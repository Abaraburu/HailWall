package com.hailwall.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Server-side configuration, stored at {@code config/hailwall.json}.
 * Created with sensible defaults on first run.
 */
public class HailWallConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger("HailWall");
	private static final Gson GSON = new GsonBuilder()
			.setPrettyPrinting()
			.disableHtmlEscaping()
			.create();

	/** "whitelist" (kick if a NON-listed mod is present) or "blacklist" (kick only if a forbidden mod is present). */
	public String mode = "whitelist";

	/**
	 * true = enforce (kick offenders). false = TRANSITION / MONITOR mode: nobody is ever kicked,
	 * but every player's mods are still recorded in the access log (entries that WOULD have been
	 * kicked are noted as "MONITOR (would kick: ...)"). Use this to collect modids and build your
	 * whitelist without disturbing players, then set it back to true.
	 */
	public boolean enforce = true;

	/** If true, server operators (in ops.json) are NEVER kicked by HailWall, even with extra/forbidden mods. */
	public boolean operatorsBypass = true;

	/** If true, clients without the HailWall companion mod (vanilla / other clients) are kicked. */
	public boolean requireCompanionMod = true;

	/** If true, the HMAC signature of the reported mod list is verified. */
	public boolean verifySignature = true;

	/** If true, log a line in the server console for every successful verification. */
	public boolean logModLists = true;

	/** If true, write an access log (who joined, with which mods) under logs/hailwall/. */
	public boolean enableAccessLog = true;

	/** How many days of access logs to keep. Older daily files are deleted automatically. */
	public int accessLogRetentionDays = 5;

	/**
	 * Mod ids allowed in "whitelist" mode (case-insensitive). ADD YOUR ALLOWED MODS HERE.
	 * minecraft, java, fabricloader, fabric-api, hailwall and the "fabric-*" submodules are always allowed.
	 */
	public List<String> whitelist = new ArrayList<>();

	/** Mod ids forbidden in "blacklist" mode (case-insensitive). EXAMPLES — edit for your server. */
	public List<String> blacklist = new ArrayList<>(List.of(
			"meteor-client",
			"wurst",
			"baritone",
			"xray",
			"xrayultimate",
			"freecam"
	));

	/** Mod ids that MUST be present, otherwise the player is kicked (case-insensitive). */
	public List<String> requiredMods = new ArrayList<>();

	// Kick messages (shown on the client disconnect screen). Fully configurable / translatable.
	public String messageForbidden = "Kicked by HailWall: disallowed mods detected:";
	public String messageMissingRequired = "Kicked by HailWall: required mods are missing:";
	public String messageNoCompanion = "This server requires the HailWall mod. Please install it (client-side) to join.";
	public String messageTampered = "Mod verification failed: invalid signature.";
	public String messageProtocol = "Incompatible HailWall protocol version: please update the mod.";

	public static HailWallConfig loadOrCreate() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve("hailwall.json");
		try {
			if (Files.exists(path)) {
				try (Reader r = Files.newBufferedReader(path)) {
					HailWallConfig cfg = GSON.fromJson(r, HailWallConfig.class);
					if (cfg == null) {
						cfg = new HailWallConfig();
					}
					// Re-save so newly added fields appear in the file on disk.
					cfg.save(path);
					return cfg;
				}
			}
			HailWallConfig cfg = new HailWallConfig();
			cfg.save(path);
			return cfg;
		} catch (Exception e) {
			LOGGER.error("[HailWall] Failed to load config, using defaults", e);
			return new HailWallConfig();
		}
	}

	public void save(Path path) {
		try {
			Files.createDirectories(path.getParent());
			try (Writer w = Files.newBufferedWriter(path)) {
				GSON.toJson(this, w);
			}
		} catch (IOException e) {
			LOGGER.error("[HailWall] Failed to save config", e);
		}
	}
}
