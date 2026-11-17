package com.respawnanchorbreaker;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

public class RespawnAnchorBreakerClient implements ClientModInitializer {
    public static KeyMapping toggleKey;
    public static KeyMapping guiKey;

    @Override
    public void onInitializeClient() {
        Config.load();
        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.respawn_anchor_breaker.toggle",
                InputConstants.Type.KEYBOARD,
                InputConstants.KEY_R,
                KeyMapping.Category.MISC));
        guiKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.respawn_anchor_breaker.gui",
                InputConstants.Type.KEYBOARD,
                InputConstants.KEY_RCONTROL,
                KeyMapping.Category.MISC));
        Modules.registerKeys();

        // One listener per event, registered exactly once.
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (guiKey.consumeClick()) {
                if (mc.player != null && mc.gui.screen() == null) {
                    mc.gui.setScreen(new ClickGuiScreen());
                }
            }
            Modules.tickKeys(mc);
        });

        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("respawn_anchor_breaker", "overlay"),
                (graphics, delta) -> {
                    // the menu draws the overlays itself (so they can be dragged); don't double-draw them
                    if (Minecraft.getInstance().gui.screen() instanceof ClickGuiScreen) return;
                    Overlays.drawAll(graphics);
                });

        AnchorController controller = new AnchorController();
        ClientTickEvents.START_CLIENT_TICK.register(controller::startTick);
        ClientTickEvents.END_CLIENT_TICK.register(controller::endTick);
        ActivityLog.info("System", "Anchor Addon loaded.");
    }
}
