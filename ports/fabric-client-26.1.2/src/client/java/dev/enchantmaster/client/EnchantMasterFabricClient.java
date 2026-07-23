package dev.enchantmaster.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.nio.charset.StandardCharsets;

/**
 * Optional Fabric client for Paper Enchant Master servers.
 * Payload IDs match Bukkit plugin channels ({@code enchantmaster:*}).
 */
public final class EnchantMasterFabricClient implements ClientModInitializer {
    public static final Identifier HELLO = Identifier.parse("enchantmaster:hello");
    public static final Identifier OPEN = Identifier.parse("enchantmaster:open");
    public static final Identifier FORGE = Identifier.parse("enchantmaster:forge");
    public static final Identifier FORGE_RESULT = Identifier.parse("enchantmaster:forge_result");

    @Override
    public void onInitializeClient() {
        PayloadTypeRegistry.serverboundPlay().register(HelloPayload.TYPE, HelloPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ForgePayload.TYPE, ForgePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(OpenPayload.TYPE, OpenPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ForgeResultPayload.TYPE, ForgeResultPayload.CODEC);

        ClientPlayNetworking.registerGlobalReceiver(OpenPayload.TYPE, (payload, context) ->
                context.client().execute(() ->
                        context.client().setScreen(new ForgeWizardScreen(payload.canForgeForOthers()))));

        ClientPlayNetworking.registerGlobalReceiver(ForgeResultPayload.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    if (context.client().screen instanceof ForgeWizardScreen screen) {
                        screen.onResult(payload.ok(), payload.message());
                    }
                }));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                client.execute(EnchantMasterFabricClient::sendHello));
    }

    public static void sendHello() {
        ClientPlayNetworking.send(new HelloPayload("1.0.5"));
    }

    public static void sendForge(String json) {
        ClientPlayNetworking.send(new ForgePayload(json));
    }

    /** Raw UTF-8 body payloads (Paper PluginMessageListener compatible). */
    public record HelloPayload(String version) implements CustomPacketPayload {
        public static final Type<HelloPayload> TYPE = new Type<>(HELLO);
        public static final StreamCodec<FriendlyByteBuf, HelloPayload> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeBytes(p.version.getBytes(StandardCharsets.UTF_8)),
                buf -> {
                    byte[] data = new byte[buf.readableBytes()];
                    buf.readBytes(data);
                    return new HelloPayload(new String(data, StandardCharsets.UTF_8));
                }
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record OpenPayload(boolean canForgeForOthers) implements CustomPacketPayload {
        public static final Type<OpenPayload> TYPE = new Type<>(OPEN);
        public static final StreamCodec<FriendlyByteBuf, OpenPayload> CODEC = StreamCodec.of(
                (buf, p) -> {
                    String json = "{\"canForgeForOthers\":" + p.canForgeForOthers + "}";
                    buf.writeBytes(json.getBytes(StandardCharsets.UTF_8));
                },
                buf -> {
                    byte[] data = new byte[buf.readableBytes()];
                    buf.readBytes(data);
                    String json = new String(data, StandardCharsets.UTF_8);
                    boolean flag = json.contains("true");
                    return new OpenPayload(flag);
                }
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ForgePayload(String json) implements CustomPacketPayload {
        public static final Type<ForgePayload> TYPE = new Type<>(FORGE);
        public static final StreamCodec<FriendlyByteBuf, ForgePayload> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeBytes(p.json.getBytes(StandardCharsets.UTF_8)),
                buf -> {
                    byte[] data = new byte[buf.readableBytes()];
                    buf.readBytes(data);
                    return new ForgePayload(new String(data, StandardCharsets.UTF_8));
                }
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ForgeResultPayload(boolean ok, String message) implements CustomPacketPayload {
        public static final Type<ForgeResultPayload> TYPE = new Type<>(FORGE_RESULT);
        public static final StreamCodec<FriendlyByteBuf, ForgeResultPayload> CODEC = StreamCodec.of(
                (buf, p) -> {
                    String json = "{\"ok\":" + p.ok + ",\"message\":\"" +
                            (p.message == null ? "" : p.message.replace("\"", "\\\"")) + "\"}";
                    buf.writeBytes(json.getBytes(StandardCharsets.UTF_8));
                },
                buf -> {
                    byte[] data = new byte[buf.readableBytes()];
                    buf.readBytes(data);
                    String json = new String(data, StandardCharsets.UTF_8);
                    boolean ok = json.contains("\"ok\":true") || json.contains("\"ok\": true");
                    String msg = json;
                    int i = json.indexOf("\"message\"");
                    if (i >= 0) {
                        int q1 = json.indexOf('"', i + 10);
                        int q2 = json.indexOf('"', q1 + 1);
                        if (q1 >= 0 && q2 > q1) msg = json.substring(q1 + 1, q2);
                    }
                    return new ForgeResultPayload(ok, msg);
                }
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
