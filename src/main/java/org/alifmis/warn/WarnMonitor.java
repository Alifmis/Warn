package org.alifmis.warn;

import com.sun.management.OperatingSystemMXBean;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class WarnMonitor {

    private final JavaPlugin plugin;
    private final WarnConfig config;
    private final MiniMessage miniMessage;
    private ScheduledTask mainTask;

    // Double-buffer for MSPT measurements across cycles
    private final Map<UUID, Long> msptBufferA = new ConcurrentHashMap<>();
    private final Map<UUID, Long> msptBufferB = new ConcurrentHashMap<>();
    private boolean msptActiveA = true;

    // Exceed tracking: per-region for MSPT and CPU, global for RAM
    private final Map<String, ExceedState> msptExceeds = new ConcurrentHashMap<>();
    private final Map<String, ExceedState> cpuExceeds = new ConcurrentHashMap<>();
    private final ExceedState ramExceed = new ExceedState();

    // Per-player warning cooldowns
    private final Map<UUID, Long> msptLastWarn = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cpuLastWarn = new ConcurrentHashMap<>();
    private final Map<UUID, Long> ramLastWarn = new ConcurrentHashMap<>();

    private final OperatingSystemMXBean osBean;

    private long tickCount = 0;
    private static final int REGION_SHIFT = 2; // 4 chunks per region side

    public WarnMonitor(JavaPlugin plugin, WarnConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.miniMessage = plugin instanceof WarnPlugin wp ? wp.getMiniMessage() : MiniMessage.miniMessage();
        this.osBean = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
    }

    public void start() {
        long intervalTicks = Math.max(1, config.checkInterval * 20L);
        mainTask = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(
            plugin, this::checkTick, 20L, intervalTicks
        );
    }

    public void stop() {
        if (mainTask != null) {
            mainTask.cancel();
            mainTask = null;
        }
    }

    private void checkTick(ScheduledTask task) {
        tickCount++;

        if (config.msptEnabled) {
            Map<UUID, Long> readyBuffer = msptActiveA ? msptBufferA : msptBufferB;
            msptActiveA = !msptActiveA;
            Map<UUID, Long> activeBuffer = msptActiveA ? msptBufferA : msptBufferB;

            if (tickCount > 1 && !readyBuffer.isEmpty()) {
                processMsptResults(readyBuffer);
            }
            readyBuffer.clear();

            Collection<? extends Player> players = Bukkit.getOnlinePlayers();
            if (!players.isEmpty()) {
                long measureStart = System.nanoTime();
                for (Player player : players) {
                    Location loc = player.getLocation();
                    AtomicLong startNanos = new AtomicLong(measureStart);
                    try {
                        plugin.getServer().getRegionScheduler().execute(plugin, loc, () -> {
                            long delay = System.nanoTime() - startNanos.get();
                            long corrected = Math.max(0, delay - 30_000_000L);
                            activeBuffer.put(player.getUniqueId(), corrected);
                        });
                    } catch (Exception e) {
                        // Chunk not loaded — skip
                    }
                }
            }
        }

        if (config.cpuEnabled) {
            checkCpu();
        }

        if (config.ramEnabled) {
            checkRam();
        }
    }

    private void processMsptResults(Map<UUID, Long> delays) {
        long now = System.currentTimeMillis();

        Map<String, List<Player>> regionPlayers = new LinkedHashMap<>();
        Map<String, Double> regionMaxMspt = new LinkedHashMap<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            Long delayNanos = delays.get(player.getUniqueId());
            if (delayNanos == null) continue;

            double mspt = Math.max(0, delayNanos / 1_000_000.0);
            String regionKey = getRegionKey(player.getLocation());

            regionPlayers.computeIfAbsent(regionKey, k -> new ArrayList<>()).add(player);
            regionMaxMspt.merge(regionKey, mspt, Math::max);
        }

        for (Map.Entry<String, Double> entry : regionMaxMspt.entrySet()) {
            String regionKey = entry.getKey();
            double mspt = entry.getValue();
            ExceedState state = msptExceeds.computeIfAbsent(regionKey, k -> new ExceedState());

            if (mspt > config.msptThreshold) {
                if (state.exceedStartMs == 0) state.exceedStartMs = now;
                if ((now - state.exceedStartMs) / 1000 >= config.msptDuration) {
                    if ((now - state.lastWarnMs) >= config.warningCooldown * 1000L) {
                        state.lastWarnMs = now;
                        warnMspt(regionKey, mspt, regionPlayers.getOrDefault(regionKey, List.of()));
                    }
                }
            } else {
                state.exceedStartMs = 0;
            }
        }

        msptExceeds.keySet().removeIf(key -> !regionPlayers.containsKey(key));
    }

    private void checkCpu() {
        double cpuLoad = osBean.getProcessCpuLoad();
        if (cpuLoad < 0 || Double.isNaN(cpuLoad)) return;

        double cpuPercent = cpuLoad * 100.0;
        long now = System.currentTimeMillis();

        // Group current players by region
        Map<String, List<Player>> regionPlayers = new LinkedHashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            regionPlayers.computeIfAbsent(getRegionKey(player.getLocation()),
                k -> new ArrayList<>()).add(player);
        }

        // Check threshold per region (same CPU value for all)
        for (Map.Entry<String, List<Player>> entry : regionPlayers.entrySet()) {
            String regionKey = entry.getKey();
            ExceedState state = cpuExceeds.computeIfAbsent(regionKey, k -> new ExceedState());

            if (cpuPercent > config.cpuThreshold) {
                if (state.exceedStartMs == 0) state.exceedStartMs = now;
                if ((now - state.exceedStartMs) / 1000 >= config.cpuDuration) {
                    if ((now - state.lastWarnMs) >= config.warningCooldown * 1000L) {
                        state.lastWarnMs = now;
                        warnCpu(regionKey, cpuPercent, entry.getValue());
                    }
                }
            } else {
                state.exceedStartMs = 0;
            }
        }

        cpuExceeds.keySet().removeIf(key -> !regionPlayers.containsKey(key));
    }

    private void checkRam() {
        Runtime runtime = Runtime.getRuntime();
        long maxMem = runtime.maxMemory();
        if (maxMem <= 0) return;

        long usedMem = runtime.totalMemory() - runtime.freeMemory();
        double ramPercent = (double) usedMem / maxMem * 100.0;
        long now = System.currentTimeMillis();

        if (ramPercent > config.ramThreshold) {
            if (ramExceed.exceedStartMs == 0) ramExceed.exceedStartMs = now;
            if ((now - ramExceed.exceedStartMs) / 1000 >= config.ramDuration) {
                if ((now - ramExceed.lastWarnMs) >= config.warningCooldown * 1000L) {
                    ramExceed.lastWarnMs = now;
                    warnRam(ramPercent);
                }
            }
        } else {
            ramExceed.exceedStartMs = 0;
        }
    }

    private void warnMspt(String regionKey, double mspt, List<Player> regionPlayers) {
        plugin.getLogger().warning(String.format(
            "⚠ MSPT %.1fms (threshold %.1fms) in region %s",
            mspt, config.msptThreshold, regionKey));

        String msptStr = String.format("%.1f", mspt);

        for (Player player : Bukkit.getOnlinePlayers()) {
            String playerRegion = getRegionKey(player.getLocation());
            boolean isOp = player.hasPermission("warn.notify") || player.isOp();

            if (isOp) {
                // OPs get ALL MSPT warnings + lagging region's approximate coords
                int[] regionCoords = regionCenterCoords(regionKey);
                Component msg = miniMessage.deserialize(config.msgMsptWarningOp,
                    Placeholder.unparsed("mspt", msptStr),
                    Placeholder.unparsed("region", regionKey),
                    Placeholder.unparsed("x", String.valueOf(regionCoords[0])),
                    Placeholder.unparsed("z", String.valueOf(regionCoords[1])));
                sendIfCooldown(player, msptLastWarn, msg);
            } else if (playerRegion.equals(regionKey)) {
                // Only players in the affected region
                Component msg = miniMessage.deserialize(config.msgMsptWarning,
                    Placeholder.unparsed("mspt", msptStr));
                sendIfCooldown(player, msptLastWarn, msg);
            }
        }
    }

    private void warnCpu(String regionKey, double cpuPercent, List<Player> regionPlayers) {
        plugin.getLogger().warning(String.format(
            "⚠ CPU %.1f%% (threshold %.1f%%) in region %s",
            cpuPercent, config.cpuThreshold, regionKey));

        String cpuStr = String.format("%.1f", cpuPercent);

        for (Player player : Bukkit.getOnlinePlayers()) {
            String playerRegion = getRegionKey(player.getLocation());
            boolean isOp = player.hasPermission("warn.notify") || player.isOp();

            if (isOp) {
                // OPs get ALL CPU warnings with their region info
                Component msg = miniMessage.deserialize(config.msgCpuWarningOp,
                    Placeholder.unparsed("cpu", cpuStr),
                    Placeholder.unparsed("region", regionKey));
                sendIfCooldown(player, cpuLastWarn, msg);
            } else if (playerRegion.equals(regionKey)) {
                // Only players in the affected region
                Component msg = miniMessage.deserialize(config.msgCpuWarning,
                    Placeholder.unparsed("cpu", cpuStr));
                sendIfCooldown(player, cpuLastWarn, msg);
            }
        }
    }

    private void warnRam(double ramPercent) {
        plugin.getLogger().warning(String.format(
            "⚠ RAM usage %.1f%% exceeded threshold %.1f%%",
            ramPercent, config.ramThreshold));

        String ramStr = String.format("%.1f", ramPercent);

        for (Player player : Bukkit.getOnlinePlayers()) {
            boolean isOp = player.hasPermission("warn.notify") || player.isOp();

            // RAM is server-wide — same message for everyone (OP and non-OP)
            Component msg = miniMessage.deserialize(config.msgRamWarning,
                Placeholder.unparsed("ram", ramStr));
            sendIfCooldown(player, ramLastWarn, msg);
        }
    }

    private void sendIfCooldown(Player player, Map<UUID, Long> cooldownMap, Component message) {
        long now = System.currentTimeMillis();
        Long last = cooldownMap.get(player.getUniqueId());

        if (last == null || (now - last) >= config.warningCooldown * 1000L) {
            cooldownMap.put(player.getUniqueId(), now);
            player.sendMessage(message);
        }
    }

    private String getRegionKey(Location loc) {
        if (loc.getWorld() == null) return "unknown";
        int cx = loc.getBlockX() >> 4;
        int cz = loc.getBlockZ() >> 4;
        int rx = cx >> REGION_SHIFT;
        int rz = cz >> REGION_SHIFT;
        return loc.getWorld().getName() + "/r" + rx + "," + rz;
    }

    private static int[] regionCenterCoords(String regionKey) {
        // Format: "world/rX,Z"
        int slash = regionKey.lastIndexOf('/');
        if (slash < 0) return new int[]{0, 0};
        String coords = regionKey.substring(slash + 1); // "r2,-1"
        if (!coords.startsWith("r")) return new int[]{0, 0};
        String[] parts = coords.substring(1).split(",");
        if (parts.length != 2) return new int[]{0, 0};
        try {
            int rx = Integer.parseInt(parts[0]);
            int rz = Integer.parseInt(parts[1]);
            int blockSize = (1 << REGION_SHIFT) * 16; // region step in blocks
            return new int[]{rx * blockSize + blockSize / 2, rz * blockSize + blockSize / 2};
        } catch (NumberFormatException e) {
            return new int[]{0, 0};
        }
    }

    private static class ExceedState {
        long exceedStartMs = 0;
        long lastWarnMs = 0;
    }
}
