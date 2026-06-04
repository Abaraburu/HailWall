package com.hailwall.neoforge;

import java.security.SecureRandom;
import java.util.function.Consumer;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;

import net.neoforged.neoforge.network.configuration.ICustomConfigurationTask;

import com.hailwall.neoforge.net.ChallengePayload;
import com.hailwall.net.Protocol;

/**
 * Server-side configuration task that holds the NeoForge configuration phase open
 * until the client's mod list has been received and verified.
 *
 * <p>If the client does not advertise the HailWall channel it has no companion mod;
 * we apply the {@code requireCompanionMod} policy and finish. Otherwise we send the
 * challenge and let {@link HailWallNeoForge#onServerModList} finish (or disconnect)
 * when the signed reply arrives.</p>
 */
public final class HailWallConfigTask implements ICustomConfigurationTask {
	public static final ConfigurationTask.Type TYPE = new ConfigurationTask.Type(Protocol.MOD_ID + ":verify");

	private static final SecureRandom RANDOM = new SecureRandom();

	private final ServerConfigurationPacketListenerImpl listener;

	public HailWallConfigTask(ServerConfigurationPacketListenerImpl listener) {
		this.listener = listener;
	}

	@Override
	public void run(Consumer<CustomPacketPayload> sender) {
		if (!listener.hasChannel(ChallengePayload.TYPE)) {
			// The client cannot receive our challenge -> it has no HailWall companion mod.
			boolean allowed = HailWallNeoForge.handleNoCompanion(listener);
			if (allowed) {
				listener.finishCurrentTask(TYPE);
			}
			return;
		}
		byte[] challenge = new byte[16];
		RANDOM.nextBytes(challenge);
		HailWallNeoForge.PENDING.put(listener, challenge);
		sender.accept(new ChallengePayload(Protocol.VERSION, challenge));
		// Do NOT finish here: HailWallNeoForge.onServerModList completes or disconnects on reply.
	}

	@Override
	public ConfigurationTask.Type type() {
		return TYPE;
	}
}
