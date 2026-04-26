package dev.backup.incrementalbackup.utils;

import dev.backup.incrementalbackup.IncrementalBackupPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.logging.Level;

public class Logger {

    private static IncrementalBackupPlugin plugin;
    private static final String PREFIX = "[IncrementalBackup] ";

    public static void init(IncrementalBackupPlugin p) {
        plugin = p;
    }

    public static void info(String msg) {
        plugin.getLogger().info(msg);
        if (plugin.getConfig().getBoolean("notifications.notify-ops", true)) {
            notifyOps(ChatColor.GREEN + PREFIX + msg);
        }
    }

    public static void warn(String msg) {
        plugin.getLogger().warning(msg);
        if (plugin.getConfig().getBoolean("notifications.notify-ops", true)) {
            notifyOps(ChatColor.YELLOW + PREFIX + "⚠ " + msg);
        }
    }

    public static void error(String msg) {
        plugin.getLogger().severe(msg);
        if (plugin.getConfig().getBoolean("notifications.notify-ops", true)) {
            notifyOps(ChatColor.RED + PREFIX + "✗ " + msg);
        }
    }

    public static void error(String msg, Throwable t) {
        plugin.getLogger().log(Level.SEVERE, msg, t);
        if (plugin.getConfig().getBoolean("notifications.notify-ops", true)) {
            notifyOps(ChatColor.RED + PREFIX + "✗ " + msg);
        }
    }

    public static void console(String msg) {
        plugin.getLogger().info(msg);
    }

    private static void notifyOps(String msg) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isOp() || p.hasPermission("incrementalbackup.admin")) {
                p.sendMessage(msg);
            }
        }
    }
}
