package org.alifmis.warn;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
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

    // Alarm
    public final boolean alarmEnabled;
    public final Sound alarmSound;
    public final float alarmVolume;
    public final float alarmPitch;
    public final int alarmDurationSec;
    public final int alarmIntervalTicks;

    // Messages
    public final String msgMsptWarning;
    public final String msgMsptWarningOp;
    public final String msgCpuWarning;
    public final String msgCpuWarningOp;
    public final String msgRamWarning;

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

        alarmEnabled = cfg.getBoolean("alarm.enabled", true);
        alarmVolume = (float) cfg.getDouble("alarm.volume", 1.0);
        alarmPitch = (float) cfg.getDouble("alarm.pitch", 1.0);
        alarmDurationSec = cfg.getInt("alarm.duration", 3);
        alarmIntervalTicks = cfg.getInt("alarm.interval", 2);

        String soundName = cfg.getString("alarm.sound", "BLOCK_NOTE_BLOCK_PLING");
        alarmSound = resolveSound(soundName, plugin);

        msgMsptWarning = cfg.getString("messages.mspt-warning",
            "<red>⚠ MSPT is high (<mspt>ms) in your region!</red>");
        msgMsptWarningOp = cfg.getString("messages.mspt-warning-op",
            "<red>⚠ MSPT is high (<mspt>ms) in region <region> (<x> <z>)!</red>");
        msgCpuWarning = cfg.getString("messages.cpu-warning",
            "<red>⚠ CPU usage is high (<cpu>%) in your region!</red>");
        msgCpuWarningOp = cfg.getString("messages.cpu-warning-op",
            "<red>⚠ CPU usage is high (<cpu>%) in region <region> (<x> <z>)!</red>");
        msgRamWarning = cfg.getString("messages.ram-warning",
            "<red>⚠ Server RAM usage is high (<ram>%)!</red>");
    }

    private static Sound resolveSound(String name, JavaPlugin plugin) {
        Sound sound = Registry.SOUNDS.get(NamespacedKey.minecraft(name.toLowerCase()));
        if (sound == null) {
            sound = Registry.SOUNDS.get(NamespacedKey.fromString(name.toLowerCase()));
        }
        if (sound != null) return sound;

        plugin.getLogger().warning("Unknown alarm sound '" + name + "', falling back to BLOCK_NOTE_BLOCK_PLING");
        return Registry.SOUNDS.get(NamespacedKey.minecraft("block.note_block.pling"));
    }
}
