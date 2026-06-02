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
 */
public final class ModVerifier {
	/** Always permitted, even in whitelist mode. */
	private static final Set<String> ALWAYS_ALLOWED = Set.of(
			"minecraft", "java", "fabricloader", "fabric-api", "fabric", Protocol.MOD_ID
	);

	/**
	 * @return {@code null} if the player may join; otherwise the kick reason to display.
	 */
	public static String evaluate(int protocolVersion, byte[] challenge, byte[] canonical, byte[] mac, HailWallConfig cfg) {
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

		List<String> offenders = new ArrayList<>();
		if ("whitelist".equalsIgnoreCase(cfg.mode)) {
			Set<String> allow = new HashSet<>(ALWAYS_ALLOWED);
			if (cfg.whitelist != null) {
				for (String w : cfg.whitelist) {
					allow.add(w.toLowerCase(Locale.ROOT));
				}
			}
			for (String id : ids) {
				if (allow.contains(id) || id.startsWith("fabric-")) {
					continue; // fabric-api ships many "fabric-*" submodule ids
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

	private ModVerifier() {
	}
}
