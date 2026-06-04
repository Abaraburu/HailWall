package com.hailwall.net;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Deterministic, order-independent serialization of a mod list so that the client
 * and server compute the exact same bytes for signing.
 *
 * <p>Format: entries sorted by id, each {@code id \t version}, joined by {@code \n}.</p>
 *
 * <p>Loader-agnostic (part of {@code core}); Java 8 compatible.</p>
 */
public final class ModListCodec {
	public static byte[] encode(Map<String, String> idToVersion) {
		TreeMap<String, String> sorted = new TreeMap<>(idToVersion);
		StringBuilder sb = new StringBuilder();
		boolean first = true;
		for (Map.Entry<String, String> e : sorted.entrySet()) {
			if (!first) {
				sb.append('\n');
			}
			first = false;
			sb.append(e.getKey()).append('\t').append(e.getValue() == null ? "" : e.getValue());
		}
		return sb.toString().getBytes(StandardCharsets.UTF_8);
	}

	public static Map<String, String> decode(byte[] bytes) {
		Map<String, String> out = new LinkedHashMap<>();
		if (bytes == null || bytes.length == 0) {
			return out;
		}
		String s = new String(bytes, StandardCharsets.UTF_8);
		for (String line : s.split("\n")) {
			if (line.isEmpty()) {
				continue;
			}
			int tab = line.indexOf('\t');
			if (tab < 0) {
				out.put(line, "");
			} else {
				out.put(line.substring(0, tab), line.substring(tab + 1));
			}
		}
		return out;
	}

	private ModListCodec() {
	}
}
