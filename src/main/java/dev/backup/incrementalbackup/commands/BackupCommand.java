package dev.backup.incrementalbackup.commands;

import dev.backup.incrementalbackup.IncrementalBackupPlugin;
import dev.backup.incrementalbackup.backup.BackupManager;
import dev.backup.incrementalbackup.backup.BackupMeta;
import dev.backup.incrementalbackup.backup.BackupType;
import dev.backup.incrementalbackup.drive.DriveManager;
import dev.backup.incrementalbackup.utils.FileUtils;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.*;

public class BackupCommand implements CommandExecutor, TabCompleter {

    private final IncrementalBackupPlugin plugin;
    private final BackupManager backupManager;
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public BackupCommand(IncrementalBackupPlugin plugin, BackupManager backupManager) {
        this.plugin = plugin;
        this.backupManager = backupManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("incrementalbackup.admin")) {
            sender.sendMessage("§cYou don't have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {

            case "full" -> {
                if (backupManager.isRunning()) {
                    sender.sendMessage("§cA backup is already running!");
                    return true;
                }
                sender.sendMessage("§a[Backup] Starting manual full backup...");
                backupManager.runBackup(BackupType.FULL, sender);
            }

            case "incremental", "inc" -> {
                if (backupManager.isRunning()) {
                    sender.sendMessage("§cA backup is already running!");
                    return true;
                }
                sender.sendMessage("§e[Backup] Starting manual incremental backup...");
                backupManager.runBackup(BackupType.INCREMENTAL, sender);
            }

            case "status" -> {
                BackupMeta meta = backupManager.getMeta();
                sender.sendMessage("§6══════ Backup Status ══════");
                sender.sendMessage("§7Currently running: §f" + (backupManager.isRunning() ? "§aYES" : "§cNO"));
                sender.sendMessage("§7Last full backup: §f" + formatTime(meta.getLastFullBackupTime()));
                sender.sendMessage("§7Last full backup name: §f" + meta.getLastFullBackupName());
                sender.sendMessage("§7Last incremental: §f" + formatTime(meta.getLastIncrementalBackupTime()));
                sender.sendMessage("§7Total full backups: §f" + meta.getTotalFullBackups());
                sender.sendMessage("§7Total incremental: §f" + meta.getTotalIncrementalBackups());
                sender.sendMessage("§7Google Drive: §f" + (plugin.getDriveManager().isReady() ? "§aConnected" : "§cDisconnected"));
                sender.sendMessage("§7Full backup cron: §f" + plugin.getConfig().getString("full-backup.cron"));
                sender.sendMessage("§7Incremental cron: §f" + plugin.getConfig().getString("incremental-backup.cron"));
                sender.sendMessage("§7Track changes by: §f" + plugin.getConfig().getString("incremental-backup.track-changes-by"));
            }

            case "list" -> {
                sender.sendMessage("§6══════ Backups on Google Drive ══════");
                if (!plugin.getDriveManager().isReady()) {
                    sender.sendMessage("§cGoogle Drive is not connected.");
                    return true;
                }
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                    String folderId = plugin.getConfig().getString("google-drive.folder-id", "");
                    List<DriveManager.DriveFileInfo> files = plugin.getDriveManager().listBackups(folderId);
                    if (files.isEmpty()) {
                        sender.sendMessage("§7No backups found on Google Drive.");
                    } else {
                        for (DriveManager.DriveFileInfo f : files) {
                            String type = f.name().startsWith("FULL") ? "§aFULL" : "§eINC ";
                            sender.sendMessage(type + " §f" + f.name() + " §7(" + FileUtils.formatSize(f.size()) + ") §8" + f.createdTime());
                        }
                    }
                });
            }

            case "reload" -> {
                plugin.reloadConfig();
                sender.sendMessage("§a[Backup] Config reloaded.");
            }

            default -> sendHelp(sender);
        }

        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6══════ IncrementalBackup Commands ══════");
        sender.sendMessage("§e/backup full §7- Run a full backup now");
        sender.sendMessage("§e/backup inc §7- Run an incremental backup now");
        sender.sendMessage("§e/backup status §7- Show backup status and stats");
        sender.sendMessage("§e/backup list §7- List backups on Google Drive");
        sender.sendMessage("§e/backup reload §7- Reload config");
    }

    private String formatTime(long timestamp) {
        if (timestamp == 0) return "§8Never";
        return DATE_FORMAT.format(new Date(timestamp));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = Arrays.asList("full", "inc", "status", "list", "reload");
            List<String> result = new ArrayList<>();
            for (String s : subs) {
                if (s.startsWith(args[0].toLowerCase())) result.add(s);
            }
            return result;
        }
        return Collections.emptyList();
    }
}
