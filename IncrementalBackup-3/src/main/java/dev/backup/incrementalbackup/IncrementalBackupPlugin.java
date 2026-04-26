package dev.backup.incrementalbackup;

import dev.backup.incrementalbackup.backup.BackupManager;
import dev.backup.incrementalbackup.backup.ScheduleManager;
import dev.backup.incrementalbackup.commands.BackupCommand;
import dev.backup.incrementalbackup.drive.DriveManager;
import dev.backup.incrementalbackup.utils.Logger;
import org.bukkit.plugin.java.JavaPlugin;

public class IncrementalBackupPlugin extends JavaPlugin {

    private BackupManager backupManager;
    private DriveManager driveManager;
    private ScheduleManager scheduleManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // Init static logger utility
        Logger.init(this);

        driveManager = new DriveManager(this);
        if (!driveManager.initialize()) {
            getLogger().severe("Failed to initialize Google Drive. Backups will be saved locally only.");
        }

        backupManager = new BackupManager(this, driveManager);
        scheduleManager = new ScheduleManager(this, backupManager);
        scheduleManager.start();

        getCommand("backup").setExecutor(new BackupCommand(this, backupManager));

        getLogger().info("IncrementalBackup enabled!");
    }

    @Override
    public void onDisable() {
        if (scheduleManager != null) scheduleManager.stop();
        getLogger().info("IncrementalBackup disabled.");
    }

    public BackupManager getBackupManager() { return backupManager; }
    public DriveManager getDriveManager()   { return driveManager; }
}
