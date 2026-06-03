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

import org.bukkit.plugin.Plugin;

public interface TaskScheduler {
    void runAsync(Plugin plugin, Runnable task);
    void runTimer(Plugin plugin, Runnable task, long delayTicks, long periodTicks);
    void runTimerAsync(Plugin plugin, Runnable task, long delayTicks, long periodTicks);
    void cancelAll(Plugin plugin);
}

