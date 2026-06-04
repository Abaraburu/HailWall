package com.hailwall.net;

/**
 * Shared constants for the HailWall login handshake.
 *
 * <p>This class is part of the loader-agnostic {@code core} source set, so it must
 * not reference any Minecraft or mod-loader type. The channel is therefore exposed
 * as a namespace/path pair of plain strings; each platform builds its own resource
 * identifier ({@code Identifier} / {@code ResourceLocation}) from these.</p>
 */
public final class Protocol {
	public static final String MOD_ID = "hailwall";

	/** Namespace of the login channel used to request/return the client mod list. */
	public static final String CHANNEL_NAMESPACE = MOD_ID;

	/** Path of the login channel. */
	public static final String CHANNEL_PATH = "modlist";

	/** Handshake protocol version. Bump when the wire format changes. */
	public static final int VERSION = 1;

	private Protocol() {
	}
}
