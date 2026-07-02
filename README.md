# Warn

A Folia/Paper region-aware performance warning plugin. Monitors MSPT, CPU, and RAM per region and warns players when thresholds are exceeded.

## Features

- **Per-region MSPT monitoring** — warns only players in the affected region
- **Per-region CPU monitoring** — measures actual thread CPU time per Folia region
- **Server-wide RAM monitoring** — warns all players
- **OP notifications** — operators receive all warnings plus region coordinates
- **Configurable thresholds and durations** — all via `config.yml`
- **Alarm sound** — plays a configurable sound to warned players
- **Reloadable** — `/warn reload` to apply config changes without restart

## Requirements

- **Folia** or **Paper** 1.21.4+ (Paper 26.1+)
- Java 21+

## Installation

1. Download `Warn-1.0-SNAPSHOT.jar` from [Releases](https://github.com/your/repo/releases) (No releases yet, you may build on yourself)
2. Place it in your server's `plugins/` folder
3. Restart the server (or load with a plugin manager)
4. Edit `plugins/Warn/config.yml` to adjust thresholds

## Configuration

```yaml
# MSPT monitoring (milliseconds per tick)
mspt:
  enabled: true
  threshold: 45.0       # warn when MSPT exceeds this
  duration: 10          # must exceed threshold for this many seconds

# CPU monitoring (percentage 0-100+)
cpu:
  enabled: true
  threshold: 90.0
  duration: 10

# RAM monitoring (percentage 0-100)
ram:
  enabled: true
  threshold: 90.0
  duration: 10

# General
check-interval: 1        # seconds between checks
warning-cooldown: 15     # seconds between warnings (anti-spam)

# Alarm sound
alarm:
  enabled: true
  sound: "BLOCK_NOTE_BLOCK_PLING"
  volume: 1.0
  pitch: 1.0
  duration: 3            # seconds to repeat
  interval: 2            # ticks between plays (2 = 10/sec)

# Messages (MiniMessage format)
messages:
  mspt-warning: "<red>⚠ MSPT is high (<mspt>ms) in your region!</red>"
  mspt-warning-op: "<red>⚠ MSPT is high (<mspt>ms) in region <region> (~<x> ~<z>)!</red>"
  cpu-warning: "<red>⚠ CPU usage is high (<cpu>%) in your region!</red>"
  cpu-warning-op: "<red>⚠ CPU usage is high (<cpu>%) in region <region> (~<x> ~<z>)!</red>"
  ram-warning: "<red>⚠ Server RAM usage is high (<ram>%)!</red>"
```

## Commands

| Command | Permission | Description |
|---|---|---|
| `/warn reload` | `warn.admin` | Reload config.yml |

## Permissions

| Permission | Default | Description |
|---|---|---|
| `warn.admin` | op | Allows `/warn reload` |
| `warn.notify` | op | Receive all warnings + region coordinates (OP-level notifications) |

## How it works

### MSPT

Every check interval, a task is scheduled on each player's Folia region via `RegionScheduler.execute()`. The scheduling delay approximates the region's MSPT. Players are grouped by region, and if a region exceeds the MSPT threshold for the configured duration, only players in that region receive a warning.

Operators receive **all** MSPT warnings regardless of region, with the lagging region's approximate block coordinates.

### CPU

CPU utilization is measured independently from MSPT using `ThreadMXBean.getCurrentThreadCpuTime()`. Each region's tick thread records its cumulative CPU time. The delta between consecutive measurements divided by wall-clock time gives actual per-thread CPU usage — not derived from MSPT.

### RAM

Standard JVM heap monitoring: `(totalMemory - freeMemory) / maxMemory × 100`. Warnings go to all players since RAM is shared.

### Warning flow

1. Metric exceeds threshold → timer starts
2. Metric stays above threshold for `duration` seconds → warning triggers
3. Warning cooldown prevents spam for `warning-cooldown` seconds
4. Alarm sound plays to each warned player for the configured duration

## Building

```bash
./gradlew clean jar
```

Output: `build/libs/Warn-1.0-SNAPSHOT.jar`

## License

MIT
