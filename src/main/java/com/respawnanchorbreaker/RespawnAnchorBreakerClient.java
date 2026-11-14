package com.respawnanchorbreaker;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;

public class RespawnAnchorBreakerClient implements ClientModInitializer {
    public static KeyMapping toggleKey;

    @Override
    public void onInitializeClient() {
        Config.load();
        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.respawn_anchor_breaker.toggle",
                InputConstants.Type.KEYBOARD,
                InputConstants.KEY_R,
                KeyMapping.Category.MISC));

        AnchorController controller = new AnchorController();
        ClientTickEvents.START_CLIENT_TICK.register(controller::startTick);
        ClientTickEvents.END_CLIENT_TICK.register(controller::endTick);
    }
}
