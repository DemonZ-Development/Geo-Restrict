/*
 * GeoRestrict - High-performance geographic access control.
 * Copyright (C) 2026 Demonz Development (https://demonzdevelopment.online)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package zip.linuxaddict.georestrict;

import java.io.File;
import java.util.function.BiConsumer;

public final class CommandHandler {

    public interface Sender {
        boolean hasPermission(String permission);
        void sendMessage(String legacyMessage);
    }

    public interface IpResolver {
        String resolveOnlinePlayerIp(String playerName);
    }

    private final GeoRestrictService service;
    private final GeoCache cache;
    private final File configFile;
    private final BiConsumer<GeoConfig, Runnable> configReloader;

    public CommandHandler(GeoRestrictService service,
                          GeoCache cache,
                          File configFile,
                          BiConsumer<GeoConfig, Runnable> configReloader) {
        this.service = service;
        this.cache = cache;
        this.configFile = configFile;
        this.configReloader = configReloader;
    }

    public boolean execute(Sender sender, IpResolver resolver, String[] args) {
        if (!sender.hasPermission("georestrict.admin")) {
            sender.sendMessage(legacy("&cYou do not have permission to use this command."));
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(legacy("&b&lGeoRestrict &7v" + PluginInfo.VERSION));
            sender.sendMessage(legacy("&7By &bDemonz Development"));
            sender.sendMessage(legacy("&7https://demonzdevelopment.online/"));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "check":
                handleCheck(sender, resolver, args);
                break;
            case "purgecache":
                cache.purgeAll();
                sender.sendMessage(legacy("&aGeoRestrict cache purged successfully."));
                break;
            case "cachestats":
                handleCacheStats(sender);
                break;
            case "reload":
                reloadConfig(sender);
                break;
            default:
                sender.sendMessage(legacy("&cUsage: /georestrict <check|purgecache|cachestats|reload>"));
                break;
        }
        return true;
    }

    private void handleCheck(Sender sender, IpResolver resolver, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(legacy("&cUsage: /georestrict check <ip|player>"));
            return;
        }

        String target = args[1];
        String ip = resolver.resolveOnlinePlayerIp(target);
        final String ipToCheck = (ip == null || ip.isEmpty()) ? target : ip;

        sender.sendMessage(legacy("&7Checking IP: " + ipToCheck + "..."));
        service.checkIp(ipToCheck, target).whenComplete((result, error) -> {
            if (error != null) {
                sender.sendMessage(legacy("&cLookup failed: " + error.getMessage()));
                return;
            }
            sender.sendMessage(legacy("&7Result for " + ipToCheck + ":"));
            sender.sendMessage(legacy("&7Allowed: " + (result.allowed ? "&aYes" : "&cNo")));
            if (!result.allowed && result.reason != null) {
                sender.sendMessage(legacy("&7Reason: " + result.reason));
            }
            if (result.info != null) {
                sender.sendMessage(legacy("&7Country: " + nullSafe(result.info.countryCode)));
                sender.sendMessage(legacy("&7ASN: " + nullSafe(result.info.asn)));
                sender.sendMessage(legacy("&7ISP: " + nullSafe(result.info.asName)));
            }
        });
    }

    private void handleCacheStats(Sender sender) {
        GeoCache.CacheStats stats = cache.getStats();
        sender.sendMessage(legacy("&b&lGeoRestrict Cache Stats"));
        sender.sendMessage(legacy("&7Entries: &f" + stats.entryCount));
        sender.sendMessage(legacy("&7File size: &f" + String.format("%.2f", stats.fileSizeBytes / 1024.0) + " KB"));
        if (stats.oldestEntryTimestamp > 0) {
            long ageMs = System.currentTimeMillis() - stats.oldestEntryTimestamp;
            long ageDays = ageMs / 1000L / 60 / 60 / 24;
            long ageHours = ageMs / 1000L / 60 / 60 % 24;
            sender.sendMessage(legacy("&7Oldest entry: &f" + ageDays + "d " + ageHours + "h ago"));
        } else {
            sender.sendMessage(legacy("&7Oldest entry: &fN/A"));
        }
    }

    private void reloadConfig(Sender sender) {
        GeoConfig fresh = ConfigLoader.load(configFile);
        configReloader.accept(fresh, () -> sender.sendMessage(legacy("&aGeoRestrict config reloaded.")));
    }

    private static String nullSafe(String s) {
        return s == null || s.isEmpty() ? "Unknown" : s;
    }

    public static String legacy(String s) {
        return s.replace('&', '\u00A7');
    }
}
