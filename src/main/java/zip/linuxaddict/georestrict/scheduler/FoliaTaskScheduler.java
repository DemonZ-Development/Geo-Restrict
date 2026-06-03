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
 * Folia scheduler adapter using reflection.
 * All Folia API calls are done via reflection so this class can be loaded
 * on non-Folia servers without causing NoClassDefFoundError.
 * Uses Folia's AsyncScheduler with time-based scheduling in milliseconds.
 */
public class FoliaTaskScheduler implements TaskScheduler {

    @Override
    public void runAsync(Plugin plugin, Runnable task) {
        try {
            // Bukkit.getAsyncScheduler().runNow(plugin, scheduledTask -> task.run())
            Method getAsyncScheduler = Bukkit.class.getMethod("getAsyncScheduler");
            Object asyncScheduler = getAsyncScheduler.invoke(null);

            Consumer<Object> consumer = (scheduledTask) -> task.run();

            Method runNow = asyncScheduler.getClass().getMethod("runNow",
                    Plugin.class, Consumer.class);
            runNow.invoke(asyncScheduler, plugin, consumer);
        } catch (Exception e) {
            throw new RuntimeException("Failed to run async task via Folia scheduler", e);
        }
    }

    @Override
    public void runTimer(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
        // Folia doesn't have a sync global scheduler in the same way;
        // use async scheduler with tick-to-millisecond conversion (50ms per tick)
        runTimerAsync(plugin, task, delayTicks, periodTicks);
    }

    @Override
    public void runTimerAsync(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
        try {
            // Bukkit.getAsyncScheduler().runAtFixedRate(plugin, scheduledTask -> task.run(), delayMs, periodMs, TimeUnit.MILLISECONDS)
            Method getAsyncScheduler = Bukkit.class.getMethod("getAsyncScheduler");
            Object asyncScheduler = getAsyncScheduler.invoke(null);

            Consumer<Object> consumer = (scheduledTask) -> task.run();

            // Convert ticks to milliseconds (1 tick = 50ms)
            long delayMs = delayTicks * 50L;
            long periodMs = periodTicks * 50L;

            Method runAtFixedRate = asyncScheduler.getClass().getMethod("runAtFixedRate",
                    Plugin.class, Consumer.class, long.class, long.class, TimeUnit.class);
            runAtFixedRate.invoke(asyncScheduler, plugin, consumer, delayMs, periodMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Failed to run timer async task via Folia scheduler", e);
        }
    }

    @Override
    public void cancelAll(Plugin plugin) {
        try {
            // Bukkit.getAsyncScheduler().cancelTasks(plugin)
            Method getAsyncScheduler = Bukkit.class.getMethod("getAsyncScheduler");
            Object asyncScheduler = getAsyncScheduler.invoke(null);

            Method cancelTasks = asyncScheduler.getClass().getMethod("cancelTasks", Plugin.class);
            cancelTasks.invoke(asyncScheduler, plugin);
        } catch (Exception e) {
            throw new RuntimeException("Failed to cancel tasks via Folia scheduler", e);
        }
    }
}

