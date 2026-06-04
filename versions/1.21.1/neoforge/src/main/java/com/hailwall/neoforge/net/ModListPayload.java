package com.hailwall.neoforge.net;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import com.hailwall.net.Protocol;

/**
 * Client -&gt; server configuration-phase payload: the protocol version, the canonical
 * mod-list bytes, and the HMAC over {@code challenge || canonical}.
 */
public record ModListPayload(int protocolVersion, byte[] canonical, byte[] mac) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<ModListPayload> TYPE =
			new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Protocol.CHANNEL_NAMESPACE, Protocol.CHANNEL_PATH));

	public static final StreamCodec<ByteBuf, ModListPayload> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, ModListPayload::protocolVersion,
			ByteBufCodecs.BYTE_ARRAY, ModListPayload::canonical,
			ByteBufCodecs.BYTE_ARRAY, ModListPayload::mac,
			ModListPayload::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
