package org.alifmis.warn;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public class WarnConfig {

    // MSPT
    public final boolean msptEnabled;
    public final double msptThreshold;
    public final int msptDuration;

    // CPU
    public final boolean cpuEnabled;
    public final double cpuThreshold;
    public final int cpuDuration;

    // RAM
    public final boolean ramEnabled;
    public final double ramThreshold;
    public final int ramDuration;

    // General
    public final int checkInterval;
    public final int warningCooldown;

    // Messages
    public final String msgMsptWarning;
    public final String msgCpuWarning;
    public final String msgCpuWarningOp;
    public final String msgRamWarning;
    public final String msgRamWarningOp;

    public WarnConfig(JavaPlugin plugin) {
        FileConfiguration cfg = plugin.getConfig();

        msptEnabled = cfg.getBoolean("mspt.enabled", true);
        msptThreshold = cfg.getDouble("mspt.threshold", 45.0);
        msptDuration = cfg.getInt("mspt.duration", 10);

        cpuEnabled = cfg.getBoolean("cpu.enabled", true);
        cpuThreshold = cfg.getDouble("cpu.threshold", 90.0);
        cpuDuration = cfg.getInt("cpu.duration", 10);

        ramEnabled = cfg.getBoolean("ram.enabled", true);
        ramThreshold = cfg.getDouble("ram.threshold", 90.0);
        ramDuration = cfg.getInt("ram.duration", 10);

        checkInterval = cfg.getInt("check-interval", 1);
        warningCooldown = cfg.getInt("warning-cooldown", 15);

        msgMsptWarning = cfg.getString("messages.mspt-warning",
            "<red>⚠ MSPT is high ({mspt}ms) in your region!</red>");
        msgCpuWarning = cfg.getString("messages.cpu-warning",
            "<red>⚠ CPU usage is high ({cpu}%) in your region!</red>");
        msgCpuWarningOp = cfg.getString("messages.cpu-warning-op",
            "<red>⚠ CPU usage is high ({cpu}%)! Location: {world} {x} {y} {z}</red>");
        msgRamWarning = cfg.getString("messages.ram-warning",
            "<red>⚠ Server RAM usage is high ({ram}%)!</red>");
        msgRamWarningOp = cfg.getString("messages.ram-warning-op",
            "<red>⚠ Server RAM usage is high ({ram}%)! Location: {world} {x} {y} {z}</red>");
    }
}
