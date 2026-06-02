package com.hailwall;

import java.security.SecureRandom;
import java.util.Map;

import com.mojang.authlib.GameProfile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.FriendlyByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking;

import com.hailwall.config.HailWallConfig;
import com.hailwall.log.AccessLog;
import com.hailwall.mixin.ServerLoginPacketListenerImplAccessor;
import com.hailwall.net.ModListCodec;
import com.hailwall.net.Protocol;
import com.hailwall.server.ModVerifier;

/**
 * Server-side entrypoint. During the login phase it asks the client for its mod
 * list, verifies the (signed) response against the config, and disconnects the
 * player if disallowed mods are present (or required ones are missing, or the
 * companion mod is absent).
 *
 * <p>{@code operatorsBypass} exempts server operators. {@code enforce=false} turns
 * on transition/monitor mode: nobody is kicked, but every join is still logged.</p>
 *
 * <p>Runs on dedicated servers and on integrated (LAN) servers.</p>
 */
public class HailWallMod implements ModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("HailWall");
	private static final SecureRandom RANDOM = new SecureRandom();

	private HailWallConfig config;
	private AccessLog accessLog;

	@Override
	public void onInitialize() {
		this.config = HailWallConfig.loadOrCreate();
		this.accessLog = new AccessLog(config.enableAccessLog, config.accessLogRetentionDays);
		LOGGER.info("[HailWall] Ready. enforce={}, mode={}, operatorsBypass={}, requireCompanionMod={}, whitelist={}, blacklist={}, accessLog={}",
				config.enforce, config.mode, config.operatorsBypass, config.requireCompanionMod,
				config.whitelist.size(), config.blacklist.size(), config.enableAccessLog);
		if (!config.enforce) {
			LOGGER.info("[HailWall] MONITOR MODE (enforce=false): nobody will be kicked; mods are only recorded in the access log.");
		}

		ServerLoginConnectionEvents.QUERY_START.register((listener, server, sender, synchronizer) -> {
			// Unique challenge per connection -> the client's signature can't be replayed.
			final byte[] challenge = new byte[16];
			RANDOM.nextBytes(challenge);

			ServerLoginNetworking.registerReceiver(listener, Protocol.CHANNEL,
					(server1, listener1, understood, buf, synchronizer1, responseSender) -> {
						if (!understood) {
							// No HailWall channel handler on the client -> companion mod missing.
							synchronizer1.waitFor(server1.submit(() -> handleNoCompanion(server1, listener1)));
							return;
						}

						// Read the payload on the netty thread; reject gracefully if it is malformed
						// (a misbehaving/malicious client must not throw uncaught or spam exceptions).
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

			// Ask the client for its mod list.
			FriendlyByteBuf out = FriendlyByteBufs.create();
			out.writeVarInt(Protocol.VERSION);
			out.writeByteArray(challenge);
			sender.sendPacket(Protocol.CHANNEL, out);
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
				: ModVerifier.evaluate(proto, challenge, canonical, mac, config);

		if (reason == null) {
			boolean op = isOperator(server, who);
			if (config.logModLists) {
				LOGGER.info("[HailWall] {} passed mod verification ({} mods).", who, mods.size());
			}
			accessLog.record(who, true, op ? "OP" : null, mods);
			return;
		}

		// There is a violation (or an invalid signature / malformed payload).
		if (!config.enforce) {
			LOGGER.info("[HailWall] (monitor) {} would be kicked: {}", who, reason);
			accessLog.record(who, true, "MONITOR (would kick: " + reason + ")", mods);
			return;
		}

		if (config.operatorsBypass && isOperator(server, who)) {
			// Operator: do NOT print the offending mods in console (just note the bypass).
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
	 * Returns the player's bare username. We read the authenticated {@link GameProfile}
	 * via an accessor because {@code getUserName()} returns a decorated string
	 * ({@code "name (uuid)"}) that would not match operator names.
	 */
	private static String playerName(ServerLoginPacketListenerImpl listener) {
		try {
			GameProfile profile = ((ServerLoginPacketListenerImplAccessor) listener).getAuthenticatedProfile();
			if (profile != null && profile.name() != null && !profile.name().isEmpty()) {
				return profile.name();
			}
		} catch (Throwable ignore) {
			// Accessor unavailable for some reason; fall back to parsing getUserName().
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
