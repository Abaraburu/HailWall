package com.hailwall.neoforge.net;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import com.hailwall.net.Protocol;

/**
 * Server -&gt; client configuration-phase payload: the protocol version and a random
 * per-connection challenge the client must sign.
 */
public record ChallengePayload(int protocolVersion, byte[] challenge) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<ChallengePayload> TYPE =
			new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Protocol.CHANNEL_NAMESPACE, "challenge"));

	public static final StreamCodec<ByteBuf, ChallengePayload> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, ChallengePayload::protocolVersion,
			ByteBufCodecs.BYTE_ARRAY, ChallengePayload::challenge,
			ChallengePayload::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
