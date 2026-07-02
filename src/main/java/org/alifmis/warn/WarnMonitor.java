package org.alifmis.warn;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class WarnMonitor {

    private final JavaPlugin plugin;
    private final WarnConfig config;
    private final MiniMessage miniMessage;
    private ScheduledTask mainTask;
    private final ThreadMXBean threadBean;

    // Double-buffer for per-player samples
    private final Map<UUID, RegionSample> bufferA = new ConcurrentHashMap<>();
    private final Map<UUID, RegionSample> bufferB = new ConcurrentHashMap<>();
    private boolean activeA = true;

    // Previous cycle samples for CPU delta computation (keyed by threadId)
    private volatile Map<Long, ThreadCpuSnapshot> prevThreadSnapshots = Map.of();

    // Exceed tracking
    private final Map<String, ExceedState> msptExceeds = new ConcurrentHashMap<>();
    private final Map<String, ExceedState> cpuExceeds = new ConcurrentHashMap<>();
    private final ExceedState ramExceed = new ExceedState();

    // Per-player warning cooldowns
    private final Map<UUID, Long> msptLastWarn = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cpuLastWarn = new ConcurrentHashMap<>();
    private final Map<UUID, Long> ramLastWarn = new ConcurrentHashMap<>();

    private long tickCount = 0;
    private static final int REGION_SHIFT = 2;

    public WarnMonitor(JavaPlugin plugin, WarnConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.miniMessage = plugin instanceof WarnPlugin wp ? wp.getMiniMessage() : MiniMessage.miniMessage();
        this.threadBean = ManagementFactory.getThreadMXBean();
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

        boolean needMspt = config.msptEnabled;
        boolean needCpu = config.cpuEnabled;

        if (needMspt || needCpu) {
            Map<UUID, RegionSample> readyBuffer = activeA ? bufferA : bufferB;
            activeA = !activeA;
            Map<UUID, RegionSample> activeBuffer = activeA ? bufferA : bufferB;

            if (tickCount > 1 && !readyBuffer.isEmpty()) {
                processResults(readyBuffer);
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
                            long wallNanos = System.nanoTime();
                            long delay = wallNanos - startNanos.get();
                            long threadId = Thread.currentThread().getId();
                            long cpuNanos = threadBean.getCurrentThreadCpuTime();
                            activeBuffer.put(player.getUniqueId(),
                                new RegionSample(delay, wallNanos, threadId, cpuNanos));
                        });
                    } catch (Exception e) {
                        // Chunk not loaded — skip
                    }
                }
            }
        }

        if (config.ramEnabled) {
            checkRam();
        }
    }

    private void processResults(Map<UUID, RegionSample> samples) {
        long now = System.currentTimeMillis();

        // Capture current thread CPU snapshots for delta computation
        Map<Long, ThreadCpuSnapshot> currentSnapshots = new HashMap<>();
        for (RegionSample s : samples.values()) {
            currentSnapshots.merge(s.threadId,
                new ThreadCpuSnapshot(s.wallNanos, s.cpuNanos),
                (a, b) -> a.wallNanos >= b.wallNanos ? a : b);
        }

        // Group players by region
        Map<String, List<Player>> regionPlayers = new LinkedHashMap<>();
        Map<String, Double> regionMaxMspt = new LinkedHashMap<>();
        Map<String, Double> regionMaxCpu = new LinkedHashMap<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            RegionSample sample = samples.get(player.getUniqueId());
            if (sample == null) continue;

            String regionKey = getRegionKey(player.getLocation());
            regionPlayers.computeIfAbsent(regionKey, k -> new ArrayList<>()).add(player);

            // MSPT: use scheduling delay directly
            double rawMspt = Math.max(0, sample.delayNanos / 1_000_000.0);

            // Track raw MSPT per region
            regionMaxMspt.merge(regionKey, rawMspt, Math::max);

            // CPU: compute per-thread utilization
            if (config.cpuEnabled && prevThreadSnapshots != null) {
                ThreadCpuSnapshot prev = prevThreadSnapshots.get(sample.threadId);
                ThreadCpuSnapshot cur = currentSnapshots.get(sample.threadId);
                if (prev != null && cur != null && cur.wallNanos > prev.wallNanos) {
                    long deltaWall = cur.wallNanos - prev.wallNanos;
                    long deltaCpu = cur.cpuNanos - prev.cpuNanos;
                    if (deltaWall > 0) {
                        double cpuPercent = (double) deltaCpu / deltaWall * 100.0;
                        regionMaxCpu.merge(regionKey, cpuPercent, Math::max);
                    }
                }
            }
        }

        // Store current snapshots for next cycle
        prevThreadSnapshots = currentSnapshots;

        // Check thresholds per region
        for (Map.Entry<String, Double> entry : regionMaxMspt.entrySet()) {
            String regionKey = entry.getKey();
            double mspt = entry.getValue();
            List<Player> players = regionPlayers.getOrDefault(regionKey, List.of());

            // MSPT
            if (config.msptEnabled) {
                ExceedState state = msptExceeds.computeIfAbsent(regionKey, k -> new ExceedState());
                if (mspt > config.msptThreshold) {
                    if (state.exceedStartMs == 0) state.exceedStartMs = now;
                    if ((now - state.exceedStartMs) / 1000 >= config.msptDuration) {
                        if ((now - state.lastWarnMs) >= config.warningCooldown * 1000L) {
                            state.lastWarnMs = now;
                            warnMspt(regionKey, mspt, players);
                        }
                    }
                } else {
                    state.exceedStartMs = 0;
                }
            }
        }

        for (Map.Entry<String, Double> entry : regionMaxCpu.entrySet()) {
            String regionKey = entry.getKey();
            double cpuPercent = entry.getValue();
            List<Player> players = regionPlayers.getOrDefault(regionKey, List.of());

            if (config.cpuEnabled) {
                ExceedState state = cpuExceeds.computeIfAbsent(regionKey, k -> new ExceedState());
                if (cpuPercent > config.cpuThreshold) {
                    if (state.exceedStartMs == 0) state.exceedStartMs = now;
                    if ((now - state.exceedStartMs) / 1000 >= config.cpuDuration) {
                        if ((now - state.lastWarnMs) >= config.warningCooldown * 1000L) {
                            state.lastWarnMs = now;
                            warnCpu(regionKey, cpuPercent, players);
                        }
                    }
                } else {
                    state.exceedStartMs = 0;
                }
            }
        }

        if (config.msptEnabled) msptExceeds.keySet().removeIf(key -> !regionPlayers.containsKey(key));
        if (config.cpuEnabled) cpuExceeds.keySet().removeIf(key -> !regionPlayers.containsKey(key));
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
                int[] coords = regionCenterCoords(regionKey);
                Component msg = miniMessage.deserialize(config.msgMsptWarningOp,
                    Placeholder.unparsed("mspt", msptStr),
                    Placeholder.unparsed("region", regionKey),
                    Placeholder.unparsed("x", String.valueOf(coords[0])),
                    Placeholder.unparsed("z", String.valueOf(coords[1])));
                sendIfCooldown(player, msptLastWarn, msg);
            } else if (playerRegion.equals(regionKey)) {
                sendIfCooldown(player, msptLastWarn,
                    miniMessage.deserialize(config.msgMsptWarning,
                        Placeholder.unparsed("mspt", msptStr)));
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
                int[] coords = regionCenterCoords(regionKey);
                Component msg = miniMessage.deserialize(config.msgCpuWarningOp,
                    Placeholder.unparsed("cpu", cpuStr),
                    Placeholder.unparsed("region", regionKey),
                    Placeholder.unparsed("x", String.valueOf(coords[0])),
                    Placeholder.unparsed("z", String.valueOf(coords[1])));
                sendIfCooldown(player, cpuLastWarn, msg);
            } else if (playerRegion.equals(regionKey)) {
                sendIfCooldown(player, cpuLastWarn,
                    miniMessage.deserialize(config.msgCpuWarning,
                        Placeholder.unparsed("cpu", cpuStr)));
            }
        }
    }

    private void warnRam(double ramPercent) {
        plugin.getLogger().warning(String.format(
            "⚠ RAM usage %.1f%% exceeded threshold %.1f%%",
            ramPercent, config.ramThreshold));

        String ramStr = String.format("%.1f", ramPercent);

        for (Player player : Bukkit.getOnlinePlayers()) {
            sendIfCooldown(player, ramLastWarn,
                miniMessage.deserialize(config.msgRamWarning,
                    Placeholder.unparsed("ram", ramStr)));
        }
    }

    private void sendIfCooldown(Player player, Map<UUID, Long> cooldownMap, Component message) {
        long now = System.currentTimeMillis();
        Long last = cooldownMap.get(player.getUniqueId());
        if (last == null || (now - last) >= config.warningCooldown * 1000L) {
            cooldownMap.put(player.getUniqueId(), now);
            player.sendMessage(message);
            playAlarm(player);
        }
    }

    private void playAlarm(Player player) {
        if (!config.alarmEnabled) return;
        long totalTicks = config.alarmDurationSec * 20L;
        long intervalTicks = Math.max(1, config.alarmIntervalTicks);

        var soundTask = player.getScheduler().runAtFixedRate(plugin,
            task -> player.playSound(player.getLocation(), config.alarmSound,
                config.alarmVolume, config.alarmPitch),
            null, 1L, intervalTicks);

        player.getScheduler().runDelayed(plugin,
            task -> soundTask.cancel(), null, totalTicks);
    }

    private String getRegionKey(Location loc) {
        if (loc.getWorld() == null) return "unknown";
        int cx = loc.getBlockX() >> 4;
        int cz = loc.getBlockZ() >> 4;
        return loc.getWorld().getName() + "/r" + (cx >> REGION_SHIFT) + "," + (cz >> REGION_SHIFT);
    }

    private static int[] regionCenterCoords(String regionKey) {
        int slash = regionKey.lastIndexOf('/');
        if (slash < 0) return new int[]{0, 0};
        String coords = regionKey.substring(slash + 1);
        if (!coords.startsWith("r")) return new int[]{0, 0};
        String[] parts = coords.substring(1).split(",");
        if (parts.length != 2) return new int[]{0, 0};
        try {
            int rx = Integer.parseInt(parts[0]), rz = Integer.parseInt(parts[1]);
            int bs = (1 << REGION_SHIFT) * 16;
            return new int[]{rx * bs + bs / 2, rz * bs + bs / 2};
        } catch (NumberFormatException e) {
            return new int[]{0, 0};
        }
    }

    private record RegionSample(long delayNanos, long wallNanos, long threadId, long cpuNanos) {}

    private record ThreadCpuSnapshot(long wallNanos, long cpuNanos) {}

    private static class ExceedState {
        long exceedStartMs = 0;
        long lastWarnMs = 0;
    }
}
