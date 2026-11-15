package com.respawnanchorbreaker;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
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

    // ender-chest deposit state
    private enum Dep { NONE, PLACE, OPEN, TRANSFER, RETRIEVE }
    private Dep dep = Dep.NONE;
    private int depTicks;
    private int depWait;
    private int placeTries;
    private int openTries;
    private BlockPos chestFloor;
    private BlockPos chestPos;
    private Vec3 chestAim;
    private Vec3 chestOpenAim;
    private int transferWait;
    private int lastMovedSlot = -1;
    private int lastMovedCount = -1;
    private int stuckMoves;
    private boolean finishing;
    private boolean depSneak;
    private boolean useTapPending;
    private int depositCooldownUntil;
    private int chestPickupUntil;
    private int anchorCountTimer;

    // health / totem guard state
    private boolean recovering;
    private int recTicks;
    private int recoverCooldownUntil;
    private int lastCancelTick = -1000;
    private enum Tot { NONE, OPENING, WAIT, CLOSING }
    private Tot tot = Tot.NONE;
    private boolean offInit;
    private boolean lastOffTotem;
    private int popCandidateUntil = -1;
    private int totTicks;
    private int totWait;

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
            return;
        }
        if (breakBlock == null) {
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
        if (useTapPending) { // a use "click" is a single-tick key press
            mc.options.keyUse.setDown(false);
            useTapPending = false;
        }
        trackTotem(mc, cfg);
        if (tot != Tot.NONE && tickTotem(mc)) return;
        if (dep != Dep.NONE && mc.player != null && mc.level != null && containerOpen(mc)) {
            if (dep == Dep.OPEN) enterTransfer();
            if (dep == Dep.TRANSFER) {
                tickTransfer(mc, cfg);
                return;
            }
        }
        if (!active(mc)) {
            reset(mc);
            return;
        }
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        ignoredUntil.values().removeIf(exp -> exp <= clock);
        ignoredItems.values().removeIf(exp -> exp <= clock);
        if (eatDelay > 0) eatDelay--;

        // ---- health guard: cancel Baritone, eat until full, wait for full health, then resume
        if (cfg.healthGuard && !recovering && clock >= recoverCooldownUntil && !player.isCreative()
                && player.getHealth() < cfg.lowHealthHp) {
            beginRecovery(mc, player, cfg);
        }

        // ---- dropped anchor pickup (takes over only when we are not mid-break)
        if (pickup != null && !pickupStillValid(player, cfg)) {
            endPickup(mc);
        }
        if (pickup == null && !recovering && (cfg.autoPickup || clock < chestPickupUntil) && !wantAttack
                && !eating && ++itemScanClock >= 5) {
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
        if ((cfg.autoEat || recovering) && eatPending > 0 && eatDelay == 0 && !wantAttack) {
            if (tickEating(mc, player, level, cfg)) return;
        } else if (eating || eatKeysDown) {
            stopEating(mc);
        }
        if (recovering && tickRecovery(mc, player, cfg)) return;

        // ---- deposit anchors into an ender chest
        if (dep == Dep.NONE && cfg.depositAnchors && clock >= depositCooldownUntil && !wantAttack
                && eatPending == 0 && ++anchorCountTimer >= 10) {
            anchorCountTimer = 0;
            if (countAnchors(player) >= cfg.depositThreshold && playerStill(player)) {
                startDeposit(mc, player, level, cfg);
            }
        }
        if (dep != Dep.NONE) {
            tickDeposit(mc, player, level, cfg);
            return;
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
        if (pickup.isRemoved() || !wantedItem(pickup.getItem())) return false;
        double max = PICKUP_RADIUS + 2.0;
        return pickup.position().distanceToSqr(player.position()) <= max * max;
    }

    private boolean wantedItem(ItemStack st) {
        return (Config.get().autoPickup && st.is(Items.RESPAWN_ANCHOR))
                || (clock < chestPickupUntil && st.is(Items.ENDER_CHEST));
    }

    private ItemEntity findDroppedAnchor(ClientLevel level, LocalPlayer player, int radius) {
        AABB box = player.getBoundingBox().inflate(radius);
        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, box,
                e -> !e.isRemoved() && wantedItem(e.getItem()));
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
        if (pickup != null || eatPending > 0 || eating || dep != Dep.NONE) return;
        if (clock - lastBreakTick < 40) return;
        sendChatCommand(mc, cfg.gotoCommand);
        minedSinceGoto = 0;
    }



    // ---------------------------------------------------------------- ender chest deposit

    private int countAnchors(LocalPlayer p) {
        int n = 0;
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (st.is(Items.RESPAWN_ANCHOR)) n += st.getCount();
        }
        return n;
    }

    private int findHotbarSlot(LocalPlayer p, net.minecraft.world.item.Item item) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).is(item)) return i;
        }
        return -1;
    }

    private boolean playerStill(LocalPlayer p) {
        Vec3 d = p.getDeltaMovement();
        return Math.hypot(d.x, d.z) < 0.03;
    }

    private boolean containerOpen(Minecraft mc) {
        return mc.player != null && mc.gui.screen() != null && mc.player.containerMenu != mc.player.inventoryMenu;
    }

    private void notice(Minecraft mc, String msg) {
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal("Anchor Breaker: " + msg));
    }

    private double jit(double width) {
        return (rng.nextDouble() - 0.5) * width;
    }

    private void releaseDepKeys(Minecraft mc) {
        if (useTapPending) {
            mc.options.keyUse.setDown(false);
            useTapPending = false;
        }
        if (depSneak) {
            mc.options.keyShift.setDown(false);
            depSneak = false;
        }
    }

    private void startDeposit(Minecraft mc, LocalPlayer player, ClientLevel level, Config cfg) {
        if (findHotbarSlot(player, Items.ENDER_CHEST) < 0) {
            notice(mc, "deposit skipped, put an ender chest in your hotbar.");
            depositCooldownUntil = clock + 1200;
            return;
        }
        if (!findChestSpot(level, player)) {
            notice(mc, "deposit skipped, no good spot to place the ender chest here.");
            depositCooldownUntil = clock + 400;
            return;
        }
        clearTarget(mc);
        stopWalking(mc);
        dep = Dep.PLACE;
        depTicks = 0;
        depWait = 4 + rng.nextInt(7); // a moment to "notice" the inventory is full
        placeTries = 0;
        openTries = 0;
    }

    /** Picks a floor block near you whose top face can take the chest, with a clear view of it. */
    private boolean findChestSpot(ClientLevel level, LocalPlayer player) {
        Vec3 eye = player.getEyePosition();
        BlockPos feet = player.blockPosition();
        double reach = player.blockInteractionRange() - 0.4;
        List<BlockPos> cands = new ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 0; dy++) {
                    if (dx == 0 && dz == 0 && dy == -1) continue; // that's the block we stand on
                    cands.add(feet.offset(dx, dy, dz)); // floor block level: just below our feet, or one up
                }
            }
        }
        cands.sort((a, b) -> Double.compare(
                eye.distanceToSqr(Vec3.atCenterOf(a)), eye.distanceToSqr(Vec3.atCenterOf(b))));
        for (BlockPos floor : cands) {
            if (!spotOk(level, player, floor)) continue;
            Vec3 aim = new Vec3(floor.getX() + 0.5 + jit(0.4), floor.getY() + 0.98, floor.getZ() + 0.5 + jit(0.4));
            if (eye.distanceTo(aim) > reach) continue;
            BlockHitResult hit = level.clip(new ClipContext(
                    eye, aim, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(floor)
                    || hit.getDirection() != Direction.UP) continue;
            chestFloor = floor.immutable();
            chestPos = floor.above().immutable();
            chestAim = aim;
            return true;
        }
        return false;
    }

    private boolean spotOk(ClientLevel level, LocalPlayer player, BlockPos floor) {
        BlockState fs = level.getBlockState(floor);
        if (fs.is(Blocks.RESPAWN_ANCHOR) || hazard(fs)) return false;
        if (!fs.isFaceSturdy(level, floor, Direction.UP)) return false;
        BlockPos above = floor.above();
        BlockState as = level.getBlockState(above);
        if (!(as.isAir() || as.canBeReplaced()) || !as.getFluidState().isEmpty() || hazard(as)) return false;
        BlockPos lid = above.above();
        if (!level.getBlockState(lid).getCollisionShape(level, lid).isEmpty()) return false; // lid must open
        return !new AABB(above).intersects(player.getBoundingBox());
    }

    private boolean hitsBlock(Minecraft mc, BlockPos pos, Direction face) {
        LocalPlayer p = mc.player;
        if (p == null || pos == null) return false;
        HitResult own = p.pick(p.blockInteractionRange(), 1.0F, false);
        if (!(own instanceof BlockHitResult ob) || ob.getType() != HitResult.Type.BLOCK
                || !ob.getBlockPos().equals(pos) || (face != null && ob.getDirection() != face)) return false;
        return mc.hitResult instanceof BlockHitResult mb
                && mb.getType() == HitResult.Type.BLOCK
                && mb.getBlockPos().equals(pos)
                && (face == null || mb.getDirection() == face);
    }

    private void endDeposit(Minecraft mc, int cooldownTicks) {
        releaseDepKeys(mc);
        releaseAttack(mc);
        breakBlock = null;
        wantAttack = false;
        dep = Dep.NONE;
        depositCooldownUntil = clock + cooldownTicks;
    }

    private void abortDeposit(Minecraft mc, String why) {
        notice(mc, why);
        endDeposit(mc, 600);
    }

    private void tickDeposit(Minecraft mc, LocalPlayer player, ClientLevel level, Config cfg) {
        if (++depTicks > 700) {
            abortDeposit(mc, "deposit timed out.");
            return;
        }
        if (depWait > 0) {
            depWait--;
            return;
        }
        switch (dep) {
            case PLACE -> placeChest(mc, player, level);
            case OPEN -> openChest(mc, player, level, cfg);
            case RETRIEVE -> retrieveChest(mc, player, level, cfg);
            case TRANSFER -> afterTransfer(mc, cfg); // container is gone (we closed it, or you did)
            default -> { }
        }
    }

    private void placeChest(Minecraft mc, LocalPlayer player, ClientLevel level) {
        if (level.getBlockState(chestPos).is(Blocks.ENDER_CHEST)) { // it's down
            releaseDepKeys(mc);
            chestOpenAim = new Vec3(chestPos.getX() + 0.5 + jit(0.3), chestPos.getY() + 0.45 + jit(0.2),
                    chestPos.getZ() + 0.5 + jit(0.3));
            dep = Dep.OPEN;
            depTicks = 0;
            depWait = 8 + rng.nextInt(10); // look at it for a moment before opening
            openTries = 0;
            return;
        }
        int slot0 = findHotbarSlot(player, Items.ENDER_CHEST);
        if (slot0 < 0) {
            abortDeposit(mc, "lost the ender chest from the hotbar.");
            return;
        }
        if (!spotOk(level, player, chestFloor) || placeTries > 6) {
            abortDeposit(mc, "couldn't place the ender chest.");
            return;
        }
        mc.options.keyShift.setDown(true); // sneak so the right-click places instead of interacting
        depSneak = true;
        if (!ensureSlot(player, slot0 + 1)) return;
        if (!hitsBlock(mc, chestFloor, Direction.UP)) {
            rotateToward(player, player.getEyePosition(), chestAim);
            return;
        }
        mc.options.keyUse.setDown(true);
        useTapPending = true;
        placeTries++;
        depWait = 3 + rng.nextInt(3);
    }

    private void openChest(Minecraft mc, LocalPlayer player, ClientLevel level, Config cfg) {
        if (!level.getBlockState(chestPos).is(Blocks.ENDER_CHEST)) {
            abortDeposit(mc, "the ender chest disappeared.");
            return;
        }
        if (depSneak) { // sneaking would block opening it
            mc.options.keyShift.setDown(false);
            depSneak = false;
        }
        if (!ensureSlot(player, cfg.pickaxeSlot)) return; // hold something that can't place or use
        if (!hitsBlock(mc, chestPos, null)) {
            rotateToward(player, player.getEyePosition(), chestOpenAim);
            return;
        }
        if (++openTries > 4) {
            abortDeposit(mc, "couldn't open the ender chest.");
            return;
        }
        mc.options.keyUse.setDown(true);
        useTapPending = true;
        depWait = 10 + rng.nextInt(6); // wait for the screen
    }

    private void enterTransfer() {
        dep = Dep.TRANSFER;
        depTicks = 0;
        transferWait = 8 + rng.nextInt(10);
        lastMovedSlot = -1;
        lastMovedCount = -1;
        stuckMoves = 0;
        finishing = false;
    }

    /** Container screen is open: shift-click one anchor stack at a time with human-ish pauses. */
    private void tickTransfer(Minecraft mc, Config cfg) {
        LocalPlayer p = mc.player;
        if (++depTicks > 1500) {
            finishTransfer(mc, cfg);
            return;
        }
        if (transferWait > 0) {
            transferWait--;
            return;
        }
        AbstractContainerMenu menu = p.containerMenu;
        int found = -1;
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot sl = menu.slots.get(i);
            if (sl.container == p.getInventory() && sl.getItem().is(Items.RESPAWN_ANCHOR)) {
                found = i;
                break;
            }
        }
        if (found < 0) {
            finishTransfer(mc, cfg);
            return;
        }
        int cnt = menu.slots.get(found).getItem().getCount();
        if (found == lastMovedSlot && cnt == lastMovedCount) {
            if (++stuckMoves >= 2) { // chest is full
                finishTransfer(mc, cfg);
                return;
            }
        } else {
            stuckMoves = 0;
        }
        finishing = false;
        lastMovedSlot = found;
        lastMovedCount = cnt;
        mc.gameMode.handleContainerInput(menu.containerId, found, 0, ContainerInput.QUICK_MOVE, p);
        transferWait = 5 + rng.nextInt(8);
        if (rng.nextInt(10) == 0) transferWait += 8 + rng.nextInt(18); // the odd longer pause
    }

    private void finishTransfer(Minecraft mc, Config cfg) {
        if (!finishing) { // look over the chest for a moment before closing it
            finishing = true;
            transferWait = 6 + rng.nextInt(10);
            return;
        }
        Screen screen = mc.gui.screen();
        if (screen != null) screen.onClose();
        afterTransfer(mc, cfg);
    }

    private void afterTransfer(Minecraft mc, Config cfg) {
        if (cfg.retrieveChest && chestPos != null) {
            dep = Dep.RETRIEVE;
            depTicks = 0;
            depWait = 8 + rng.nextInt(10);
        } else {
            endDeposit(mc, 40);
        }
    }

    /** Mine the ender chest back (Silk Touch) and let the pickup logic collect it. */
    private void retrieveChest(Minecraft mc, LocalPlayer player, ClientLevel level, Config cfg) {
        if (!level.getBlockState(chestPos).is(Blocks.ENDER_CHEST)) { // gone: broken
            chestPickupUntil = clock + 300;
            lastBreakTick = clock;
            itemScanClock = 1000;
            endDeposit(mc, 60);
            return;
        }
        if (!ensureSlot(player, cfg.pickaxeSlot)) return;
        breakBlock = chestPos;
        if (!hitsBlock(mc, chestPos, null)) {
            wantAttack = false;
            rotateToward(player, player.getEyePosition(), chestOpenAim);
            return;
        }
        wantAttack = true;
    }


    // ---------------------------------------------------------------- health and totem guard

    private void sendChatCommand(Minecraft mc, String text) {
        ClientPacketListener conn = mc.getConnection();
        if (conn != null && text != null && !text.isBlank()) conn.sendChat(text);
    }

    private void cancelBaritone(Minecraft mc, Config cfg) {
        if (!cfg.baritoneGoto || clock - lastCancelTick < 100) return;
        lastCancelTick = clock;
        sendChatCommand(mc, cfg.baritoneCancelCommand);
    }

    private void beginRecovery(Minecraft mc, LocalPlayer player, Config cfg) {
        recovering = true;
        recTicks = 0;
        clearTarget(mc);
        stopWalking(mc);
        if (pickup != null) endPickup(mc);
        if (dep != Dep.NONE && dep != Dep.TRANSFER) endDeposit(mc, 0);
        cancelBaritone(mc, cfg);
        eatPending = 40;                  // "until full": tickEating stops by itself once the hunger bar is full
        eatDelay = 10 + rng.nextInt(10);  // Baritone needs a moment to stop, and so would a person
        notice(mc, "low health, Baritone cancelled, eating.");
    }

    /** True while recovery owns the tick (eating, or standing still while health regenerates). */
    private boolean tickRecovery(Minecraft mc, LocalPlayer player, Config cfg) {
        if (eatPending > 0) return true;
        if (player.getHealth() >= player.getMaxHealth()) {
            endRecovery(mc, true);
            return false;
        }
        recTicks++;
        if (recTicks % 200 == 0 && player.canEat(false)) { // still hungry: try the food again
            eatPending = 10;
            eatDelay = 5 + rng.nextInt(10);
            return true;
        }
        if (recTicks > 6000) {
            endRecovery(mc, false);
            notice(mc, "health isn't refilling, carrying on.");
            return false;
        }
        return true;
    }

    private void endRecovery(Minecraft mc, boolean success) {
        recovering = false;
        eatPending = 0;
        if (success) {
            // let the normal idle logic send the goto again as soon as there is nothing left to mine
            minedSinceGoto = Math.max(1, minedSinceGoto);
            lastBreakTick = clock - 100;
            notice(mc, "health restored, resuming.");
        } else {
            recoverCooldownUntil = clock + 600;
        }
    }

    private int spareTotems(LocalPlayer p) {
        int n = 0;
        Inventory inv = p.getInventory();
        for (int i = 0; i < 36; i++) {
            if (inv.getItem(i).is(Items.TOTEM_OF_UNDYING)) n += inv.getItem(i).getCount();
        }
        return n;
    }

    /** A pop looks like: offhand totem vanishes with no screen open, and health drops to ~1. */
    private void trackTotem(Minecraft mc, Config cfg) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || !cfg.totemGuard) {
            offInit = false;
            return;
        }
        boolean has = p.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
        if (offInit && lastOffTotem && !has && mc.gui.screen() == null && tot == Tot.NONE) {
            popCandidateUntil = clock + 10;
        }
        lastOffTotem = has;
        offInit = true;
        if (popCandidateUntil >= 0) {
            if (clock > popCandidateUntil) {
                popCandidateUntil = -1;
            } else if (!has && p.getHealth() <= 2.0F && tot == Tot.NONE) {
                popCandidateUntil = -1;
                onTotemPop(mc, p, cfg);
            }
        }
    }

    private void onTotemPop(Minecraft mc, LocalPlayer p, Config cfg) {
        notice(mc, "totem popped!");
        cancelBaritone(mc, cfg);
        clearTarget(mc);
        stopWalking(mc);
        stopEating(mc);
        if (pickup != null) endPickup(mc);
        if (dep != Dep.NONE && dep != Dep.TRANSFER) endDeposit(mc, 0);
        if (spareTotems(p) == 0) {
            notice(mc, "no spare totem in your inventory.");
            return;
        }
        tot = Tot.OPENING;
        totTicks = 0;
        totWait = 3 + rng.nextInt(6); // a beat before reaching for the inventory
    }

    /** Opens the inventory, waits for AutoTotem to fill the offhand, then closes it. True while it owns the tick. */
    private boolean tickTotem(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) {
            tot = Tot.NONE;
            return false;
        }
        boolean has = p.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
        switch (tot) {
            case OPENING -> {
                if (has) { // AutoTotem was already quick enough
                    tot = Tot.NONE;
                    return false;
                }
                if (totWait-- > 0) return true;
                mc.gui.setScreen(new InventoryScreen(p));
                tot = Tot.WAIT;
                totTicks = 0;
            }
            case WAIT -> {
                if (!(mc.gui.screen() instanceof InventoryScreen)) { // you closed it yourself
                    tot = Tot.NONE;
                    return false;
                }
                if (has) {
                    tot = Tot.CLOSING;
                    totWait = 4 + rng.nextInt(6);
                } else if (++totTicks > 400) {
                    notice(mc, "no totem reached your offhand, closing the inventory.");
                    tot = Tot.CLOSING;
                    totWait = 4 + rng.nextInt(6);
                }
            }
            case CLOSING -> {
                if (totWait-- > 0) return true;
                Screen s = mc.gui.screen();
                if (s instanceof InventoryScreen) s.onClose();
                tot = Tot.NONE;
                return false;
            }
            default -> {
                return false;
            }
        }
        return true;
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
        releaseDepKeys(mc);
        if (dep != Dep.NONE && dep != Dep.TRANSFER) dep = Dep.NONE;
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
