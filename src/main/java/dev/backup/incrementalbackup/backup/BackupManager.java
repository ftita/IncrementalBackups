package dev.backup.incrementalbackup.backup;

import dev.backup.incrementalbackup.IncrementalBackupPlugin;
import dev.backup.incrementalbackup.drive.DriveManager;
import dev.backup.incrementalbackup.util.GlobMatcher;
import org.bukkit.command.CommandSender;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class BackupManager {

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");

    private final IncrementalBackupPlugin plugin;
    private final DriveManager driveManager;
    private final Logger logger;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final BackupMeta meta;

    public BackupManager(IncrementalBackupPlugin plugin, DriveManager driveManager) {
        this.plugin = plugin;
        this.driveManager = driveManager;
        this.logger = plugin.getLogger();
        this.meta = new BackupMeta(plugin);
    }

    // ---------------------------------------------------------------
    //  Public API
    // ---------------------------------------------------------------

    public boolean isRunning()     { return running.get(); }
    public BackupMeta getMeta()    { return meta; }

    // Kept for backward compat with ScheduleManager
    public String getLastIncrementalTime() {
        long t = meta.getLastIncrementalBackupTime();
        return t == 0 ? "Never" : DATE_FORMAT.format(new Date(t));
    }

    public String getLastFullTime() {
        long t = meta.getLastFullBackupTime();
        return t == 0 ? "Never" : DATE_FORMAT.format(new Date(t));
    }

    public void runBackup(BackupType type, CommandSender sender) {
        if (!running.compareAndSet(false, true)) {
            msg(sender, "§cA backup is already in progress!");
            return;
        }
        try {
            if (type == BackupType.FULL) {
                runFull(sender);
            } else {
                runIncremental(sender);
            }
        } finally {
            running.set(false);
        }
    }

    // ---------------------------------------------------------------
    //  Incremental
    // ---------------------------------------------------------------

    private void runIncremental(CommandSender sender) {
        msg(sender, "§7Starting §eincremental §7backup...");
        long startMs = System.currentTimeMillis();

        List<String> includes = plugin.getConfig().getStringList("backup.incremental.include");
        List<String> excludes = plugin.getConfig().getStringList("backup.incremental.exclude");
        boolean onlyModified  = plugin.getConfig().getBoolean("backup.incremental.only-modified", true);

        Path serverRoot = plugin.getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
        String timestamp = DATE_FORMAT.format(new Date());
        String zipName   = "incremental-" + timestamp + ".zip";
        File tempZip     = new File(plugin.getDataFolder(), "tmp_" + zipName);

        try {
            long afterTs = onlyModified ? meta.getLastIncrementalTimestamp() : 0L;
            List<Path> files = collectFiles(serverRoot, includes, excludes, afterTs);

            if (files.isEmpty()) {
                msg(sender, "§7No modified files found since last backup. Skipping.");
                return;
            }

            msg(sender, "§7Found §f" + files.size() + " §7files to backup.");
            createZip(serverRoot, files, tempZip);

            meta.recordIncremental();

            if (driveManager.isReady()) {
                String folderId = plugin.getConfig().getString("google-drive.drive-folder-id", "");
                String id = driveManager.uploadFile(tempZip, zipName, "INCREMENTAL", folderId);
                if (id != null) {
                    msg(sender, "§aIncremental backup uploaded: §f" + zipName);
                    int keep = plugin.getConfig().getInt("backup.retention.keep-incremental", 48);
                    if (keep > 0) driveManager.applyRetention("INCREMENTAL", keep, folderId);
                } else {
                    msg(sender, "§cUpload failed. Zip saved locally: §f" + tempZip.getName());
                }
            } else {
                msg(sender, "§eDrive not configured. Saved locally: §f" + tempZip.getAbsolutePath());
            }

        } catch (Exception e) {
            logger.severe("Incremental backup failed: " + e.getMessage());
            msg(sender, "§cIncremental backup failed: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (driveManager.isReady()) tempZip.delete();
        }
    }

    // ---------------------------------------------------------------
    //  Full
    // ---------------------------------------------------------------

    private void runFull(CommandSender sender) {
        msg(sender, "§7Starting §cfull §7backup...");

        boolean saveWorld = plugin.getConfig().getBoolean("backup.full.save-world-before", true);
        if (saveWorld) {
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> plugin.getServer().getWorlds().forEach(w -> w.save()));
            try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
        }

        List<String> includes = plugin.getConfig().getStringList("backup.full.include");
        List<String> excludes = plugin.getConfig().getStringList("backup.full.exclude");

        Path serverRoot = plugin.getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
        String timestamp = DATE_FORMAT.format(new Date());
        String zipName   = "full-" + timestamp + ".zip";
        File tempZip     = new File(plugin.getDataFolder(), "tmp_" + zipName);

        try {
            List<Path> files = collectFiles(serverRoot, includes, excludes, 0L);
            msg(sender, "§7Packing §f" + files.size() + " §7files...");
            createZip(serverRoot, files, tempZip);

            meta.recordFull(zipName);

            if (driveManager.isReady()) {
                String folderId = plugin.getConfig().getString("google-drive.drive-folder-id", "");
                String id = driveManager.uploadFile(tempZip, zipName, "FULL", folderId);
                if (id != null) {
                    msg(sender, "§aFull backup uploaded: §f" + zipName);
                    int keep = plugin.getConfig().getInt("backup.retention.keep-full", 7);
                    if (keep > 0) driveManager.applyRetention("FULL", keep, folderId);
                } else {
                    msg(sender, "§cUpload failed. Saved locally.");
                }
            } else {
                msg(sender, "§eDrive not configured. Saved locally: §f" + tempZip.getAbsolutePath());
            }

        } catch (Exception e) {
            logger.severe("Full backup failed: " + e.getMessage());
            msg(sender, "§cFull backup failed: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (driveManager.isReady()) tempZip.delete();
        }
    }

    // ---------------------------------------------------------------
    //  File Collection
    // ---------------------------------------------------------------

    private List<Path> collectFiles(Path root, List<String> includes,
                                    List<String> excludes, long afterTimestamp) throws IOException {
        List<Path> result = new ArrayList<>();

        for (String inc : includes) {
            Path target = inc.equals(".") ? root : root.resolve(inc).normalize();
            if (!Files.exists(target)) continue;

            if (Files.isRegularFile(target)) {
                if (shouldInclude(root, target, excludes, afterTimestamp)) result.add(target);
            } else {
                Files.walkFileTree(target, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (shouldInclude(root, file, excludes, afterTimestamp)) result.add(file);
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        }
        return result;
    }

    private boolean shouldInclude(Path root, Path file, List<String> excludes, long afterTimestamp) {
        if (afterTimestamp > 0) {
            try {
                if (Files.getLastModifiedTime(file).toMillis() <= afterTimestamp) return false;
            } catch (IOException e) { return false; }
        }
        String rel = root.relativize(file).toString().replace("\\", "/");
        for (String pattern : excludes) {
            if (GlobMatcher.matches(rel, pattern)) return false;
        }
        return true;
    }

    // ---------------------------------------------------------------
    //  Zip Creation
    // ---------------------------------------------------------------

    private void createZip(Path root, List<Path> files, File output) throws IOException {
        output.getParentFile().mkdirs();
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(output)))) {
            for (Path file : files) {
                String entry = root.relativize(file).toString().replace("\\", "/");
                zos.putNextEntry(new ZipEntry(entry));
                Files.copy(file, zos);
                zos.closeEntry();
            }
        }
    }

    // ---------------------------------------------------------------
    //  Helpers
    // ---------------------------------------------------------------

    private void msg(CommandSender sender, String text) {
        String prefix = plugin.getConfig().getString("message-prefix", "&8[&bBackup&8] &r").replace("&", "§");
        logger.info(text.replaceAll("§[0-9a-fk-or]", ""));
        if (sender != null) sender.sendMessage(prefix + text);
    }
}
