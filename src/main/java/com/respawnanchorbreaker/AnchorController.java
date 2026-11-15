package com.respawnanchorbreaker;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
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
    private BlockPos breakBlock;     // block we may mine right now: the anchor, or snow covering it
    private static final int PICKUP_RADIUS = 8;
    private int clock;
    private int ticksSinceScan = 1000;
    private int reactionTicks;
    private int ticksOnTarget;
    private int inReachNotAimed;
    private int swapDelay = -1;
    private boolean wantAttack;
    private boolean holdingAttack;

    // eating / baritone state
    private int eatPending;
    private int eatDelay;
    private int eatTicks;
    private int eatPause;
    private boolean eating;
    private boolean useSeen;
    private boolean eatKeysDown;
    private int minedSinceGoto;
    private int lastBreakTick = -1000;

    // sprint-jump state
    private boolean pathing;          // true after we sent the goto command, until we start working again
    private int gotoSentTick = -100000;
    private boolean hopDown;
    private int hopCooldown;
    private int hopScan = 1000;
    private boolean anchorClose;

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
            stopEating(mc);
            releaseHop(mc);
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
        releaseHop(mc); // a hop is a single-tick key tap
        if (pickup != null || eating || target != null) pathing = false;
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        ignoredUntil.values().removeIf(exp -> exp <= clock);
        ignoredItems.values().removeIf(exp -> exp <= clock);
        if (eatDelay > 0) eatDelay--;

        // ---- dropped anchor pickup (takes over only when we are not mid-break)
        if (pickup != null && !pickupStillValid(player, cfg)) {
            endPickup(mc);
        }
        if (pickup == null && cfg.autoPickup && !wantAttack && !eating && ++itemScanClock >= 5) {
            itemScanClock = 0;
            ItemEntity found = findDroppedAnchor(level, player, PICKUP_RADIUS);
            if (found != null) {
                clearTarget(mc);
                stopEating(mc);
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

        // ---- eating after each anchor (only when not mid-break)
        if (cfg.autoEat && eatPending > 0 && eatDelay == 0 && !wantAttack) {
            if (tickEating(mc, player, level, cfg)) return;
        } else if (eating || eatKeysDown) {
            stopEating(mc);
        }

        // ---- anchor breaking
        if (target != null && !stillValid(player, level)) {
            if (ticksOnTarget > 0 && !level.getBlockState(target).is(Blocks.RESPAWN_ANCHOR)) {
                onAnchorBroken(cfg);
            }
            clearTarget(mc);
        }

        if (target == null) {
            if (++ticksSinceScan >= cfg.scanIntervalTicks) {
                ticksSinceScan = 0;
                acquire(player, level, player.blockInteractionRange() - cfg.reachMargin);
                if (target == null) maybeSendGoto(mc, cfg);
            }
            if (target == null) {
                wantAttack = false;
                tickHop(mc, player, level, cfg);
                return;
            }
        }

        Vec3 eye = player.getEyePosition();
        double reach = player.blockInteractionRange();

        if (clock % 10 == 0 && resolveBreakBlock(level, player, eye, aimPoint, target) == null) {
            Vec3 np = findAimPoint(level, player, target, reach);
            if (np == null) {
                ignore(target, 200);
                clearTarget(mc);
                return;
            }
            aimPoint = np;
        }

        // the block to mine right now: the anchor itself, or a snow layer covering it
        breakBlock = resolveBreakBlock(level, player, eye, aimPoint, target);
        boolean aimed = breakBlock != null && hitsTarget(mc);

        if (reactionTicks > 0) {
            reactionTicks--;
        } else if (!aimed) {
            rotateToward(player, eye, aimPoint);
        }

        boolean near = eye.distanceTo(aimPoint) <= reach + 1.0;
        boolean slotReady = !near || !cfg.autoSwap || ensureSlot(player, cfg.pickaxeSlot);
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
        double max = PICKUP_RADIUS + 2.0;
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


    // ---------------------------------------------------------------- eating / baritone

    private void onAnchorBroken(Config cfg) {
        minedSinceGoto++;
        lastBreakTick = clock;
        if (cfg.autoEat) {
            eatPending = cfg.eatCount;
            eatDelay = 12; // give the drop time to appear and be picked up first
        }
    }

    /** Eats one piece at a time from the food slot. Returns true while eating owns this tick. */
    private boolean tickEating(Minecraft mc, LocalPlayer player, ClientLevel level, Config cfg) {
        if (eatPause > 0) {
            eatPause--;
            return true;
        }
        if (!eating) {
            if (!player.canEat(false)) { // already full: nothing to try
                eatPending = 0;
                return false;
            }
            clearTarget(mc);
            eating = true;
            eatTicks = 0;
            useSeen = false;
        }
        if (!ensureSlot(player, cfg.foodSlot)) return true;

        // Sneaking makes right-click use the item instead of interacting with the block under the crosshair.
        mc.options.keyShift.setDown(true);
        eatKeysDown = true;

        // Never right-click while pointing at a respawn anchor (outside the Nether it can explode).
        if (mc.hitResult instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK
                && level.getBlockState(b.getBlockPos()).is(Blocks.RESPAWN_ANCHOR)) {
            mc.options.keyUse.setDown(false);
            rotateTo(player, player.getYRot(), 75.0F);
            return true;
        }

        mc.options.keyUse.setDown(true);
        eatTicks++;
        if (player.isUsingItem()) {
            useSeen = true;
        } else if (useSeen) { // bite finished
            stopEating(mc);
            eatPending--;
            eatPause = 6;
            return true;
        }
        if ((!useSeen && eatTicks > 12) || eatTicks > 120) { // not edible / can't eat / stuck
            eatPending = 0;
            stopEating(mc);
            return false;
        }
        return true;
    }

    private void stopEating(Minecraft mc) {
        if (eatKeysDown) {
            mc.options.keyUse.setDown(false);
            mc.options.keyShift.setDown(false);
            eatKeysDown = false;
        }
        eating = false;
        useSeen = false;
        eatTicks = 0;
    }

    /** Once everything nearby is mined, picked up and eaten, tell Baritone to go to the next anchor. */
    private void maybeSendGoto(Minecraft mc, Config cfg) {
        if (!cfg.baritoneGoto || minedSinceGoto <= 0) return;
        if (pickup != null || eatPending > 0 || eating) return;
        if (clock - lastBreakTick < 40) return;
        ClientPacketListener conn = mc.getConnection();
        if (conn == null) return;
        conn.sendChat(cfg.gotoCommand);
        minedSinceGoto = 0;
        pathing = true;
        gotoSentTick = clock;
    }


    // ---------------------------------------------------------------- sprint jumping

    private void releaseHop(Minecraft mc) {
        if (hopDown) {
            mc.options.keyJump.setDown(false);
            hopDown = false;
        }
    }

    /**
     * While Baritone is pathing to the next anchor and we have nothing else to do, tap jump whenever it is
     * clearly safe: sprinting on flat ground with clear space ahead. Anything that looks like a step, gap,
     * drop, low ceiling, liquid or ladder is left to Baritone (that's its parkour/precise movement).
     */
    private void tickHop(Minecraft mc, LocalPlayer player, ClientLevel level, Config cfg) {
        if (!cfg.autoHop || !pathing) return;
        if (clock - gotoSentTick > 2400) { // 2 minutes: Baritone is surely done or gave up
            pathing = false;
            return;
        }
        if (hopCooldown > 0) {
            hopCooldown--;
            return;
        }
        if (mc.options.keyJump.isDown()) return; // Baritone (or you) is already jumping
        if (!hopSafe(level, player)) return;

        if (++hopScan >= 5) {
            hopScan = 0;
            anchorClose = anchorWithin(level, player.getEyePosition(), 6.0);
        }
        if (anchorClose) return; // about to work: don't overshoot

        mc.options.keyJump.setDown(true);
        hopDown = true;
        hopCooldown = 2;
    }

    private boolean hopSafe(ClientLevel level, LocalPlayer player) {
        if (!player.onGround() || !player.isSprinting()) return false;
        if (player.isInWater() || player.isInLava() || player.onClimbable() || player.isFallFlying()) return false;

        Vec3 d = player.getDeltaMovement();
        double hs = Math.hypot(d.x, d.z);
        if (hs < 0.15) return false; // not really moving
        double ux = d.x / hs, uz = d.z / hs;
        Vec3 pos = player.position();

        // 4 blocks of headroom for the jump itself, and for the next 3 blocks ahead:
        // clear body space, solid flat ground below, no liquids or hazards.
        for (int i = 0; i <= 3; i++) {
            BlockPos col = BlockPos.containing(pos.x + ux * i, pos.y, pos.z + uz * i);
            for (int h = 0; h <= 3; h++) {
                BlockPos bp = col.above(h);
                BlockState st = level.getBlockState(bp);
                if (!st.getCollisionShape(level, bp).isEmpty() || !st.getFluidState().isEmpty() || hazard(st)) {
                    // the block at our own feet column counts too: standing in something odd -> don't hop
                    return false;
                }
            }
            if (i == 0) continue;
            BlockPos below = col.below();
            BlockState bs = level.getBlockState(below);
            if (bs.getCollisionShape(level, below).isEmpty() || hazard(bs)) return false; // gap or drop ahead
        }
        return true;
    }

    private boolean anchorWithin(ClientLevel level, Vec3 eye, double r) {
        BlockPos o = BlockPos.containing(eye);
        int n = (int) Math.ceil(r);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -n; dx <= n; dx++) {
            for (int dy = -n; dy <= n; dy++) {
                for (int dz = -n; dz <= n; dz++) {
                    m.set(o.getX() + dx, o.getY() + dy, o.getZ() + dz);
                    if (level.getBlockState(m).is(Blocks.RESPAWN_ANCHOR)
                            && eye.distanceToSqr(Vec3.atCenterOf(m)) <= r * r) return true;
                }
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- helpers

    /** Selects the given hotbar slot (1-9) after a short human-like delay. True once it is selected. */
    private boolean ensureSlot(LocalPlayer p, int slot1Based) {
        int want = Mth.clamp(slot1Based, 1, 9) - 1;
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
                && (eating || !mc.player.isUsingItem());
    }

    private void reset(Minecraft mc) {
        releaseHop(mc);
        releaseAttack(mc);
        stopWalking(mc);
        stopEating(mc);
        target = null;
        aimPoint = null;
        breakBlock = null;
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
        breakBlock = null;
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
        return player.getEyePosition().distanceTo(Vec3.atCenterOf(target))
                <= player.blockInteractionRange() + 1.0;
    }

    /** True only if BOTH our own ray and the game's current hit result point at the block we may mine. */
    private boolean hitsTarget(Minecraft mc) {
        LocalPlayer player = mc.player;
        BlockPos want = breakBlock;
        if (player == null || want == null) return false;
        HitResult own = player.pick(player.blockInteractionRange(), 1.0F, false);
        if (!(own instanceof BlockHitResult ob) || ob.getType() != HitResult.Type.BLOCK
                || !ob.getBlockPos().equals(want)) return false;
        return mc.hitResult instanceof BlockHitResult mb
                && mb.getType() == HitResult.Type.BLOCK
                && mb.getBlockPos().equals(want);
    }

    /**
     * Which block must be mined to get at the anchor from here: the anchor itself, or (if enabled) a snow
     * layer touching it that is in the way. Returns null if something else blocks the view.
     */
    private BlockPos resolveBreakBlock(ClientLevel level, LocalPlayer player, Vec3 eye, Vec3 point, BlockPos anchor) {
        if (point == null || anchor == null) return null;
        BlockHitResult hit = level.clip(new ClipContext(
                eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK) return null;
        BlockPos hp = hit.getBlockPos();
        if (hp.equals(anchor)) return anchor;
        if (Config.get().clearSnow && level.getBlockState(hp).is(Blocks.SNOW) && hp.distManhattan(anchor) == 1) {
            return hp.immutable();
        }
        return null;
    }

    /** Finds the closest anchors within maxDist (measured to the aim point) and targets the nearest usable one. */
    private void acquire(LocalPlayer player, ClientLevel level, double maxDist) {
        Vec3 eye = player.getEyePosition();
        BlockPos origin = BlockPos.containing(eye);
        int n = (int) Math.ceil(maxDist) + 1;
        double scanR = maxDist + 0.9;
        List<BlockPos> found = new ArrayList<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -n; dx <= n; dx++) {
            for (int dy = -n; dy <= n; dy++) {
                for (int dz = -n; dz <= n; dz++) {
                    m.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!level.getBlockState(m).is(Blocks.RESPAWN_ANCHOR)) continue;
                    if (eye.distanceToSqr(Vec3.atCenterOf(m)) > scanR * scanR) continue;
                    if (ignoredUntil.containsKey(m)) continue;
                    found.add(m.immutable());
                }
            }
        }
        found.sort((a, b) -> Double.compare(
                eye.distanceToSqr(Vec3.atCenterOf(a)), eye.distanceToSqr(Vec3.atCenterOf(b))));
        for (BlockPos pos : found) {
            Vec3 p = findAimPoint(level, player, pos, maxDist);
            if (p != null) {
                target = pos;
                aimPoint = p;
                reactionTicks = 3 + rng.nextInt(6);
                ticksOnTarget = 0;
                inReachNotAimed = 0;
                return;
            }
        }
    }

    /** A point on/in the anchor that is within maxDist of the eyes and not blocked by anything but snow. */
    private Vec3 findAimPoint(ClientLevel level, LocalPlayer player, BlockPos pos, double maxDist) {
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
            if (eye.distanceTo(p) > maxDist) continue;
            if (resolveBreakBlock(level, player, eye, p, pos) != null) return p;
        }
        return null;
    }

    private void rotateToward(LocalPlayer p, Vec3 eye, Vec3 point) {
        double dx = point.x - eye.x, dy = point.y - eye.y, dz = point.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float targetYaw = (float) (Mth.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F;
        float targetPitch = (float) (-(Mth.atan2(dy, horiz) * 180.0 / Math.PI));
        // Straight above/below: yaw is meaningless, so keep it instead of spinning around.
        if (horiz < 0.1) targetYaw = p.getYRot();
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
