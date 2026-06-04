package com.hailwall.server;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.hailwall.config.HailWallConfig;
import com.hailwall.net.ModListCodec;
import com.hailwall.net.Protocol;
import com.hailwall.net.Signing;

/**
 * Pure validation logic: given the client's signed mod list and the config,
 * decide whether the player may join.
 *
 * <p>Loader-agnostic (part of {@code core}); Java 8 compatible. The set of always
 * allowed ids and the allowed id-prefixes are injected by the platform layer,
 * because the "essential" ids differ per loader (e.g. {@code fabricloader} /
 * {@code fabric-api} / {@code fabric-*} on Fabric, {@code neoforge} on NeoForge,
 * {@code forge} on Forge).</p>
 */
public final class ModVerifier {
	/**
	 * @param protocolVersion the protocol version reported by the client
	 * @param challenge       the per-connection challenge the server sent
	 * @param canonical       the canonical mod-list bytes the client signed
	 * @param mac             the client's HMAC over {@code challenge || canonical}
	 * @param cfg             the server configuration
	 * @param alwaysAllowed   ids that are always permitted even in whitelist mode
	 *                        (lower-case); typically minecraft/java/the loader ids/hailwall
	 * @param allowedPrefixes id prefixes always permitted in whitelist mode (lower-case),
	 *                        e.g. {@code "fabric-"} for fabric-api submodules
	 * @return {@code null} if the player may join; otherwise the kick reason to display.
	 */
	public static String evaluate(int protocolVersion, byte[] challenge, byte[] canonical, byte[] mac,
			HailWallConfig cfg, Set<String> alwaysAllowed, List<String> allowedPrefixes) {
		if (protocolVersion != Protocol.VERSION) {
			return cfg.messageProtocol;
		}

		if (cfg.verifySignature) {
			byte[] expected = Signing.sign(challenge, canonical);
			if (mac == null || !MessageDigest.isEqual(expected, mac)) {
				return cfg.messageTampered;
			}
		}

		Map<String, String> mods = ModListCodec.decode(canonical);
		Set<String> ids = new HashSet<>();
		for (String id : mods.keySet()) {
			ids.add(id.toLowerCase(Locale.ROOT));
		}

		List<String> prefixes = new ArrayList<>();
		if (allowedPrefixes != null) {
			for (String p : allowedPrefixes) {
				if (p != null && !p.isEmpty()) {
					prefixes.add(p.toLowerCase(Locale.ROOT));
				}
			}
		}

		List<String> offenders = new ArrayList<>();
		if ("whitelist".equalsIgnoreCase(cfg.mode)) {
			Set<String> allow = new HashSet<>();
			if (alwaysAllowed != null) {
				for (String a : alwaysAllowed) {
					allow.add(a.toLowerCase(Locale.ROOT));
				}
			}
			if (cfg.whitelist != null) {
				for (String w : cfg.whitelist) {
					allow.add(w.toLowerCase(Locale.ROOT));
				}
			}
			for (String id : ids) {
				if (allow.contains(id) || hasPrefix(id, prefixes)) {
					continue;
				}
				offenders.add(id);
			}
		} else {
			Set<String> banned = new HashSet<>();
			if (cfg.blacklist != null) {
				for (String b : cfg.blacklist) {
					banned.add(b.toLowerCase(Locale.ROOT));
				}
			}
			for (String id : ids) {
				if (banned.contains(id)) {
					offenders.add(id);
				}
			}
		}

		if (!offenders.isEmpty()) {
			offenders.sort(String::compareTo);
			return cfg.messageForbidden + " " + String.join(", ", offenders);
		}

		if (cfg.requiredMods != null && !cfg.requiredMods.isEmpty()) {
			List<String> missing = new ArrayList<>();
			for (String req : cfg.requiredMods) {
				if (!ids.contains(req.toLowerCase(Locale.ROOT))) {
					missing.add(req);
				}
			}
			if (!missing.isEmpty()) {
				missing.sort(String::compareTo);
				return cfg.messageMissingRequired + " " + String.join(", ", missing);
			}
		}

		return null;
	}

	private static boolean hasPrefix(String id, List<String> prefixes) {
		for (String p : prefixes) {
			if (id.startsWith(p)) {
				return true;
			}
		}
		return false;
	}

	private ModVerifier() {
	}
}
