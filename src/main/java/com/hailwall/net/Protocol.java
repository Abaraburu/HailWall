package com.hailwall.net;

import net.minecraft.resources.Identifier;

/**
 * Shared constants for the HailWall login handshake. Lives in the {@code main}
 * source set so both the server entrypoint and the client entrypoint can use it.
 */
public final class Protocol {
	public static final String MOD_ID = "hailwall";

	/** Login query channel used to request/return the client mod list. */
	public static final Identifier CHANNEL = Identifier.fromNamespaceAndPath(MOD_ID, "modlist");

	/** Handshake protocol version. Bump when the wire format changes. */
	public static final int VERSION = 1;

	private Protocol() {
	}
}
