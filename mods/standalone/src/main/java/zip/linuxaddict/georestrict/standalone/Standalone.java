package zip.linuxaddict.georestrict.standalone;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zip.linuxaddict.georestrict.*;

public final class Standalone implements AutoCloseable {
    private final Logger log = LoggerFactory.getLogger("GeoRestrict");
    private final Gson gson = new Gson();
    private final Path directory;
    private final GeoCache cache;
    private final GeoRestrictService service;
    private final ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor();
    private final ThreadPoolExecutor clients = new ThreadPoolExecutor(0, 128, 30, TimeUnit.SECONDS,
        new SynchronousQueue<>(), new ThreadPoolExecutor.AbortPolicy());
    private final java.util.Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private volatile long modified;
    private volatile boolean closed;
    private HttpServer http;
    private ServerSocket listener;

    private Standalone(Path directory) throws IOException {
        this.directory = directory;
        Files.createDirectories(directory);
        Path config = directory.resolve("config.yml");
        cache = new GeoCache(directory.resolve("geo_cache.json").toFile(), gson, log);
        cache.load();
        service = new GeoRestrictService(ConfigLoader.load(config.toFile()), log, cache);
        modified = config.toFile().lastModified();
        maintenance.scheduleAtFixedRate(() -> {
            try {
                if (config.toFile().lastModified() != modified) {
                    service.setConfig(ConfigLoader.load(config.toFile()));
                    modified = config.toFile().lastModified();
                    log.info("GeoRestrict config changed, reloaded.");
                }
                cache.purgeExpired(service.getConfig().cacheTtlDays);
            } catch (RuntimeException error) { log.error("Config/cache maintenance failed", error); }
        }, 2, 2, TimeUnit.SECONDS);
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length == 0 || (!arguments[0].equals("admission") && !arguments[0].equals("gateway"))) {
            System.out.println("GeoRestrict " + PluginInfo.VERSION + "\n"
                + "admission [config-directory] [port=27651]\n"
                + "gateway [config-directory] [listen-port=25565] [backend-host=127.0.0.1] [backend-port=25566]\n"
                + "Gateway bind address: -Dgeorestrict.bind=127.0.0.1 (set explicitly for public access).");
            return;
        }
        Standalone runtime = new Standalone(Path.of(arguments.length > 1 ? arguments[1] : "config/georestrict"));
        Runtime.getRuntime().addShutdownHook(new Thread(runtime::close, "GeoRestrict-Shutdown"));
        if (arguments[0].equals("admission")) runtime.admission(arguments.length > 2 ? Integer.parseInt(arguments[2]) : 27651);
        else runtime.gateway(arguments.length > 2 ? Integer.parseInt(arguments[2]) : 25565,
            arguments.length > 3 ? arguments[3] : "127.0.0.1", arguments.length > 4 ? Integer.parseInt(arguments[4]) : 25566);
    }

    private GeoRestrictService.CheckResult check(String ip, String name, UUID uuid, boolean bypass) {
        return service.checkIp(ip, name, uuid, bypass).orTimeout(Math.min(25_000L,
            Math.max(5_000L, service.getConfig().connectionTimeoutMs * 2L + 2_000L)), TimeUnit.MILLISECONDS).join();
    }

    private void admission(int port) throws IOException {
        Path tokenFile = directory.resolve("admission.token");
        if (!Files.exists(tokenFile)) {
            byte[] random = new byte[32];new SecureRandom().nextBytes(random);
            Files.writeString(tokenFile, Base64.getUrlEncoder().withoutPadding().encodeToString(random), StandardOpenOption.CREATE_NEW);
            try { Files.setPosixFilePermissions(tokenFile, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); }
            catch (UnsupportedOperationException ignored) {}
        }
        byte[] token = Files.readString(tokenFile).trim().getBytes(StandardCharsets.UTF_8);
        if (token.length < 24) throw new IllegalStateException("Admission token must contain at least 24 characters");
        http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 64);
        http.setExecutor(clients);
        http.createContext("/", exchange -> {
            try (exchange) {
                String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                byte[] supplied = authorization != null && authorization.startsWith("Bearer ")
                    ? authorization.substring(7).getBytes(StandardCharsets.UTF_8) : new byte[0];
                if (!MessageDigest.isEqual(token, supplied)) { respond(exchange, 401, "{\"error\":\"unauthorized\"}");return; }
                if (exchange.getRequestURI().getPath().equals("/health") && exchange.getRequestMethod().equals("GET")) {
                    respond(exchange, 200, "{\"ok\":true,\"version\":\"" + PluginInfo.VERSION + "\"}");return;
                }
                if (!exchange.getRequestURI().getPath().equals("/check") || !exchange.getRequestMethod().equals("POST")) {
                    respond(exchange, 404, "{\"error\":\"unknown_route\"}");return;
                }
                byte[] body = exchange.getRequestBody().readNBytes(4097);
                if (body.length > 4096) { respond(exchange, 413, "{\"error\":\"request_too_large\"}");return; }
                try {
                    JsonObject request = gson.fromJson(new String(body, StandardCharsets.UTF_8), JsonObject.class);
                    String ip = request.get("ip").getAsString();
                    if (!literal(ip)) { respond(exchange, 400, "{\"error\":\"invalid_ip\"}");return; }
                    String name = request.has("player") ? request.get("player").getAsString() : "Player";
                    UUID uuid = request.has("uuid") && !request.get("uuid").isJsonNull() ? UUID.fromString(request.get("uuid").getAsString()) : null;
                    boolean bypass = request.has("bypass") && request.get("bypass").getAsBoolean();
                    respond(exchange, 200, gson.toJson(check(ip, name, uuid, bypass)));
                } catch (IllegalArgumentException | NullPointerException error) {
                    respond(exchange, 400, "{\"error\":\"invalid_request\"}");
                } catch (RuntimeException error) {
                    JsonObject failure = new JsonObject();failure.addProperty("allowed", !service.getConfig().blockOnLookupFailure);
                    failure.addProperty("reason", service.getConfig().kickMessageLookupFailure);
                    respond(exchange, 200, gson.toJson(failure));
                }
            } catch (IOException error) { log.debug("Admission client disconnected", error); }
        });
        http.start();log.info("GeoRestrict admission bridge v{} listening on loopback:{}", PluginInfo.VERSION, port);
    }

    private static boolean literal(String ip) {
        if (ip == null || ip.isBlank() || ip.length() > 128) return false;
        if (ip.indexOf(':') >= 0) {
            if (!ip.matches("[0-9a-fA-F:.%a-zA-Z0-9_-]+")) return false;
            try { return InetAddress.getByName(ip) != null; } catch (UnknownHostException error) { return false; }
        }
        String[] parts = ip.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (!part.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(part) > 255) return false;
        }
        return true;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, data.length);
        exchange.getResponseBody().write(data);
    }

    private void gateway(int port, String backendHost, int backendPort) throws IOException {
        InetSocketAddress backend = new InetSocketAddress(backendHost, backendPort);
        listener = new ServerSocket();listener.bind(new InetSocketAddress(System.getProperty("georestrict.bind", "127.0.0.1"), port));
        log.info("GeoRestrict edge gateway v{} listening on {}; backend {}", PluginInfo.VERSION, listener.getLocalSocketAddress(), backend);
        while (!closed) {
            Socket socket = listener.accept();sockets.add(socket);
            try { clients.execute(() -> route(socket, backend)); }
            catch (RejectedExecutionException error) { sockets.remove(socket);socket.close(); }
        }
    }

    private void route(Socket socket, InetSocketAddress backend) {
        try (socket) {
            socket.setSoTimeout(10_000);
            InputStream input = socket.getInputStream();
            ByteArrayOutputStream initial = new ByteArrayOutputStream();
            int first = input.read();if (first < 0) return;initial.write(first);
            boolean legacyMode = Boolean.getBoolean("georestrict.legacy");
            if (legacyMode && first != 2 && first != 254) return;
            boolean legacy = legacyMode && first == 2;
            boolean login = legacy;
            if (!(legacyMode && first == 254) && !legacy) {
                int size = varint(input, initial, first);
                if (size < 1 || size > 8192) return;
                byte[] packet = input.readNBytes(size);if (packet.length != size) return;initial.write(packet);
                DataInputStream handshake = new DataInputStream(new ByteArrayInputStream(packet));
                if (varint(handshake, null, handshake.readUnsignedByte()) != 0) return;
                varint(handshake, null, handshake.readUnsignedByte());
                int hostLength = varint(handshake, null, handshake.readUnsignedByte());
                if (hostLength < 0 || hostLength > 1024 || handshake.skipBytes(hostLength) != hostLength) return;
                handshake.readUnsignedShort();
                int state = varint(handshake, null, handshake.readUnsignedByte());
                if (state != 1 && state != 2) return;
                login = state == 2;
            }
            if (login) {
                GeoRestrictService.CheckResult result;
                try { result = check(socket.getInetAddress().getHostAddress(), "Connection", null, false); }
                catch (RuntimeException error) {
                    if (!service.getConfig().blockOnLookupFailure) result = new GeoRestrictService.CheckResult(true, null, null);
                    else result = new GeoRestrictService.CheckResult(false, service.getConfig().kickMessageLookupFailure, null);
                }
                if (!result.allowed) { deny(socket.getOutputStream(), result.reason, legacy);return; }
            }
            try (Socket downstream = new Socket()) {
                sockets.add(downstream);downstream.connect(backend, 5000);
                socket.setSoTimeout(0);socket.setTcpNoDelay(true);downstream.setTcpNoDelay(true);
                downstream.getOutputStream().write(initial.toByteArray());
                Future<?> reverse = clients.submit(() -> copy(downstream, socket));
                copy(socket, downstream);reverse.cancel(true);sockets.remove(downstream);
            }
        } catch (IOException | RuntimeException error) { log.debug("Gateway connection ended", error); }
        finally { sockets.remove(socket); }
    }

    private static void copy(Socket source, Socket destination) {
        try { source.getInputStream().transferTo(destination.getOutputStream()); }
        catch (IOException ignored) {}
        finally { try { source.close();destination.close(); } catch (IOException ignored) {} }
    }

    private static int varint(InputStream stream, ByteArrayOutputStream capture, int first) throws IOException {
        int value = first & 127, shift = 7, current = first;
        while ((current & 128) != 0) {
            if (shift >= 35) throw new IOException("Oversized varint");
            current = stream.read();if (current < 0) throw new EOFException();
            if (capture != null) capture.write(current);
            value |= (current & 127) << shift;shift += 7;
        }
        return value;
    }

    private static void varint(OutputStream stream, int value) throws IOException {
        while ((value & ~127) != 0) { stream.write((value & 127) | 128);value >>>= 7; }
        stream.write(value);
    }

    private void deny(OutputStream output, String reason, boolean legacy) throws IOException {
        String message = reason == null ? "Connection denied." : reason;
        if (legacy) {
            DataOutputStream data = new DataOutputStream(output);data.writeByte(255);
            if (message.length() > 256) message = message.substring(0, 256);
            data.writeShort(message.length());data.writeChars(message);
        } else {
            JsonObject text = new JsonObject();text.addProperty("text", message);
            byte[] bytes = gson.toJson(text).getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream packet = new ByteArrayOutputStream();varint(packet, 0);varint(packet, bytes.length);packet.write(bytes);
            varint(output, packet.size());packet.writeTo(output);
        }
        output.flush();
    }

    @Override public void close() {
        if (closed) return;closed = true;
        if (http != null) http.stop(0);
        if (listener != null) try { listener.close(); } catch (IOException ignored) {}
        for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) {}
        clients.shutdownNow();maintenance.shutdownNow();service.shutdown();cache.shutdown();
    }
}
