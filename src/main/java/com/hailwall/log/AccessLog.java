package com.hailwall.log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Lightweight access log: one JSON-Lines file per day under {@code config/hailwall/}
 * (e.g. {@code config/hailwall/access-2026-06-02.jsonl}). It lives under {@code config/}
 * (not {@code logs/}) because some hosts (e.g. Aternos) expose the config folder but not logs.
 *
 * <p>All disk I/O runs on a dedicated daemon thread, so the server/netty threads never block
 * on the filesystem. Daily files older than the retention window are deleted, so the log never
 * grows unbounded and there is no costly file rewriting.</p>
 */
public final class AccessLog {
	private static final Logger LOGGER = LoggerFactory.getLogger("HailWall");
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
	private static final String PREFIX = "access-";
	private static final String SUFFIX = ".jsonl";

	private final boolean enabled;
	private final int retentionDays;
	private final Path dir;
	private final ExecutorService io;
	private volatile LocalDate lastPruneDay;

	public AccessLog(boolean enabled, int retentionDays) {
		this.enabled = enabled;
		this.retentionDays = Math.max(1, retentionDays);
		// Under config/ so it is reachable on hosts that hide the logs/ folder (Aternos, etc.).
		this.dir = FabricLoader.getInstance().getConfigDir().resolve("hailwall");
		if (enabled) {
			this.io = Executors.newSingleThreadExecutor(r -> {
				Thread t = new Thread(r, "HailWall-AccessLog");
				t.setDaemon(true);
				return t;
			});
			this.io.execute(() -> {
				try {
					Files.createDirectories(dir);
				} catch (IOException e) {
					LOGGER.error("[HailWall] Cannot create access-log directory {}", dir, e);
				}
				prune();
			});
		} else {
			this.io = null;
		}
	}

	/**
	 * Records an access. The JSON line is built on the calling thread (cheap, pure CPU) and the
	 * actual disk write is handed to the IO thread, so the server thread never blocks on the disk.
	 *
	 * @param player  player name
	 * @param allowed true = joined, false = kicked
	 * @param reason  optional note / kick reason (may be {@code null})
	 * @param mods    reported mods (id -&gt; version); may be empty/unknown
	 */
	public void record(String player, boolean allowed, String reason, Map<String, String> mods) {
		if (!enabled) {
			return;
		}
		final ZonedDateTime now = ZonedDateTime.now();
		final LocalDate today = now.toLocalDate();

		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("time", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
		entry.put("epoch", now.toInstant().toEpochMilli());
		entry.put("player", player == null ? "<unknown>" : player);
		entry.put("action", allowed ? "JOIN" : "KICK");
		if (reason != null) {
			entry.put("reason", reason);
		}

		List<String> modList = new ArrayList<>();
		if (mods != null && !mods.isEmpty()) {
			List<String> ids = new ArrayList<>(mods.keySet());
			Collections.sort(ids);
			for (String id : ids) {
				String v = mods.get(id);
				modList.add(v == null || v.isEmpty() ? id : id + " " + v);
			}
		}
		entry.put("modCount", modList.size());
		entry.put("mods", modList);

		final String line = GSON.toJson(entry) + System.lineSeparator();
		try {
			io.execute(() -> writeLine(today, line));
		} catch (Throwable ignore) {
			// Executor unavailable (shutting down): drop this entry rather than disturb login.
		}
	}

	private void writeLine(LocalDate today, String line) {
		try {
			if (!today.equals(lastPruneDay)) {
				prune();
			}
			Path file = dir.resolve(PREFIX + today.format(DAY) + SUFFIX);
			Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (Exception e) {
			LOGGER.error("[HailWall] Failed to write access log", e);
		}
	}

	private void prune() {
		lastPruneDay = LocalDate.now();
		if (!Files.isDirectory(dir)) {
			return;
		}
		// Keep today plus (retentionDays - 1) previous days.
		LocalDate cutoff = LocalDate.now().minusDays(retentionDays - 1L);
		try (Stream<Path> files = Files.list(dir)) {
			files.filter(p -> {
				String n = p.getFileName().toString();
				return n.startsWith(PREFIX) && n.endsWith(SUFFIX);
			}).forEach(p -> {
				String n = p.getFileName().toString();
				String datePart = n.substring(PREFIX.length(), n.length() - SUFFIX.length());
				try {
					LocalDate d = LocalDate.parse(datePart, DAY);
					if (d.isBefore(cutoff)) {
						Files.deleteIfExists(p);
						LOGGER.info("[HailWall] Pruned old access log {}", n);
					}
				} catch (Exception ignore) {
					// Not a dated log file; leave it alone.
				}
			});
		} catch (IOException e) {
			LOGGER.error("[HailWall] Failed to prune access logs", e);
		}
	}
}
