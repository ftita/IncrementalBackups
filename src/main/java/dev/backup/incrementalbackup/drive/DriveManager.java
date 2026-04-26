package dev.backup.incrementalbackup.drive;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.http.FileContent;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import dev.backup.incrementalbackup.IncrementalBackupPlugin;

import java.io.*;
import java.util.*;
import java.util.logging.Logger;

public class DriveManager {

    private final IncrementalBackupPlugin plugin;
    private final Logger logger;
    private Drive driveService;
    private boolean ready = false;

    public record DriveFileInfo(String id, String name, long size, String createdTime) {}

    public DriveManager(IncrementalBackupPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public boolean initialize() {
        String secretPath = plugin.getConfig().getString("google-drive.oauth-client-secret", "client_secret.json");
        java.io.File secretFile = new java.io.File(plugin.getDataFolder(), secretPath);

        if (!secretFile.exists()) {
            logger.warning("OAuth client secret not found at: " + secretFile.getAbsolutePath());
            logger.warning("Google Drive upload disabled. See README for setup instructions.");
            return false;
        }

        try {
            NetHttpTransport transport = new NetHttpTransport();
            GsonFactory jsonFactory = GsonFactory.getDefaultInstance();

            // Load client secrets
            GoogleClientSecrets clientSecrets;
            try (Reader reader = new FileReader(secretFile)) {
                clientSecrets = GoogleClientSecrets.load(jsonFactory, reader);
            }

            // Token store — persists the OAuth token between restarts
            java.io.File tokenDir = new java.io.File(plugin.getDataFolder(), "tokens");

            GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                    transport, jsonFactory, clientSecrets,
                    Collections.singleton(DriveScopes.DRIVE_FILE))
                    .setDataStoreFactory(new FileDataStoreFactory(tokenDir))
                    .setAccessType("offline")
                    .build();

            // If a stored token exists, use it silently.
            // If not, print the auth URL to console (server has no browser).
            Credential credential = flow.loadCredential("user");

            if (credential == null || credential.getAccessToken() == null && credential.getRefreshToken() == null) {
                // No token yet — start local receiver on a high port and print the URL
                LocalServerReceiver receiver = new LocalServerReceiver.Builder().setPort(18080).build();
                logger.info("========================================================");
                logger.info("  Google Drive authorization required!");
                logger.info("  Open the following URL in your browser to authorize:");
                logger.info("  (The server will wait up to 5 minutes)");
                logger.info("========================================================");

                credential = new AuthorizationCodeInstalledApp(flow, receiver).authorize("user");

                logger.info("========================================================");
                logger.info("  Authorization successful! Token saved.");
                logger.info("========================================================");
            }

            driveService = new Drive.Builder(transport, jsonFactory, credential)
                    .setApplicationName("IncrementalBackup")
                    .build();

            ready = true;
            logger.info("Google Drive (OAuth2) initialized successfully.");
            return true;

        } catch (Exception e) {
            logger.severe("Failed to initialize Google Drive OAuth2: " + e.getMessage());
            e.printStackTrace();
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
            meta.setDescription("incrementalbackup_type=" + type);

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
