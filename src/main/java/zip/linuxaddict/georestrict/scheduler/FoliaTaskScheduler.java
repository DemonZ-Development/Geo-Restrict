/*
 * GeoRestrict - High-performance geographic access control.
 * Copyright (C) 2026 Demonz Development (https://demonz.org)
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
 * Folia scheduler adapter. Loaded via reflection (see GeoRestrictBukkitPlugin#createScheduler)
 * so this class is never touched on non-Folia servers, avoiding NoClassDefFoundError.
 */
public class FoliaTaskScheduler implements TaskScheduler {

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
        } catch (Exception e) {
            throw new RuntimeException("Folia cancelAll failed", e);
        }
    }
}
