package com.respawnanchorbreaker;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Looks at and breaks respawn anchors by (a) turning the camera and (b) holding the normal attack key.
 * It never moves the player, changes reach, places blocks or uses items.
 */
public final class AnchorController {
    private final Random rng = new Random();
    private final Map<BlockPos, Integer> ignoredUntil = new HashMap<>();

    private BlockPos target;
    private Vec3 aimPoint;
    private int clock;
    private int ticksSinceScan = 1000;
    private int reactionTicks;
    private int ticksOnTarget;       // ticks spent holding attack on the current target
    private int inReachNotAimed;     // ticks within reach but not hitting the anchor
    private boolean wantAttack;
    private boolean holdingAttack;

    // ---------------------------------------------------------------- tick hooks

    /** Start of tick: only decide whether the attack key is held, using a fresh check. */
    public void startTick(Minecraft mc) {
        if (!active(mc) || target == null) {
            releaseAttack(mc);
            return;
        }
        if (wantAttack && hitsTarget(mc)) {
            mc.options.keyAttack.setDown(true);
            holdingAttack = true;
        } else {
            releaseAttack(mc);
        }
    }

    /** End of tick: handle toggle, target selection, camera rotation and bookkeeping. */
    public void endTick(Minecraft mc) {
        Config cfg = Config.get();
        while (RespawnAnchorBreakerClient.toggleKey.consumeClick()) {
            cfg.enabled = !cfg.enabled;
            Config.save();
            if (mc.player != null) {
                mc.player.sendSystemMessage(Component.literal(
                        "Respawn Anchor Breaker: " + (cfg.enabled ? "ON" : "OFF")));
            }
        }

        clock++;
        if (!active(mc)) {
            reset(mc);
            return;
        }
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        ignoredUntil.values().removeIf(exp -> exp <= clock);

        if (target != null && !stillValid(player, level)) {
            clearTarget(mc);
        }

        if (target == null) {
            if (++ticksSinceScan >= cfg.scanIntervalTicks) {
                ticksSinceScan = 0;
                acquire(player, level, cfg.radius);
            }
            if (target == null) {
                wantAttack = false;
                return;
            }
        }

        Vec3 eye = player.getEyePosition();

        // Periodically make sure the aim point is still visible; otherwise pick another or give up.
        if (clock % 10 == 0 && !visible(level, player, eye, aimPoint)) {
            Vec3 np = findAimPoint(level, player, target);
            if (np == null) {
                ignore(target, 200);
                clearTarget(mc);
                return;
            }
            aimPoint = np;
        }

        if (reactionTicks > 0) {
            reactionTicks--;
        } else {
            rotateToward(player, eye, aimPoint);
        }

        boolean aimed = hitsTarget(mc);
        wantAttack = aimed;
        if (aimed) {
            ticksOnTarget++;
            inReachNotAimed = 0;
            if (ticksOnTarget > 1200) { // 60 s without breaking: give up on this one
                ignore(target, 400);
                clearTarget(mc);
            }
        } else {
            double reach = player.blockInteractionRange();
            if (eye.distanceTo(aimPoint) <= reach) {
                // close enough but not hitting the anchor (blocked, entity in the way...)
                if (++inReachNotAimed > 60) {
                    ignore(target, 200);
                    clearTarget(mc);
                }
            } else {
                inReachNotAimed = 0; // out of reach: just keep looking, the player may walk closer
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private boolean active(Minecraft mc) {
        return Config.get().enabled
                && mc.player != null && mc.level != null && mc.gameMode != null
                && mc.gui.screen() == null
                && !mc.player.isSpectator()
                && !mc.player.isUsingItem();
    }

    private void reset(Minecraft mc) {
        releaseAttack(mc);
        target = null;
        aimPoint = null;
        wantAttack = false;
        ticksSinceScan = 1000;
    }

    private void releaseAttack(Minecraft mc) {
        if (holdingAttack) {
            mc.options.keyAttack.setDown(false);
            holdingAttack = false;
        }
    }

    private void clearTarget(Minecraft mc) {
        releaseAttack(mc);
        target = null;
        aimPoint = null;
        wantAttack = false;
        ticksOnTarget = 0;
        inReachNotAimed = 0;
        ticksSinceScan = 1000; // look for the next anchor right away
    }

    private void ignore(BlockPos pos, int ticks) {
        ignoredUntil.put(pos.immutable(), clock + ticks);
    }

    private boolean stillValid(LocalPlayer player, ClientLevel level) {
        if (!level.getBlockState(target).is(Blocks.RESPAWN_ANCHOR)) return false;
        double max = Config.get().radius + 1.5; // small hysteresis so we don't flip-flop at the edge
        return player.getEyePosition().distanceToSqr(Vec3.atCenterOf(target)) <= max * max;
    }

    /** True only if BOTH our own ray and the game's current hit result point at the target anchor. */
    private boolean hitsTarget(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || target == null) return false;
        HitResult own = player.pick(player.blockInteractionRange(), 1.0F, false);
        if (!(own instanceof BlockHitResult ob) || ob.getType() != HitResult.Type.BLOCK
                || !ob.getBlockPos().equals(target)) return false;
        return mc.hitResult instanceof BlockHitResult mb
                && mb.getType() == HitResult.Type.BLOCK
                && mb.getBlockPos().equals(target);
    }

    private void acquire(LocalPlayer player, ClientLevel level, int radius) {
        Vec3 eye = player.getEyePosition();
        BlockPos origin = BlockPos.containing(eye);
        double r2 = (double) radius * radius;
        List<BlockPos> found = new ArrayList<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    m.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!level.getBlockState(m).is(Blocks.RESPAWN_ANCHOR)) continue;
                    if (eye.distanceToSqr(Vec3.atCenterOf(m)) > r2) continue;
                    if (ignoredUntil.containsKey(m)) continue;
                    found.add(m.immutable());
                }
            }
        }
        found.sort((a, b) -> Double.compare(
                eye.distanceToSqr(Vec3.atCenterOf(a)), eye.distanceToSqr(Vec3.atCenterOf(b))));
        for (BlockPos pos : found) {
            Vec3 p = findAimPoint(level, player, pos);
            if (p != null) {
                target = pos;
                aimPoint = p;
                reactionTicks = 3 + rng.nextInt(6);
                ticksOnTarget = 0;
                inReachNotAimed = 0;
                return;
            }
            ignore(pos, 100); // fully obstructed from here: try again later
        }
    }

    /** Picks a point on/in the anchor that has a clear line of sight from the player's eyes. */
    private Vec3 findAimPoint(ClientLevel level, LocalPlayer player, BlockPos pos) {
        Vec3 eye = player.getEyePosition();
        double x = pos.getX(), y = pos.getY(), z = pos.getZ();
        double[][] candidates = {
                {0.5 + (rng.nextDouble() - 0.5) * 0.4, 0.5 + (rng.nextDouble() - 0.5) * 0.4, 0.5 + (rng.nextDouble() - 0.5) * 0.4},
                {0.5, 0.5, 0.5},
                {0.5, 0.9, 0.5}, {0.5, 0.1, 0.5},
                {0.9, 0.5, 0.5}, {0.1, 0.5, 0.5},
                {0.5, 0.5, 0.9}, {0.5, 0.5, 0.1},
        };
        for (double[] c : candidates) {
            Vec3 p = new Vec3(x + c[0], y + c[1], z + c[2]);
            if (visible(level, player, eye, p)) return p;
        }
        return null;
    }

    private boolean visible(ClientLevel level, LocalPlayer player, Vec3 eye, Vec3 point) {
        if (target == null && point == null) return false;
        BlockHitResult hit = level.clip(new ClipContext(
                eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK) return false;
        BlockPos hp = hit.getBlockPos();
        return level.getBlockState(hp).is(Blocks.RESPAWN_ANCHOR)
                && (target == null || hp.equals(target) || hp.equals(BlockPos.containing(point)));
    }

    /** Eased, slightly noisy rotation: fast when far, slow when close, with a small dead zone. */
    private void rotateToward(LocalPlayer p, Vec3 eye, Vec3 point) {
        double dx = point.x - eye.x, dy = point.y - eye.y, dz = point.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float targetYaw = (float) (Mth.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F;
        float targetPitch = (float) (-(Mth.atan2(dy, horiz) * 180.0 / Math.PI));

        float dYaw = Mth.wrapDegrees(targetYaw - p.getYRot());
        float dPitch = targetPitch - p.getXRot();
        float dist = (float) Math.hypot(dYaw, dPitch);
        if (dist < 0.35F) return;

        float step = Mth.clamp(dist * 0.22F, 0.4F, 14.0F);
        step *= 0.85F + rng.nextFloat() * 0.3F;
        step = Math.min(step, dist);
        float ratio = step / dist;

        p.setYRot(p.getYRot() + dYaw * ratio);
        p.setXRot(Mth.clamp(p.getXRot() + dPitch * ratio, -90.0F, 90.0F));
    }
}
