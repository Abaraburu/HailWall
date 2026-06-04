package com.hailwall.net;

import java.nio.charset.StandardCharsets;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Lightweight HMAC-SHA256 signing of the reported mod list.
 *
 * <p>The same jar is installed on both the client and the server, so both sides
 * automatically share {@link #SECRET}. The per-connection random challenge sent
 * by the server is mixed into the signature, which makes naive packet replay and
 * spoofing fail.</p>
 *
 * <p><b>Honest security note:</b> this is obfuscation-grade, not cryptographically
 * secure. A determined attacker can decompile the jar, extract {@link #SECRET} and
 * forge a valid signature with a fake mod list. This is an inherent limit of every
 * client-side mod check. To raise the bar for your own server, change the secret
 * below and rebuild the jar (client and server get the new value from the same build).</p>
 *
 * <p>Loader-agnostic (part of {@code core}); Java 8 compatible.</p>
 */
public final class Signing {
	private static final byte[] SECRET =
			"HailWall::change-me-and-rebuild::v1".getBytes(StandardCharsets.UTF_8);

	/**
	 * @param challenge the random challenge sent by the server for this connection
	 * @param payload   the canonical mod-list bytes
	 * @return HMAC-SHA256 over {@code challenge || payload}
	 */
	public static byte[] sign(byte[] challenge, byte[] payload) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(SECRET, "HmacSHA256"));
			mac.update(challenge);
			mac.update(payload);
			return mac.doFinal();
		} catch (Exception e) {
			throw new IllegalStateException("HailWall: HMAC computation failed", e);
		}
	}

	private Signing() {
	}
}
