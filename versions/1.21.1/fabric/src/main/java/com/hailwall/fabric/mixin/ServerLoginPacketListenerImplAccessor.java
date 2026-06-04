package com.hailwall.fabric.mixin;

import com.mojang.authlib.GameProfile;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.server.network.ServerLoginPacketListenerImpl;

/**
 * Exposes the private {@code authenticatedProfile} field of the login listener so
 * we can read the player's real {@link GameProfile} (and thus the bare username).
 *
 * <p>The field name {@code authenticatedProfile} is identical in 1.21.1 and 26.1.2
 * under Mojang mappings.</p>
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public interface ServerLoginPacketListenerImplAccessor {
	@Accessor("authenticatedProfile")
	GameProfile getAuthenticatedProfile();
}
