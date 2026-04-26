package dev.backup.incrementalbackup.utils;

public class FileUtils {

    public static String formatSize(long bytes) {
        if (bytes < 1024)                  return bytes + " B";
        if (bytes < 1024 * 1024)           return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024)   return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
