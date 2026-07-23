package dev.enchantmaster.network;

import com.google.gson.JsonParser;
import dev.enchantmaster.EnchantMaster;
import dev.enchantmaster.audit.AuditActor;
import dev.enchantmaster.forge.ForgeRequest;
import dev.enchantmaster.forge.GiveService;
import dev.enchantmaster.util.PermissionHelper;
import dev.enchantmaster.web.WebAccessControl;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client → server: forge request as JSON (only from clients with the mod). */
public record ForgeRequestPayload(String json) implements CustomPacketPayload {
    public static final Type<ForgeRequestPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(EnchantMaster.MODID, "forge_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ForgeRequestPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8,
                    ForgeRequestPayload::json,
                    ForgeRequestPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleServer(ForgeRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!PermissionHelper.canUseInGameForge(player)) {
                reply(player, false, "You must be an operator to forge items in-game.");
                return;
            }

            try {
                ForgeRequest request = ForgeRequest.fromJson(JsonParser.parseString(payload.json()).getAsJsonObject());

                // If no target specified, default to the OP themselves
                if (request.targetPlayerUuid == null
                        && (request.targetPlayerName == null || request.targetPlayerName.isBlank())) {
                    request = new ForgeRequest(
                            request.itemId,
                            request.overrideLimits,
                            request.name,
                            request.lore,
                            request.enchantments,
                            request.attributes,
                            player.getUUID(),
                            player.getGameProfile().name(),
                            request.dryRun
                    );
                }

                var server = player.level().getServer();
                if (server == null) {
                    reply(player, false, "Server not available.");
                    return;
                }
                String ip = WebAccessControl.resolvePlayerIp(player);
                AuditActor actor = AuditActor.ingame(player, ip);
                GiveService.Result result = GiveService.forgeAndGive(server, request, actor);
                reply(player, result.success(), result.message());
                if (result.success()) {
                    player.sendSystemMessage(Component.literal(result.message()), false);
                }
            } catch (Exception e) {
                EnchantMaster.LOGGER.warn("Forge request failed for {}: {}", player.getGameProfile().name(), e.toString());
                reply(player, false, "Invalid forge request: " + e.getMessage());
            }
        });
    }

    private static void reply(ServerPlayer player, boolean success, String message) {
        // Prefer packet when client has the mod; always fall back to chat so OP still sees errors.
        boolean sent = ClientModSupport.sendIfSupported(player, new ForgeResultPayload(success, message));
        if (!sent) {
            player.sendSystemMessage(Component.literal("[Enchant Master] " + message), false);
        }
    }
}
