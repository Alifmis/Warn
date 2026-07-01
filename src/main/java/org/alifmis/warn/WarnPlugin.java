package org.alifmis.warn;

import com.mojang.brigadier.Command;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.plugin.java.JavaPlugin;

public final class WarnPlugin extends JavaPlugin {

    private WarnConfig config;
    private WarnMonitor monitor;
    private MiniMessage miniMessage;

    @Override
    public void onEnable() {
        miniMessage = MiniMessage.miniMessage();

        saveDefaultConfig();
        config = new WarnConfig(this);

        monitor = new WarnMonitor(this, config);
        monitor.start();

        registerCommands();

        getLogger().info("Warn plugin enabled!");
    }

    @Override
    public void onDisable() {
        if (monitor != null) {
            monitor.stop();
        }
        getLogger().info("Warn plugin disabled!");
    }

    public void reload() {
        reloadConfig();
        config = new WarnConfig(this);
        monitor.stop();
        monitor = new WarnMonitor(this, config);
        monitor.start();
        getLogger().info("Configuration reloaded!");
    }

    public MiniMessage getMiniMessage() {
        return miniMessage;
    }

    private void registerCommands() {
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            var registrar = event.registrar();
            registrar.register(
                Commands.literal("warn")
                    .requires(src -> src.getSender().hasPermission("warn.admin"))
                    .then(Commands.literal("reload")
                        .executes(ctx -> {
                            reload();
                            ctx.getSource().getSender().sendMessage(
                                Component.text("Warn configuration reloaded!")
                            );
                            return Command.SINGLE_SUCCESS;
                        })
                    )
                    .build()
            );
        });
    }
}
