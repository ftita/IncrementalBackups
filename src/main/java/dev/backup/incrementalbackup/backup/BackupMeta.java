package dev.backup.incrementalbackup.backup;

import dev.backup.incrementalbackup.IncrementalBackupPlugin;

import java.io.*;
import java.util.logging.Logger;

public class BackupMeta {

    private final java.io.File stateFile;
    private final Logger logger;

    private long lastFullBackupTime = 0L;
    private long lastIncrementalBackupTime = 0L;
    private String lastFullBackupName = "None";
    private int totalFullBackups = 0;
    private int totalIncrementalBackups = 0;

    public BackupMeta(IncrementalBackupPlugin plugin) {
        this.stateFile = new java.io.File(plugin.getDataFolder(), "backup_meta.dat");
        this.logger = plugin.getLogger();
        load();
    }

    // ---- Getters ----

    public long getLastFullBackupTime()        { return lastFullBackupTime; }
    public long getLastIncrementalBackupTime() { return lastIncrementalBackupTime; }
    public String getLastFullBackupName()      { return lastFullBackupName; }
    public int getTotalFullBackups()           { return totalFullBackups; }
    public int getTotalIncrementalBackups()    { return totalIncrementalBackups; }

    // ---- Setters (call save() after updating) ----

    public void recordFull(String name) {
        lastFullBackupTime = System.currentTimeMillis();
        lastFullBackupName = name;
        totalFullBackups++;
        save();
    }

    public void recordIncremental() {
        lastIncrementalBackupTime = System.currentTimeMillis();
        totalIncrementalBackups++;
        save();
    }

    public long getLastIncrementalTimestamp() { return lastIncrementalBackupTime; }

    // ---- Persistence ----

    private void load() {
        if (!stateFile.exists()) return;
        try (DataInputStream dis = new DataInputStream(new FileInputStream(stateFile))) {
            lastFullBackupTime        = dis.readLong();
            lastIncrementalBackupTime = dis.readLong();
            lastFullBackupName        = dis.readUTF();
            totalFullBackups          = dis.readInt();
            totalIncrementalBackups   = dis.readInt();
        } catch (IOException e) {
            logger.warning("Could not load backup meta: " + e.getMessage());
        }
    }

    public void save() {
        stateFile.getParentFile().mkdirs();
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(stateFile))) {
            dos.writeLong(lastFullBackupTime);
            dos.writeLong(lastIncrementalBackupTime);
            dos.writeUTF(lastFullBackupName);
            dos.writeInt(totalFullBackups);
            dos.writeInt(totalIncrementalBackups);
        } catch (IOException e) {
            logger.warning("Could not save backup meta: " + e.getMessage());
        }
    }
}
