package com.hailwall.fabric.mixin;

import com.mojang.authlib.GameProfile;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.server.network.ServerLoginPacketListenerImpl;

/**
 * Exposes the private {@code gameProfile} field of the login listener so we can read the
 * player's real {@link GameProfile} (and thus the bare username).
 *
 * <p>In 1.20.1 the field is named {@code gameProfile} (it became {@code authenticatedProfile}
 * in later versions).</p>
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public interface ServerLoginPacketListenerImplAccessor {
	@Accessor("gameProfile")
	GameProfile getGameProfile();
}
