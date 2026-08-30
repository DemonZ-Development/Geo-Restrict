/*
 * GeoRestrict - High-performance geographic access control.
 * Copyright (C) 2026 Demonz Development (https://demonz.org)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package zip.linuxaddict.georestrict.it;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public final class MockGateway implements AutoCloseable {

    private final HttpServer server;
    private final Map<String, String> responses = new HashMap<>();
    private final Gson gson = new Gson();

    public MockGateway() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            String ip = (query != null && query.startsWith("ip=")) ? query.substring(3) : "";
            String body = responses.getOrDefault(ip, "{\"error\":\"invalid ip\"}");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            int code = responses.containsKey(ip) ? 200 : 400;
            exchange.sendResponseHeaders(code, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
            exchange.close();
        });
        server.start();
    }

    public MockGateway with(String ip, String countryCode, String asn, String asName,
                            boolean hosting, boolean vpn, boolean proxy) {
        Map<String, Object> m = new HashMap<>();
        m.put("ip", ip);
        m.put("countryCode", countryCode);
        m.put("countryName", countryCode);
        m.put("asn", asn);
        m.put("asName", asName);
        m.put("isVpn", vpn);
        m.put("isHosting", hosting);
        m.put("isProxy", proxy);
        m.put("isMobile", false);
        responses.put(ip, gson.toJson(m));
        return this;
    }

    public MockGateway withRaw(String ip, String body) {
        responses.put(ip, body);
        return this;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
