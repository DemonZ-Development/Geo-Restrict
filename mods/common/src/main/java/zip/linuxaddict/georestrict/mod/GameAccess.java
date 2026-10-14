package zip.linuxaddict.georestrict.mod;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.Predicate;

public final class GameAccess {
    private static final ClassValue<List<Field>> FIELDS = new ClassValue<>() {
        @Override protected List<Field> computeValue(Class<?> type) {
            List<Field> fields = new ArrayList<>();
            for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
                for (Field field : owner.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers())) fields.add(field);
                }
            }
            return List.copyOf(fields);
        }
    };

    private GameAccess() {}

    public static Object server(Object listener) {
        return fieldValue(listener, "net.minecraft.server.MinecraftServer");
    }

    public static boolean isDedicatedServer(Object server) {
        return (boolean) named(server, List.of("isDedicatedServer", "m_6982_", "method_3816"));
    }

    public static Object connection(Object listener) {
        return fieldValue(listener, "net.minecraft.network.Connection", "net.minecraft.class_2535");
    }

    public static SocketAddress remoteAddress(Object connection) {
        return (SocketAddress) fieldValue(connection, "java.net.SocketAddress");
    }

    public static Object profile(Object listener) {
        return fieldValue(listener, "com.mojang.authlib.GameProfile");
    }

    public static String state(Object listener) {
        try {
            for (Field field : FIELDS.get(listener.getClass())) {
                if (!field.getType().isEnum()) continue;
                boolean loginState = false;
                for (Object constant : field.getType().getEnumConstants()) {
                    if (((Enum<?>) constant).name().equals("HELLO")) loginState = true;
                }
                if (loginState) {
                    field.setAccessible(true);
                    return ((Enum<?>) field.get(listener)).name();
                }
            }
        } catch (ReflectiveOperationException error) {
            throw failure("login state", error);
        }
        throw failure("login state", null);
    }

    public static String playerName(Object profile) {
        return (String) named(profile, List.of("getName", "name"));
    }

    public static UUID playerId(Object profile) {
        UUID id = (UUID) named(profile, List.of("getId", "id"));
        return id != null ? id : UUID.nameUUIDFromBytes(
            ("OfflinePlayer:" + playerName(profile)).getBytes(StandardCharsets.UTF_8));
    }

    private static Object profileArgument(Class<?> expected, Object profile) {
        if (expected.isInstance(profile)) {
            if (named(profile, List.of("getId", "id")) != null) return profile;
        }
        try {
            for (Constructor<?> constructor : expected.getConstructors()) {
                Class<?>[] types = constructor.getParameterTypes();
                if (types.length != 2) continue;
                if (types[0] == UUID.class && types[1] == String.class) {
                    return constructor.newInstance(playerId(profile), playerName(profile));
                }
                if (types[0] == String.class && types[1] == UUID.class) {
                    return constructor.newInstance(playerName(profile), playerId(profile));
                }
            }
        } catch (ReflectiveOperationException error) {
            throw failure("player identity", error);
        }
        throw failure("player identity " + expected.getName(), null);
    }

    public static Object playerList(Object server) {
        return getter(server, "net.minecraft.server.players.PlayerList", "net.minecraft.class_3324");
    }

    public static boolean isOperator(Object server, Object profile) {
        Object players = playerList(server);
        for (String name : List.of("isOp", "m_11303_", "method_14569")) {
            for (Method method : players.getClass().getMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == 1
                    && method.getReturnType() == boolean.class) {
                    return (boolean) invoke(method, players, profileArgument(method.getParameterTypes()[0], profile));
                }
            }
        }
        throw failure("operator lookup", null);
    }

    public static Object existingDenial(Object server, SocketAddress address, Object profile) {
        Object players = playerList(server);
        for (Method method : players.getClass().getMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (types.length == 2 && types[0] == SocketAddress.class && isComponent(method.getReturnType())) {
                return invoke(method, players, address, profileArgument(types[1], profile));
            }
        }
        throw failure("vanilla admission lookup", null);
    }

    public static void disconnect(Object listener, String reason) {
        Object component = component(reason);
        for (String name : List.of("disconnect", "m_10053_", "method_14380")) {
            for (Method method : listener.getClass().getMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isInstance(component)) {
                    invoke(method, listener, component);
                    return;
                }
            }
        }
        throw failure("login disconnect", null);
    }

    public static Object commandDispatcher(Object server) {
        Object commands = getter(server, "net.minecraft.commands.Commands", "net.minecraft.class_2170");
        return getter(commands, "com.mojang.brigadier.CommandDispatcher");
    }

    @SuppressWarnings("unchecked")
    public static boolean isAdmin(Object server, Object source) {
        for (Method method : source.getClass().getMethods()) {
            if (List.of("hasPermission", "m_6761_", "method_9259").contains(method.getName())
                && method.getReturnType() == boolean.class && method.getParameterCount() == 1
                && method.getParameterTypes()[0] == int.class) {
                return (boolean) invoke(method, source, 2);
            }
        }
        Object commands = getter(server, "net.minecraft.commands.Commands", "net.minecraft.class_2170");
        for (String fieldName : List.of("LEVEL_GAMEMASTERS", "field_31839")) {
            try {
                Field field = commands.getClass().getField(fieldName);
                Object requirement = field.get(null);
                for (Method method : commands.getClass().getMethods()) {
                    if (Modifier.isStatic(method.getModifiers()) && method.getParameterCount() == 1
                        && method.getParameterTypes()[0] == field.getType()
                        && Predicate.class.isAssignableFrom(method.getReturnType())) {
                        return ((Predicate<Object>) invoke(method, null, requirement)).test(source);
                    }
                }
            } catch (NoSuchFieldException ignored) {
            } catch (ReflectiveOperationException error) {
                throw failure("native command permissions", error);
            }
        }
        throw failure("native command permissions", null);
    }

    public static String onlinePlayerIp(Object server, String name) {
        Object players = playerList(server);
        for (Method method : players.getClass().getMethods()) {
            if (method.getParameterCount() == 1 && method.getParameterTypes()[0] == String.class
                && typeName(method.getReturnType(), "net.minecraft.server.level.ServerPlayer", "net.minecraft.class_3222")) {
                Object player = invoke(method, players, name);
                if (player == null) return null;
                Object handler = fieldValue(player, "net.minecraft.server.network.ServerGamePacketListenerImpl", "net.minecraft.class_3244");
                SocketAddress address = remoteAddress(connection(handler));
                return address instanceof java.net.InetSocketAddress inet ? inet.getAddress().getHostAddress() : null;
            }
        }
        throw failure("online player lookup", null);
    }

    public static void sendMessage(Object source, String text) {
        Object component = component(text.replaceAll("\u00a7[0-9a-fk-orA-FK-OR]", ""));
        for (Method method : source.getClass().getMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (types.length == 2 && types[0] == Supplier.class && types[1] == boolean.class) {
                invoke(method, source, (Supplier<Object>) () -> component, false);
                return;
            }
            if (types.length == 2 && types[0].isInstance(component) && types[1] == boolean.class) {
                invoke(method, source, component, false);
                return;
            }
        }
        throw failure("command response", null);
    }

    public static Object component(String text) {
        try {
            Class<?> type;
            try { type = Class.forName("net.minecraft.network.chat.Component"); }
            catch (ClassNotFoundException ignored) { type = Class.forName("net.minecraft.class_2561"); }
            for (String name : List.of("literal", "m_237113_", "method_43470")) {
                for (Method method : type.getMethods()) {
                    if (method.getName().equals(name) && Modifier.isStatic(method.getModifiers())
                        && method.getParameterCount() == 1 && method.getParameterTypes()[0] == String.class) {
                        return invoke(method, null, text);
                    }
                }
            }
        } catch (ClassNotFoundException error) {
            throw failure("text component", error);
        }
        throw failure("literal text", null);
    }

    private static boolean isComponent(Class<?> type) {
        return typeName(type, "net.minecraft.network.chat.Component", "net.minecraft.class_2561");
    }

    private static boolean typeName(Class<?> type, String... names) {
        for (String name : names) if (type.getName().equals(name)) return true;
        return false;
    }

    private static Object fieldValue(Object target, String... names) {
        try {
            Field found = null;
            for (Field field : FIELDS.get(target.getClass())) {
                if (typeName(field.getType(), names)) {
                    if (found != null) throw failure("ambiguous field " + String.join(",", names), null);
                    found = field;
                }
            }
            if (found != null) { found.setAccessible(true); return found.get(target); }
        } catch (ReflectiveOperationException error) {
            throw failure("field " + String.join(",", names), error);
        }
        throw failure("field " + String.join(",", names), null);
    }

    private static Object getter(Object target, String... resultTypes) {
        Method found = null;
        for (Method method : target.getClass().getMethods()) {
            if (method.getParameterCount() == 0 && typeName(method.getReturnType(), resultTypes)) {
                if (found != null && !found.getName().equals(method.getName())) {
                    throw failure("ambiguous getter " + String.join(",", resultTypes), null);
                }
                found = method;
            }
        }
        if (found != null) return invoke(found, target);
        throw failure("getter " + String.join(",", resultTypes), null);
    }

    private static Object named(Object target, List<String> names) {
        for (String name : names) {
            try { return invoke(target.getClass().getMethod(name), target); }
            catch (NoSuchMethodException ignored) {}
        }
        throw failure("method " + names, null);
    }

    private static Object invoke(Method method, Object target, Object... arguments) {
        try { return method.invoke(target, arguments); }
        catch (ReflectiveOperationException error) { throw failure(method.toString(), error); }
    }

    private static IllegalStateException failure(String operation, Throwable cause) {
        return new IllegalStateException("Unsupported Minecraft runtime operation: " + operation, cause);
    }
}
