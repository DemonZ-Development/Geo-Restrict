package georestrict.live;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.Executor;
import zip.linuxaddict.georestrict.mod.GameAccess;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

public final class Fixture {
    public static void forward(Object listener, Object packet) throws Exception {
        String host = null;
        for (Field f: packet.getClass().getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) && f.getType() == String.class) {
                f.setAccessible(true);
                String candidate = (String) f.get(packet);
                if (candidate != null && candidate.startsWith("georestrict-live.invalid|")) host = candidate;
            }
        }
        if (host == null) return;
        String ip = host.substring("georestrict-live.invalid|".length());
        if (!ip.matches("[0-9a-fA-F:.]+")) throw new IllegalArgumentException("Fixture requires literal IP");
        Object connection = GameAccess.connection(listener);
        InetSocketAddress real = (InetSocketAddress) GameAccess.remoteAddress(connection);
        if (!real.getAddress().isLoopbackAddress()) throw new IllegalStateException("Fixture accepts only isolated loopback clients");
        for (Class<?> type=connection.getClass(); type!=null; type=type.getSuperclass()) {
            for (Field f: type.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) && f.getType() == SocketAddress.class) {
                    f.setAccessible(true); f.set(connection,new InetSocketAddress(InetAddress.getByName(ip),real.getPort()));
                    return;
                }
            }
        }
        throw new IllegalStateException("Fixture cannot find remote address");
    }
    @SuppressWarnings("unchecked")
    public static void register(Object server) {
        ((Executor)server).execute(() -> {
            CommandDispatcher<Object> dispatcher=(CommandDispatcher<Object>)GameAccess.commandDispatcher(server);
            dispatcher.register(LiteralArgumentBuilder.<Object>literal("geoprobe").executes(ctx -> {
                Probe.run("native-mod",zip.linuxaddict.georestrict.api.GeoRestrictAPI.isAvailable()); return 1;
            }));
            dispatcher.register(LiteralArgumentBuilder.<Object>literal("geoadminprobe").executes(ctx -> {
                try { permissionProbe(server,dispatcher); }
                catch (Exception error) { throw new IllegalStateException("Native permission probe failed",error); }
                return 1;
            }));
        });
    }

    private static Object permissionConstant(Class<?> type,String named,String intermediary) throws Exception {
        try { return type.getField(named).get(null); }
        catch (NoSuchFieldException ignored) { return type.getField(intermediary).get(null); }
    }

    private static void permissionProbe(Object server,CommandDispatcher<Object> dispatcher) throws Exception {
        Object source=null;
        for (Method method:server.getClass().getMethods()) {
            String type=method.getReturnType().getName();
            if (method.getParameterCount()==0 && (type.equals("net.minecraft.commands.CommandSourceStack")
                || type.equals("net.minecraft.class_2168"))) source=method.invoke(server);
        }
        if (source==null) throw new IllegalStateException("Missing native command source");
        Method with=null;
        for (Method method:source.getClass().getMethods()) {
            if (java.util.List.of("withPermission","m_81325_","method_9206").contains(method.getName())
                && method.getParameterCount()==1) with=method;
        }
        if (with==null) throw new IllegalStateException("Missing native permission conversion");
        Class<?> type=with.getParameterTypes()[0];
        Object low=with.invoke(source,type==int.class ? 0 : permissionConstant(type,"NO_PERMISSIONS","field_63207"));
        Object high=with.invoke(source,type==int.class ? 2 : permissionConstant(type,"ALL_PERMISSIONS","field_63208"));
        boolean denied=false;
        try {dispatcher.execute("georestrict",low);}
        catch (CommandSyntaxException expected) {denied=true;}
        java.util.Map<String,Object> result=new java.util.LinkedHashMap<>();
        result.put("lowAdmin",GameAccess.isAdmin(server,low));
        result.put("highAdmin",GameAccess.isAdmin(server,high));
        result.put("lowCommandDenied",denied);
        result.put("highCommandExecuted",dispatcher.execute("georestrict",high)==1);
        System.out.println("GEO_LIVE_PERMISSIONS "+new com.google.gson.Gson().toJson(result));
    }
}
