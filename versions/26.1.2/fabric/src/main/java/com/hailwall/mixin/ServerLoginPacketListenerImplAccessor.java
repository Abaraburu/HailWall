package com.hailwall.mixin;

import com.mojang.authlib.GameProfile;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.server.network.ServerLoginPacketListenerImpl;

/**
 * Exposes the private {@code authenticatedProfile} field of the login listener so
 * we can read the player's real {@link GameProfile} (and thus the bare username).
 *
 * <p>Needed because {@code ServerLoginPacketListenerImpl#getUserName()} returns a
 * decorated string like {@code "name (uuid)"}, which does not match operator names.</p>
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public interface ServerLoginPacketListenerImplAccessor {
	@Accessor("authenticatedProfile")
	GameProfile getAuthenticatedProfile();
}
