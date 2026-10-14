package zip.linuxaddict.georestrict.mod;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import java.util.concurrent.Executor;
import zip.linuxaddict.georestrict.CommandHandler;

public final class NativeCommands {
    private NativeCommands() {}

    @SuppressWarnings("unchecked")
    public static void register(Object server, ServerRuntime runtime, Object dispatcher) {
        CommandDispatcher<Object> nativeDispatcher = (CommandDispatcher<Object>) dispatcher;
        var root = nativeDispatcher.register(LiteralArgumentBuilder.<Object>literal("georestrict")
            .requires(runtime::canAdminister)
            .executes(context -> execute(server, runtime, context.getSource(), new String[0]))
            .then(RequiredArgumentBuilder.<Object, String>argument("arguments", StringArgumentType.greedyString())
                .executes(context -> execute(server, runtime, context.getSource(),
                    StringArgumentType.getString(context, "arguments").trim().split("\\s+")))));
        nativeDispatcher.register(LiteralArgumentBuilder.<Object>literal("gr")
            .requires(runtime::canAdminister)
            .executes(context -> execute(server, runtime, context.getSource(), new String[0]))
            .redirect(root));
    }

    private static int execute(Object server, ServerRuntime runtime, Object source, String[] arguments) {
        runtime.commands().execute(new CommandHandler.Sender() {
            @Override public boolean hasPermission(String permission) { return runtime.canAdminister(source); }
            @Override public void sendMessage(String message) {
                ((Executor) server).execute(() -> GameAccess.sendMessage(source, message));
            }
        }, name -> GameAccess.onlinePlayerIp(server, name), arguments);
        return 1;
    }
}
