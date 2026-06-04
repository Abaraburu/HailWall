package com.hailwall.neoforge;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.authlib.GameProfile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import com.hailwall.config.HailWallConfig;
import com.hailwall.log.AccessLog;
import com.hailwall.neoforge.net.ChallengePayload;
import com.hailwall.neoforge.net.ModListPayload;
import com.hailwall.net.ModListCodec;
import com.hailwall.net.Protocol;
import com.hailwall.net.Signing;
import com.hailwall.server.ModVerifier;

/**
 * NeoForge entrypoint for Minecraft 1.21.1. During the configuration phase the server
 * asks the client for its mod list (via a custom payload + a configuration task that
 * holds the handshake open), verifies the signed response against the config, and
 * disconnects the player if disallowed mods are present (or required ones are missing,
 * or the companion mod is absent).
 *
 * <p>All the loader-agnostic logic lives in the shared {@code core}; this class only
 * provides the NeoForge "glue".</p>
 */
@Mod("hailwall")
public class HailWallNeoForge {
	public static final Logger LOGGER = LoggerFactory.getLogger("HailWall");

	/** Ids always permitted on NeoForge, even in whitelist mode. */
	private static final Set<String> ALWAYS_ALLOWED = new HashSet<>(Arrays.asList(
			"minecraft", "java", "neoforge", "forge", Protocol.MOD_ID));

	/** No always-allowed id prefixes on NeoForge (unlike Fabric's "fabric-*"). */
	private static final List<String> ALLOWED_PREFIXES = Collections.emptyList();

	/** Per-connection challenges, keyed by the configuration listener, awaiting a reply. */
	static final Map<Object, byte[]> PENDING = new ConcurrentHashMap<>();

	private static HailWallConfig config;
	private static AccessLog accessLog;

	public HailWallNeoForge(IEventBus modBus) {
		config = HailWallConfig.loadOrCreate(FMLPaths.CONFIGDIR.get());
		accessLog = new AccessLog(config.enableAccessLog, config.accessLogRetentionDays, FMLPaths.CONFIGDIR.get());
		LOGGER.info("[HailWall] Ready. enforce={}, mode={}, operatorsBypass={}, requireCompanionMod={}, whitelist={}, blacklist={}, accessLog={}",
				config.enforce, config.mode, config.operatorsBypass, config.requireCompanionMod,
				config.whitelist.size(), config.blacklist.size(), config.enableAccessLog);
		if (!config.enforce) {
			LOGGER.info("[HailWall] MONITOR MODE (enforce=false): nobody will be kicked; mods are only recorded in the access log.");
		}

		modBus.addListener(this::onRegisterPayloads);
		modBus.addListener(HailWallNeoForge::onRegisterConfigurationTasks);
	}

	private void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
		PayloadRegistrar registrar = event.registrar("1").optional();
		registrar.configurationToClient(ChallengePayload.TYPE, ChallengePayload.STREAM_CODEC, HailWallNeoForge::onClientChallenge);
		registrar.configurationToServer(ModListPayload.TYPE, ModListPayload.STREAM_CODEC, HailWallNeoForge::onServerModList);
	}

	private static void onRegisterConfigurationTasks(RegisterConfigurationTasksEvent event) {
		event.register(new HailWallConfigTask((ServerConfigurationPacketListenerImpl) event.getListener()));
	}

	/** CLIENT: respond to the server's challenge with our signed, top-level mod list. */
	private static void onClientChallenge(ChallengePayload msg, IPayloadContext ctx) {
		Map<String, String> mods = new TreeMap<>();
		for (IModInfo mi : ModList.get().getMods()) {
			mods.put(mi.getModId(), mi.getVersion().toString());
		}

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
		byte[] mac = Signing.sign(msg.challenge(), canonical);
		ctx.reply(new ModListPayload(Protocol.VERSION, canonical, mac));
	}

	/** SERVER: verify the client's reply, then finish the configuration task or disconnect. */
	private static void onServerModList(ModListPayload msg, IPayloadContext ctx) {
		final ServerConfigurationPacketListenerImpl listener = (ServerConfigurationPacketListenerImpl) ctx.listener();
		byte[] challenge = PENDING.remove(listener);
		if (challenge == null) {
			challenge = new byte[0]; // no pending challenge -> signature check will fail (treated as tampered)
		}
		final String who = playerName(listener);
		final Map<String, String> mods = ModListCodec.decode(msg.canonical());
		final String reason = ModVerifier.evaluate(msg.protocolVersion(), challenge, msg.canonical(), msg.mac(),
				config, ALWAYS_ALLOWED, ALLOWED_PREFIXES);

		ctx.enqueueWork(() -> {
			if (reason == null) {
				boolean op = isOperator(who);
				if (config.logModLists) {
					LOGGER.info("[HailWall] {} passed mod verification ({} mods).", who, mods.size());
				}
				accessLog.record(who, true, op ? "OP" : null, mods);
				listener.finishCurrentTask(HailWallConfigTask.TYPE);
				return;
			}

			if (!config.enforce) {
				LOGGER.info("[HailWall] (monitor) {} would be kicked: {}", who, reason);
				accessLog.record(who, true, "MONITOR (would kick: " + reason + ")", mods);
				listener.finishCurrentTask(HailWallConfigTask.TYPE);
				return;
			}

			if (config.operatorsBypass && isOperator(who)) {
				LOGGER.info("[HailWall] {} is operator -> bypassing restrictions (allowed).", who);
				accessLog.record(who, true, "OP bypass", mods);
				listener.finishCurrentTask(HailWallConfigTask.TYPE);
				return;
			}

			LOGGER.info("[HailWall] Rejecting {}: {}", who, reason);
			accessLog.record(who, false, reason, mods);
			ctx.disconnect(Component.literal(reason));
		});
	}

	/**
	 * Called from {@link HailWallConfigTask} when the client has no HailWall channel.
	 *
	 * @return {@code true} if the player may stay (task should finish), {@code false} if disconnected.
	 */
	static boolean handleNoCompanion(ServerConfigurationPacketListenerImpl listener) {
		String who = playerName(listener);
		if (!config.requireCompanionMod) {
			accessLog.record(who, true, "companion mod absent (not required)", Collections.emptyMap());
			return true;
		}
		if (!config.enforce) {
			LOGGER.info("[HailWall] (monitor) {} has no companion mod (not kicked).", who);
			accessLog.record(who, true, "MONITOR (no companion mod)", Collections.emptyMap());
			return true;
		}
		if (config.operatorsBypass && isOperator(who)) {
			LOGGER.info("[HailWall] {} is operator -> bypassing restrictions (allowed).", who);
			accessLog.record(who, true, "OP bypass (no companion mod)", Collections.emptyMap());
			return true;
		}
		LOGGER.info("[HailWall] Rejecting {}: no companion mod.", who);
		accessLog.record(who, false, "no companion mod", Collections.emptyMap());
		listener.getConnection().disconnect(Component.literal(config.messageNoCompanion));
		return false;
	}

	private static String playerName(ServerConfigurationPacketListenerImpl listener) {
		try {
			GameProfile profile = listener.getOwner();
			if (profile != null && profile.getName() != null && !profile.getName().isEmpty()) {
				return profile.getName();
			}
		} catch (Throwable ignore) {
			// fall through
		}
		return "<unknown>";
	}

	private static boolean isOperator(String name) {
		if (name == null || name.equals("<unknown>")) {
			return false;
		}
		try {
			MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
			if (server == null) {
				return false;
			}
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
}
