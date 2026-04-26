package dev.backup.incrementalbackup.drive;

import com.google.api.client.http.FileContent;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import dev.backup.incrementalbackup.IncrementalBackupPlugin;

import java.io.*;
import java.util.*;
import java.util.logging.Logger;

public class DriveManager {

    private final IncrementalBackupPlugin plugin;
    private final Logger logger;
    private Drive driveService;
    private boolean ready = false;

    private static final String TAG_PROP = "incrementalbackup_type";

    // ---- Inner record for /backup list ----
    public record DriveFileInfo(String id, String name, long size, String createdTime) {}

    public DriveManager(IncrementalBackupPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public boolean initialize() {
        String keyPath = plugin.getConfig().getString("google-drive.service-account-key", "service-account.json");
        java.io.File keyFile = new java.io.File(plugin.getDataFolder(), keyPath);

        if (!keyFile.exists()) {
            logger.warning("Service account key not found at: " + keyFile.getAbsolutePath());
            logger.warning("Google Drive upload will be disabled. Place the key file and reload.");
            return false;
        }

        try (InputStream is = new FileInputStream(keyFile)) {
            GoogleCredentials credentials = GoogleCredentials
                    .fromStream(is)
                    .createScoped(Collections.singleton(DriveScopes.DRIVE));

            driveService = new Drive.Builder(
                    new NetHttpTransport(),
                    GsonFactory.getDefaultInstance(),
                    new HttpCredentialsAdapter(credentials))
                    .setApplicationName("IncrementalBackup")
                    .build();

            ready = true;
            logger.info("Google Drive initialized successfully.");
            return true;
        } catch (Exception e) {
            logger.severe("Failed to initialize Google Drive: " + e.getMessage());
            return false;
        }
    }

    public boolean isReady() { return ready; }

    // ---------------------------------------------------------------
    //  Upload
    // ---------------------------------------------------------------

    public String uploadFile(java.io.File localFile, String fileName, String type, String folderId) {
        if (!ready) return null;
        try {
            File meta = new File();
            meta.setName(fileName);
            meta.setDescription(TAG_PROP + "=" + type);

            if (folderId != null && !folderId.isBlank()) {
                meta.setParents(Collections.singletonList(folderId));
            }

            FileContent content = new FileContent("application/zip", localFile);
            File uploaded = driveService.files().create(meta, content)
                    .setFields("id, name")
                    .execute();

            logger.info("Uploaded to Drive: " + uploaded.getName() + " (id=" + uploaded.getId() + ")");
            return uploaded.getId();
        } catch (Exception e) {
            logger.severe("Drive upload failed: " + e.getMessage());
            return null;
        }
    }

    // ---------------------------------------------------------------
    //  List backups
    // ---------------------------------------------------------------

    public List<DriveFileInfo> listBackups(String folderId) {
        if (!ready) return List.of();
        try {
            // Match files named "incremental-..." or "full-..." uploaded by this plugin
            String q = "(name contains 'incremental-' or name contains 'full-') and mimeType = 'application/zip' and trashed = false";
            if (folderId != null && !folderId.isBlank()) {
                q += " and '" + folderId + "' in parents";
            }

            FileList result = driveService.files().list()
                    .setQ(q)
                    .setOrderBy("createdTime desc")
                    .setFields("files(id, name, size, createdTime)")
                    .execute();

            List<DriveFileInfo> list = new ArrayList<>();
            if (result.getFiles() != null) {
                for (File f : result.getFiles()) {
                    long size = f.getSize() != null ? f.getSize() : 0L;
                    String created = f.getCreatedTime() != null ? f.getCreatedTime().toString() : "unknown";
                    list.add(new DriveFileInfo(f.getId(), f.getName(), size, created));
                }
            }
            return list;
        } catch (Exception e) {
            logger.warning("Failed to list Drive backups: " + e.getMessage());
            return List.of();
        }
    }

    // ---------------------------------------------------------------
    //  Retention
    // ---------------------------------------------------------------

    public void applyRetention(String type, int keep, String folderId) {
        if (!ready) return;
        try {
            // type is "FULL" or "INCREMENTAL" — match by filename prefix (lowercase)
            String prefix = type.toLowerCase() + "-";
            String q = "name contains '" + prefix + "' and mimeType = 'application/zip' and trashed = false";
            if (folderId != null && !folderId.isBlank()) {
                q += " and '" + folderId + "' in parents";
            }

            FileList result = driveService.files().list()
                    .setQ(q)
                    .setOrderBy("createdTime")
                    .setFields("files(id, name)")
                    .execute();

            List<File> files = result.getFiles();
            if (files == null || files.size() <= keep) return;

            int toDelete = files.size() - keep;
            for (int i = 0; i < toDelete; i++) {
                File f = files.get(i);
                driveService.files().delete(f.getId()).execute();
                logger.info("Retention: deleted old backup " + f.getName());
            }
        } catch (Exception e) {
            logger.warning("Retention cleanup failed: " + e.getMessage());
        }
    }
}
