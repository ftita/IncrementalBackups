package dev.backup.incrementalbackup.utils;

import dev.backup.incrementalbackup.IncrementalBackupPlugin;
import dev.backup.incrementalbackup.backup.BackupManager;
import dev.backup.incrementalbackup.backup.BackupType;
import org.bukkit.scheduler.BukkitTask;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

public class CronScheduler {

    private final IncrementalBackupPlugin plugin;
    private final BackupManager backupManager;
    private BukkitTask task;

    public CronScheduler(IncrementalBackupPlugin plugin, BackupManager backupManager) {
        this.plugin = plugin;
        this.backupManager = backupManager;
    }

    public void start() {
        // Check every minute (1200 ticks)
        task = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, this::tick, 1200L, 1200L);
        Logger.info("Cron scheduler started.");
    }

    public void stop() {
        if (task != null) task.cancel();
    }

    private void tick() {
        LocalDateTime now = LocalDateTime.now();

        if (plugin.getConfig().getBoolean("full-backup.enabled", true)) {
            String cron = plugin.getConfig().getString("full-backup.cron", "0 3 * * 0");
            if (matchesCron(cron, now)) {
                Logger.info("Scheduled full backup triggered by cron: " + cron);
                backupManager.runBackup(BackupType.FULL, null);
            }
        }

        if (plugin.getConfig().getBoolean("incremental-backup.enabled", true)) {
            String cron = plugin.getConfig().getString("incremental-backup.cron", "0 * * * *");
            if (matchesCron(cron, now)) {
                Logger.info("Scheduled incremental backup triggered by cron: " + cron);
                backupManager.runBackup(BackupType.INCREMENTAL, null);
            }
        }
    }

    /**
     * Simple 5-field cron matcher: minute hour day-of-month month day-of-week
     */
    public static boolean matchesCron(String cron, LocalDateTime dt) {
        String[] parts = cron.trim().split("\\s+");
        if (parts.length != 5) return false;

        return matchField(parts[0], dt.getMinute(), 0, 59)
                && matchField(parts[1], dt.getHour(), 0, 23)
                && matchField(parts[2], dt.getDayOfMonth(), 1, 31)
                && matchField(parts[3], dt.getMonthValue(), 1, 12)
                && matchField(parts[4], dt.getDayOfWeek().getValue() % 7, 0, 6); // 0=Sunday
    }

    private static boolean matchField(String field, int value, int min, int max) {
        if (field.equals("*")) return true;

        // Handle step values: */5 or 1-5/2
        if (field.contains("/")) {
            String[] stepParts = field.split("/");
            int step = Integer.parseInt(stepParts[1]);
            int start = stepParts[0].equals("*") ? min : Integer.parseInt(stepParts[0]);
            for (int i = start; i <= max; i += step) {
                if (i == value) return true;
            }
            return false;
        }

        // Handle ranges: 1-5
        if (field.contains("-")) {
            String[] range = field.split("-");
            int low = Integer.parseInt(range[0]);
            int high = Integer.parseInt(range[1]);
            return value >= low && value <= high;
        }

        // Handle lists: 1,2,3
        if (field.contains(",")) {
            for (String part : field.split(",")) {
                if (Integer.parseInt(part.trim()) == value) return true;
            }
            return false;
        }

        // Single value
        return Integer.parseInt(field) == value;
    }
}
