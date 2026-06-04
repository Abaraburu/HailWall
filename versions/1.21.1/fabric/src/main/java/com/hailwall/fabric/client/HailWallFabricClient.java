package com.hailwall.fabric.client;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import com.hailwall.net.ModListCodec;
import com.hailwall.net.Protocol;
import com.hailwall.net.Signing;

/**
 * Fabric client-side entrypoint for Minecraft 1.21.1. Answers the server's login query
 * by collecting every top-level installed mod (id + version), signing the list, and
 * sending it back.
 *
 * <p>Only top-level mods are reported: nested (jar-in-jar) library mods are skipped,
 * so a whitelist stays manageable. The parent mod that bundles them is still reported
 * and checked.</p>
 */
@Environment(EnvType.CLIENT)
public class HailWallFabricClient implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("HailWall");

	private static final ResourceLocation CHANNEL =
			ResourceLocation.fromNamespaceAndPath(Protocol.CHANNEL_NAMESPACE, Protocol.CHANNEL_PATH);

	@Override
	public void onInitializeClient() {
		ClientLoginNetworking.registerGlobalReceiver(CHANNEL, (client, listener, buf, callbacksConsumer) -> {
			// Server query: protocol version + random challenge.
			buf.readVarInt(); // server protocol version (read to consume the buffer)
			byte[] challenge = buf.readByteArray();

			Map<String, String> mods = new TreeMap<>();
			for (ModContainer container : FabricLoader.getInstance().getAllMods()) {
				if (container.getContainingMod().isPresent()) {
					continue; // skip nested (jar-in-jar) library mods
				}
				mods.put(container.getMetadata().getId(),
						container.getMetadata().getVersion().getFriendlyString());
			}

			// Print the reported modlist to logs/latest.log to help admins gather modids.
			StringBuilder ids = new StringBuilder("[");
			boolean first = true;
			for (String id : mods.keySet()) {
				if (!first) {
					ids.append(", ");
				}
				first = false;
				ids.append('"').append(id).append('"');
			}
			ids.append(']');
			LOGGER.info("[HailWall] Companion active. Modlist sent to server ({} mods): {}", mods.size(), ids);

			byte[] canonical = ModListCodec.encode(mods);
			byte[] mac = Signing.sign(challenge, canonical);

			FriendlyByteBuf out = PacketByteBufs.create();
			out.writeVarInt(Protocol.VERSION);
			out.writeByteArray(canonical);
			out.writeByteArray(mac);

			return CompletableFuture.completedFuture(out);
		});
	}
}
