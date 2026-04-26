package dev.backup.incrementalbackup.backup;

import dev.backup.incrementalbackup.IncrementalBackupPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;

public class ScheduleManager {

    private final IncrementalBackupPlugin plugin;
    private final BackupManager backupManager;

    private BukkitTask incrementalTask;
    private BukkitTask fullCheckTask;

    public ScheduleManager(IncrementalBackupPlugin plugin, BackupManager backupManager) {
        this.plugin = plugin;
        this.backupManager = backupManager;
    }

    public void start() {
        startIncrementalSchedule();
        startFullSchedule();
    }

    public void stop() {
        if (incrementalTask != null) incrementalTask.cancel();
        if (fullCheckTask  != null) fullCheckTask.cancel();
    }

    // ---------------------------------------------------------------
    //  Incremental – fixed interval
    // ---------------------------------------------------------------

    private void startIncrementalSchedule() {
        if (!plugin.getConfig().getBoolean("backup.incremental.enabled", true)) return;
        int intervalMinutes = plugin.getConfig().getInt("backup.incremental.interval-minutes", 30);
        long intervalTicks = (long) intervalMinutes * 60 * 20;

        incrementalTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            plugin.getLogger().info("[Scheduler] Running scheduled incremental backup...");
            backupManager.runBackup(BackupType.INCREMENTAL, null);
        }, intervalTicks, intervalTicks);

        plugin.getLogger().info("Incremental backup scheduled every " + intervalMinutes + " minutes.");
    }

    // ---------------------------------------------------------------
    //  Full – daily or specific day + time (checked every minute)
    // ---------------------------------------------------------------

    private void startFullSchedule() {
        if (!plugin.getConfig().getBoolean("backup.full.enabled", true)) return;

        String scheduleDay  = plugin.getConfig().getString("backup.full.schedule-day", "DAILY").toUpperCase();
        String scheduleTime = plugin.getConfig().getString("backup.full.schedule-time", "04:00");

        // Parse target time
        LocalTime targetTime;
        try {
            targetTime = LocalTime.parse(scheduleTime, DateTimeFormatter.ofPattern("HH:mm"));
        } catch (Exception e) {
            plugin.getLogger().warning("Invalid full backup schedule-time: " + scheduleTime + ". Defaulting to 04:00.");
            targetTime = LocalTime.of(4, 0);
        }

        final LocalTime finalTarget = targetTime;

        // Check every minute (1200 ticks) whether it's time to run
        fullCheckTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            LocalTime now = LocalTime.now().withSecond(0).withNano(0);
            if (!now.equals(finalTarget)) return;

            if (!scheduleDay.equals("DAILY")) {
                int todayDow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK); // 1=Sun
                int targetDow = dayOfWeekValue(scheduleDay);
                if (todayDow != targetDow) return;
            }

            plugin.getLogger().info("[Scheduler] Running scheduled full backup...");
            backupManager.runBackup(BackupType.FULL, null);

        }, 1200L, 1200L);

        plugin.getLogger().info("Full backup scheduled: " + scheduleDay + " at " + scheduleTime);
    }

    private int dayOfWeekValue(String day) {
        return switch (day) {
            case "SUN" -> Calendar.SUNDAY;
            case "MON" -> Calendar.MONDAY;
            case "TUE" -> Calendar.TUESDAY;
            case "WED" -> Calendar.WEDNESDAY;
            case "THU" -> Calendar.THURSDAY;
            case "FRI" -> Calendar.FRIDAY;
            case "SAT" -> Calendar.SATURDAY;
            default    -> -1;
        };
    }
}
