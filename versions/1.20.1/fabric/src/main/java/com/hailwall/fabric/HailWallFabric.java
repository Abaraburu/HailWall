package com.hailwall.fabric;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mojang.authlib.GameProfile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking;
import net.fabricmc.loader.api.FabricLoader;

import com.hailwall.config.HailWallConfig;
import com.hailwall.fabric.mixin.ServerLoginPacketListenerImplAccessor;
import com.hailwall.log.AccessLog;
import com.hailwall.net.ModListCodec;
import com.hailwall.net.Protocol;
import com.hailwall.server.ModVerifier;

/**
 * Fabric server-side entrypoint for Minecraft 1.20.1. During the login phase it asks
 * the client for its mod list, verifies the (signed) response against the config, and
 * disconnects the player if disallowed mods are present (or required ones are missing,
 * or the companion mod is absent).
 *
 * <p>All the loader-agnostic logic lives in the shared {@code core}; this class only
 * provides the Fabric "glue".</p>
 */
public class HailWallFabric implements ModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("HailWall");
	private static final SecureRandom RANDOM = new SecureRandom();

	/** Login channel. 1.20.1 has no {@code ResourceLocation.fromNamespaceAndPath}; use the constructor. */
	static final ResourceLocation CHANNEL =
			new ResourceLocation(Protocol.CHANNEL_NAMESPACE, Protocol.CHANNEL_PATH);

	private static final Set<String> ALWAYS_ALLOWED = new HashSet<>(Arrays.asList(
			"minecraft", "java", "fabricloader", "fabric-api", "fabric", Protocol.MOD_ID));

	private static final List<String> ALLOWED_PREFIXES = Collections.singletonList("fabric-");

	private HailWallConfig config;
	private AccessLog accessLog;

	@Override
	public void onInitialize() {
		this.config = HailWallConfig.loadOrCreate(FabricLoader.getInstance().getConfigDir());
		this.accessLog = new AccessLog(config.enableAccessLog, config.accessLogRetentionDays,
				FabricLoader.getInstance().getConfigDir());
		LOGGER.info("[HailWall] Ready. enforce={}, mode={}, operatorsBypass={}, requireCompanionMod={}, whitelist={}, blacklist={}, accessLog={}",
				config.enforce, config.mode, config.operatorsBypass, config.requireCompanionMod,
				config.whitelist.size(), config.blacklist.size(), config.enableAccessLog);
		if (!config.enforce) {
			LOGGER.info("[HailWall] MONITOR MODE (enforce=false): nobody will be kicked; mods are only recorded in the access log.");
		}

		ServerLoginConnectionEvents.QUERY_START.register((listener, server, sender, synchronizer) -> {
			final byte[] challenge = new byte[16];
			RANDOM.nextBytes(challenge);

			ServerLoginNetworking.registerReceiver(listener, CHANNEL,
					(server1, listener1, understood, buf, synchronizer1, responseSender) -> {
						if (!understood) {
							synchronizer1.waitFor(server1.submit(() -> handleNoCompanion(server1, listener1)));
							return;
						}

						boolean malformed = false;
						int proto = -1;
						byte[] canonical = null;
						byte[] mac = null;
						try {
							proto = buf.readVarInt();
							canonical = buf.readByteArray();
							mac = buf.readByteArray();
						} catch (Exception ex) {
							malformed = true;
						}

						final boolean fMalformed = malformed;
						final int fProto = proto;
						final byte[] fCanonical = canonical;
						final byte[] fMac = mac;
						synchronizer1.waitFor(server1.submit(() ->
								handleResponse(server1, listener1, fMalformed, fProto, challenge, fCanonical, fMac)));
					});

			FriendlyByteBuf out = PacketByteBufs.create();
			out.writeVarInt(Protocol.VERSION);
			out.writeByteArray(challenge);
			sender.sendPacket(CHANNEL, out);
		});
	}

	private void handleNoCompanion(MinecraftServer server, ServerLoginPacketListenerImpl listener) {
		String who = playerName(listener);
		if (!config.requireCompanionMod) {
			accessLog.record(who, true, "companion mod absent (not required)", Map.of());
			return;
		}
		if (!config.enforce) {
			LOGGER.info("[HailWall] (monitor) {} has no companion mod (not kicked).", who);
			accessLog.record(who, true, "MONITOR (no companion mod)", Map.of());
			return;
		}
		if (config.operatorsBypass && isOperator(server, who)) {
			LOGGER.info("[HailWall] {} is operator -> bypassing restrictions (allowed).", who);
			accessLog.record(who, true, "OP bypass (no companion mod)", Map.of());
			return;
		}
		LOGGER.info("[HailWall] Rejecting {}: no companion mod.", who);
		accessLog.record(who, false, "no companion mod", Map.of());
		listener.disconnect(Component.literal(config.messageNoCompanion));
	}

	private void handleResponse(MinecraftServer server, ServerLoginPacketListenerImpl listener,
			boolean malformed, int proto, byte[] challenge, byte[] canonical, byte[] mac) {
		String who = playerName(listener);
		Map<String, String> mods = malformed ? Map.of() : ModListCodec.decode(canonical);
		String reason = malformed
				? config.messageTampered
				: ModVerifier.evaluate(proto, challenge, canonical, mac, config, ALWAYS_ALLOWED, ALLOWED_PREFIXES);

		if (reason == null) {
			boolean op = isOperator(server, who);
			if (config.logModLists) {
				LOGGER.info("[HailWall] {} passed mod verification ({} mods).", who, mods.size());
			}
			accessLog.record(who, true, op ? "OP" : null, mods);
			return;
		}

		if (!config.enforce) {
			LOGGER.info("[HailWall] (monitor) {} would be kicked: {}", who, reason);
			accessLog.record(who, true, "MONITOR (would kick: " + reason + ")", mods);
			return;
		}

		if (config.operatorsBypass && isOperator(server, who)) {
			LOGGER.info("[HailWall] {} is operator -> bypassing restrictions (allowed).", who);
			accessLog.record(who, true, "OP bypass", mods);
			return;
		}

		LOGGER.info("[HailWall] Rejecting {}: {}", who, reason);
		accessLog.record(who, false, reason, mods);
		listener.disconnect(Component.literal(reason));
	}

	private static boolean isOperator(MinecraftServer server, String name) {
		if (name == null || name.equals("<unknown>")) {
			return false;
		}
		try {
			for (String op : server.getPlayerList().getOpNames()) {
				if (op.equalsIgnoreCase(name)) {
					return true;
				}
			}
		} catch (Throwable t) {
			LOGGER.warn("[HailWall] Could not check operator status for {}", name, t);
		}
		return false;
	}

	/**
	 * Returns the player's bare username via the login {@link GameProfile} (read through an
	 * accessor; the field is {@code gameProfile} in 1.20.1).
	 */
	private static String playerName(ServerLoginPacketListenerImpl listener) {
		try {
			GameProfile profile = ((ServerLoginPacketListenerImplAccessor) listener).getGameProfile();
			if (profile != null && profile.getName() != null && !profile.getName().isEmpty()) {
				return profile.getName();
			}
		} catch (Throwable ignore) {
			// fall through to getUserName()
		}
		try {
			String u = listener.getUserName();
			if (u != null) {
				int sp = u.indexOf(" (");
				return sp > 0 ? u.substring(0, sp) : u;
			}
		} catch (Throwable ignore) {
			// ignore
		}
		return "<unknown>";
	}
}
