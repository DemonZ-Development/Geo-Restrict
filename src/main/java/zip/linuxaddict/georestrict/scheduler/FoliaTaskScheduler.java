/*
 * GeoRestrict - High-performance geographic access control.
 * Copyright (C) 2026 Demonz Development (https://demonzdevelopment.online)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package zip.linuxaddict.georestrict.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Folia scheduler adapter using reflection so the class can be loaded on
 * non-Folia servers without NoClassDefFoundError. All async work is routed
 * through Bukkit.getAsyncScheduler(); sync work that requires a region
 * uses Bukkit.getGlobalRegionScheduler().
 */
public class FoliaTaskScheduler implements TaskScheduler {

    @Override
    public void runAsync(Plugin plugin, Runnable task) {
        try {
            Object asyncScheduler = Bukkit.class.getMethod("getAsyncScheduler").invoke(null);
            Consumer<Object> consumer = scheduled -> task.run();
            Method runNow = asyncScheduler.getClass().getMethod("runNow", Plugin.class, Consumer.class);
            runNow.invoke(asyncScheduler, plugin, consumer);
        } catch (Exception e) {
            throw new RuntimeException("Folia async scheduling failed", e);
        }
    }

    @Override
    public void runTimer(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
        // Folia has no global sync timer; use the global region scheduler,
        // which always runs on a known region and is safe for sync work.
        try {
            Object globalRegionScheduler = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
            Method runAtFixedRate = globalRegionScheduler.getClass().getMethod("runAtFixedRate",
                Plugin.class, Consumer.class, long.class, longTicksAsLong());
            runAtFixedRate.invoke(globalRegionScheduler, plugin,
                (Consumer<Object>) scheduled -> task.run(),
                delayTicks < 1 ? 1 : delayTicks,
                periodTicks < 1 ? 1 : periodTicks);
        } catch (Exception e) {
            // Fall back to async if the region scheduler is unavailable.
            runTimerAsync(plugin, task, delayTicks, periodTicks);
        }
    }

    @Override
    public void runTimerAsync(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
        try {
            Object asyncScheduler = Bukkit.class.getMethod("getAsyncScheduler").invoke(null);
            Consumer<Object> consumer = scheduled -> task.run();
            long delayMs = Math.max(50L, delayTicks * 50L);
            long periodMs = Math.max(50L, periodTicks * 50L);
            Method runAtFixedRate = asyncScheduler.getClass().getMethod("runAtFixedRate",
                Plugin.class, Consumer.class, long.class, long.class, TimeUnit.class);
            runAtFixedRate.invoke(asyncScheduler, plugin, consumer, delayMs, periodMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Folia async timer scheduling failed", e);
        }
    }

    @Override
    public void cancelAll(Plugin plugin) {
        try {
            Object asyncScheduler = Bukkit.class.getMethod("getAsyncScheduler").invoke(null);
            asyncScheduler.getClass().getMethod("cancelTasks", Plugin.class).invoke(asyncScheduler, plugin);
            Object globalRegion = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
            try {
                globalRegion.getClass().getMethod("cancelTasks", Plugin.class).invoke(globalRegion, plugin);
            } catch (NoSuchMethodException ignored) {}
        } catch (Exception e) {
            throw new RuntimeException("Folia cancelAll failed", e);
        }
    }

    /** Reflection helper to bridge `long` parameter types across runAtFixedRate overloads. */
    private static Class<?> longTicksAsLong() {
        return long.class;
    }
}
