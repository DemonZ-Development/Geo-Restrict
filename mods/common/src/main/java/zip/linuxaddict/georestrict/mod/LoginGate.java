package zip.linuxaddict.georestrict.mod;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import zip.linuxaddict.georestrict.GeoRestrictService;

public final class LoginGate {
    private CompletableFuture<GeoRestrictService.CheckResult> pending;
    private boolean finished;
    private boolean denied;

    public boolean poll(Object listener) {
        if (finished) return denied;
        String state = GameAccess.state(listener);
        if (!state.equals("VERIFYING") && !state.equals("READY_TO_ACCEPT")
            && !state.equals("NEGOTIATING")) return false;
        Object server = GameAccess.server(listener);
        if (!GameAccess.isDedicatedServer(server)) return false;
        ServerRuntime runtime = ServerRuntime.get(server);
        if (runtime == null) throw new IllegalStateException("GeoRestrict server runtime was not initialized");
        if (pending == null) {
            Object profile = GameAccess.profile(listener);
            SocketAddress address = GameAccess.remoteAddress(GameAccess.connection(listener));
            if (GameAccess.existingDenial(server, address, profile) != null) {
                finished = true;
                return false;
            }
            String ip = address instanceof InetSocketAddress inet && inet.getAddress() != null
                ? inet.getAddress().getHostAddress() : "";
            long deadline = Math.min(25_000L, Math.max(5_000L, runtime.config().connectionTimeoutMs * 2L + 2_000L));
            pending = runtime.service().checkIp(ip, GameAccess.playerName(profile), GameAccess.playerId(profile),
                GameAccess.isOperator(server, profile)).orTimeout(deadline, TimeUnit.MILLISECONDS);
        }
        if (!pending.isDone()) return true;
        finished = true;
        String reason;
        try {
            GeoRestrictService.CheckResult result = pending.join();
            denied = !result.allowed;
            reason = result.reason;
        } catch (RuntimeException error) {
            denied = runtime.config().blockOnLookupFailure;
            reason = runtime.config().kickMessageLookupFailure;
            runtime.logger().warn("Login lookup failed: {}", error.toString());
        }
        if (denied) GameAccess.disconnect(listener, reason == null ? "Connection denied." : reason);
        return denied;
    }
}
