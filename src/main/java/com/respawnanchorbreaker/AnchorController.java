package com.respawnanchorbreaker;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Looks at and breaks respawn anchors (turning the camera and holding the normal attack key),
 * switches to the pickaxe slot first, and walks to pick up the dropped anchor item.
 * Walking uses the normal forward/jump keys and stops at ledges and hazards.
 */
public final class AnchorController {
    private final Random rng = new Random();
    private final Map<BlockPos, Integer> ignoredUntil = new HashMap<>();
    private final Map<Integer, Integer> ignoredItems = new HashMap<>();

    // anchor-breaking state
    private BlockPos target;
    private Vec3 aimPoint;
    private int clock;
    private int ticksSinceScan = 1000;
    private int reactionTicks;
    private int ticksOnTarget;
    private int inReachNotAimed;
    private int swapDelay = -1;
    private boolean wantAttack;
    private boolean holdingAttack;

    // pickup state
    private ItemEntity pickup;
    private int itemScanClock = 1000;
    private int pickupTicks;
    private int waitTicks;
    private int stuckTicks;
    private Vec3 lastPos;
    private boolean walking;
    private boolean jumping;

    // ---------------------------------------------------------------- tick hooks

    /** Start of tick: only decide whether the attack key is held, using a fresh check. */
    public void startTick(Minecraft mc) {
        if (!active(mc)) {
            releaseAttack(mc);
            stopWalking(mc);
            return;
        }
        if (target == null) {
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

    /** End of tick: toggle key, pickup, target selection, camera rotation and bookkeeping. */
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
        ignoredItems.values().removeIf(exp -> exp <= clock);

        // ---- dropped anchor pickup (takes over only when we are not mid-break)
        if (pickup != null && !pickupStillValid(player, cfg)) {
            endPickup(mc);
        }
        if (pickup == null && cfg.autoPickup && !wantAttack && ++itemScanClock >= 5) {
            itemScanClock = 0;
            ItemEntity found = findDroppedAnchor(level, player, cfg.radius);
            if (found != null) {
                clearTarget(mc);
                pickup = found;
                pickupTicks = 0;
                waitTicks = 0;
                stuckTicks = 0;
                lastPos = null;
                reactionTicks = 2 + rng.nextInt(4);
            }
        }
        if (pickup != null) {
            runPickup(mc, player, level);
            return;
        }
        stopWalking(mc);

        // ---- anchor breaking
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

        double reach = player.blockInteractionRange();
        boolean near = eye.distanceTo(aimPoint) <= reach + 1.0;
        boolean slotReady = !near || ensurePickaxeSlot(player, cfg);
        boolean aimed = hitsTarget(mc);
        wantAttack = aimed && slotReady;

        if (wantAttack) {
            ticksOnTarget++;
            inReachNotAimed = 0;
            if (ticksOnTarget > 1200) {
                ignore(target, 400);
                clearTarget(mc);
            }
        } else if (!aimed) {
            if (eye.distanceTo(aimPoint) <= reach) {
                if (++inReachNotAimed > 60) {
                    ignore(target, 200);
                    clearTarget(mc);
                }
            } else {
                inReachNotAimed = 0;
            }
        }
    }

    // ---------------------------------------------------------------- pickup

    private boolean pickupStillValid(LocalPlayer player, Config cfg) {
        if (pickup.isRemoved() || !pickup.getItem().is(Items.RESPAWN_ANCHOR)) return false;
        double max = cfg.radius + 2.0;
        return pickup.position().distanceToSqr(player.position()) <= max * max;
    }

    private ItemEntity findDroppedAnchor(ClientLevel level, LocalPlayer player, int radius) {
        AABB box = player.getBoundingBox().inflate(radius);
        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, box,
                e -> !e.isRemoved() && e.getItem().is(Items.RESPAWN_ANCHOR));
        Vec3 pp = player.position();
        ItemEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (ItemEntity e : items) {
            if (ignoredItems.containsKey(e.getId())) continue;
            if (level.getBlockState(e.blockPosition()).getFluidState().is(FluidTags.LAVA)) continue;
            double d = e.position().distanceToSqr(pp);
            if (d > (double) radius * radius) continue;
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    private void runPickup(Minecraft mc, LocalPlayer player, ClientLevel level) {
        if (reactionTicks > 0) {
            reactionTicks--;
            return;
        }
        Vec3 ip = pickup.position();
        Vec3 eye = player.getEyePosition();
        double dx = ip.x - player.getX();
        double dz = ip.z - player.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        double vert = ip.y - player.getY();

        float targetYaw = (float) (Mth.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F;
        float targetPitch = (float) (-(Mth.atan2(ip.y + 0.2 - eye.y, Math.max(horiz, 0.5)) * 180.0 / Math.PI));
        targetPitch = Mth.clamp(targetPitch, -5.0F, 35.0F);
        if (horiz > 0.35) rotateTo(player, targetYaw, targetPitch);

        if (++pickupTicks > 400) {
            giveUpPickup(mc);
            return;
        }

        if (horiz < 0.45 && Math.abs(vert) < 1.5) {
            // standing on it: stop and wait for the game to pick it up
            stopWalking(mc);
            if (++waitTicks > 60) giveUpPickup(mc);
            return;
        }

        float yawErr = Math.abs(Mth.wrapDegrees(targetYaw - player.getYRot()));
        if (yawErr > 30.0F) {
            stopWalking(mc);
            return;
        }
        if (!safeAhead(level, player, player.getYRot())) {
            giveUpPickup(mc);
            return;
        }

        mc.options.keyUp.setDown(true);
        walking = true;

        // stuck detection: pressed against a wall/step -> jump, then give up
        Vec3 pos = player.position();
        if (lastPos != null) {
            double moved = Math.hypot(pos.x - lastPos.x, pos.z - lastPos.z);
            if (moved < 0.03) stuckTicks++; else stuckTicks = 0;
        }
        lastPos = pos;
        if (stuckTicks >= 4) {
            mc.options.keyJump.setDown(true);
            jumping = true;
        } else if (jumping) {
            mc.options.keyJump.setDown(false);
            jumping = false;
        }
        if (stuckTicks > 40) giveUpPickup(mc);
    }

    private void giveUpPickup(Minecraft mc) {
        if (pickup != null) ignoredItems.put(pickup.getId(), clock + 600);
        endPickup(mc);
    }

    private void endPickup(Minecraft mc) {
        stopWalking(mc);
        pickup = null;
        itemScanClock = 1000;
    }

    private void stopWalking(Minecraft mc) {
        if (walking) {
            mc.options.keyUp.setDown(false);
            walking = false;
        }
        if (jumping) {
            mc.options.keyJump.setDown(false);
            jumping = false;
        }
        stuckTicks = 0;
        lastPos = null;
    }

    /** Refuses to walk toward lava/fire/magma or off a drop of more than maxDropBlocks (default 20). */
    private boolean safeAhead(ClientLevel level, LocalPlayer p, float yawDeg) {
        double rad = Math.toRadians(yawDeg);
        double dirX = -Math.sin(rad);
        double dirZ = Math.cos(rad);
        Vec3 pos = p.position();
        for (double d : new double[]{0.7, 1.4}) {
            BlockPos col = BlockPos.containing(pos.x + dirX * d, pos.y, pos.z + dirZ * d);
            if (hazard(level.getBlockState(col)) || hazard(level.getBlockState(col.above()))) return false;
            boolean ground = false;
            for (int dy = 0; dy >= -Config.get().maxDropBlocks; dy--) {
                BlockPos bp = col.above(dy);
                BlockState st = level.getBlockState(bp);
                if (hazard(st)) return false;
                if (!st.getCollisionShape(level, bp).isEmpty()) {
                    ground = true;
                    break;
                }
            }
            if (!ground) return false;
        }
        return true;
    }

    private boolean hazard(BlockState st) {
        return st.getFluidState().is(FluidTags.LAVA)
                || st.is(BlockTags.FIRE)
                || st.is(BlockTags.CAMPFIRES)
                || st.is(Blocks.MAGMA_BLOCK)
                || st.is(Blocks.CACTUS);
    }

    // ---------------------------------------------------------------- helpers

    private boolean ensurePickaxeSlot(LocalPlayer p, Config cfg) {
        if (!cfg.autoSwap) return true;
        int want = Mth.clamp(cfg.pickaxeSlot, 1, 9) - 1;
        Inventory inv = p.getInventory();
        if (inv.getSelectedSlot() == want) {
            swapDelay = -1;
            return true;
        }
        if (swapDelay < 0) swapDelay = 2 + rng.nextInt(4); // short "human" delay
        if (--swapDelay <= 0) {
            inv.setSelectedSlot(want);
            swapDelay = -1;
        }
        return false;
    }

    private boolean active(Minecraft mc) {
        return Config.get().enabled
                && mc.player != null && mc.level != null && mc.gameMode != null
                && mc.gui.screen() == null
                && !mc.player.isSpectator()
                && !mc.player.isPassenger()
                && !mc.player.isUsingItem();
    }

    private void reset(Minecraft mc) {
        releaseAttack(mc);
        stopWalking(mc);
        target = null;
        aimPoint = null;
        pickup = null;
        wantAttack = false;
        swapDelay = -1;
        ticksSinceScan = 1000;
        itemScanClock = 1000;
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
        ticksSinceScan = 1000;
    }

    private void ignore(BlockPos pos, int ticks) {
        ignoredUntil.put(pos.immutable(), clock + ticks);
    }

    private boolean stillValid(LocalPlayer player, ClientLevel level) {
        if (!level.getBlockState(target).is(Blocks.RESPAWN_ANCHOR)) return false;
        double max = Config.get().radius + 1.5;
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
            ignore(pos, 100);
        }
    }

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
        if (point == null) return false;
        BlockHitResult hit = level.clip(new ClipContext(
                eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK) return false;
        BlockPos hp = hit.getBlockPos();
        return level.getBlockState(hp).is(Blocks.RESPAWN_ANCHOR)
                && (target == null || hp.equals(target) || hp.equals(BlockPos.containing(point)));
    }

    private void rotateToward(LocalPlayer p, Vec3 eye, Vec3 point) {
        double dx = point.x - eye.x, dy = point.y - eye.y, dz = point.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float targetYaw = (float) (Mth.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F;
        float targetPitch = (float) (-(Mth.atan2(dy, horiz) * 180.0 / Math.PI));
        rotateTo(p, targetYaw, targetPitch);
    }

    /** Eased, slightly noisy rotation: fast when far, slow when close, with a small dead zone. */
    private void rotateTo(LocalPlayer p, float targetYaw, float targetPitch) {
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
