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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
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
    private int lastBreakTick = -100000;

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

    // rain trident state
    private enum Tri { NONE, PREP, SWAP, AIM, CHARGE, LAUNCH, FLIGHT }
    private Tri tri = Tri.NONE;
    private BlockPos triTarget;
    private Vec3 triAim;
    private int triTicks;
    private int triWait;
    private int triHoldTicks;
    private int triChargeTicks;
    private int triLaunchWait;
    private boolean triUseDown;
    private boolean triHold;              // a launch is possible, so don't hand over to Baritone
    private int triCooldownUntil;
    private int triFailUntil;
    private final List<BlockPos> farFound = new ArrayList<>();   // anchors seen anywhere in render distance
    private List<int[]> farOrder;                                  // chunks to scan this pass, nearest first
    private int farIndex;

    // ---- navigation arbiter, RTP, food, refills, water, misc (v2)
    public static volatile String activity = "Starting";
    public static volatile int invAnchors;
    private boolean baritoneActive;            // true while a goto WE sent may still be running
    private boolean baritoneForItem;           // that goto was aimed at an unreachable dropped anchor
    private int lastGotoTick = -1000;
    private int navQuietUntil;
    private Vec3 progressPos;
    private int progressTick;
    private int lastMonitorTick = -100;
    private double bestNearDist = Double.MAX_VALUE;
    private final Deque<Integer> restartTicks = new ArrayDeque<>();
    private int pendingGotoAt = -1;
    private String pendingGotoWhy = "";
    private boolean navHardFail;
    private int navHardFailTick;
    private int softFails;
    private int softFailSince;
    private ItemEntity assistItem;
    private int assistStart;
    private boolean itemAssistFailed;
    private final Set<Integer> assisted = new HashSet<>();

    private enum Rtp { NONE, SENT }
    private Rtp rtp = Rtp.NONE;
    private int rtpSentTick;
    private Vec3 rtpFrom;
    private Object rtpLevel;
    private int rtpCooldownUntil;
    private int rtpGraceUntil;
    private String rtpPendingReason;
    private int rtpPendingSince;
    private boolean rtpMenuClicked;
    private int rtpMenuWait;
    private final Deque<Integer> rtpTimes = new ArrayDeque<>();
    private int farPasses;
    private int passFound;
    private int farBudget = 120000;
    private int noAnchorSince = -1;
    private int profitSince = -1;

    private boolean foodBreak;
    private boolean foodResumeGoto;
    private boolean eatFailed;
    private int eatToLevel;
    private int hungerCooldownUntil;
    private net.minecraft.world.item.Item foodItem;

    private enum Refill { NONE, OPENING, CLICK, VERIFY, CLOSING }
    private Refill refill = Refill.NONE;
    private boolean refillFood;
    private int refillWait;
    private int refillTicks;
    private int refillDest;
    private int refillCooldownUntil;

    private int lastPickSwapTick = -1000;
    private final Deque<Integer> pickSwaps = new ArrayDeque<>();
    private int swapBackoffUntil;
    private int targetAge;
    private int outOfRangeTicks;

    private int floodSteps;
    private double floodBaseY = Double.NaN;
    private int floodColX;
    private int floodColZ;
    private int floodLastStep;

    private int depositRetryAt;
    private int depositStartCount;
    private int totStable;
    private boolean totTimedOut;

    private int triStall;
    private double triLastDist = Double.MAX_VALUE;
    private BlockPos triLastTarget;
    private int triHops;

    private int clusterCacheTick = -100;
    private boolean clusterCache;

    private enum Wat { NONE, SWIM, LAND }
    private Wat wat = Wat.NONE;
    private BlockPos watTarget;
    private Vec3 landTarget;
    private int watTicks;
    private int watClose;
    private int landFailUntil;
    private Vec3 swimLastPos;
    private int swimStuck;
    private int swimTick;
    private boolean swimKeysDown;
    private boolean breathing;

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

    /** End of tick: every decision is made here, in strict priority order (numbered below). */
    public void endTick(Minecraft mc) {
        Config cfg = Config.get();
        while (RespawnAnchorBreakerClient.toggleKey.consumeClick()) {
            cfg.enabled = !cfg.enabled;
            Config.save();
            ActivityLog.important("System", "Respawn Anchor Breaker " + (cfg.enabled ? "enabled." : "disabled."));
        }

        clock++;
        if (mc.player != null) {
            for (String line : ActivityLog.drainChat()) mc.player.sendSystemMessage(Component.literal(line));
            if (clock % 20 == 0) invAnchors = countAnchors(mc.player);
        }
        Market.tick(cfg);
        if (clock % 6000 == 0) Config.save();
        activity = computeActivity();
        if (useTapPending) { // a use "click" is a single-tick key press
            mc.options.keyUse.setDown(false);
            useTapPending = false;
        }
        if (mc.player != null && mc.level != null) ChatWatcher.poll(mc, this::onChatLine);

        // 1. things that own the screen or the inventory
        trackTotem(mc, cfg);
        if (tot != Tot.NONE && tickTotem(mc, cfg)) return;
        if (refill != Refill.NONE && mc.player != null && tickRefill(mc, cfg)) return;
        if (rtp != Rtp.NONE && tickRtp(mc, cfg)) return;
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

        // 2. navigation reports from Baritone's chat, delayed restarts, and the RTP triggers
        tickNavEvents(mc, player, cfg);
        checkRtpTriggers(mc, player, level, cfg);
        if (rtp != Rtp.NONE) return;

        // 3. safety: drowning, then low health
        if (cfg.waterMining && player.isUnderWater()
                && (player.getAirSupply() < 90 || (breathing && player.getAirSupply() < 250))) {
            breathe(mc, player);
            return;
        } else if (breathing) {
            breathing = false;
            releaseSwimKeys(mc);
        }
        if (cfg.healthGuard && !recovering && clock >= recoverCooldownUntil && !player.isCreative()
                && player.getHealth() < cfg.lowHealthHp) {
            beginRecovery(mc, player, cfg);
        }

        // 4. hunger: eat before it stops us sprinting (pauses Baritone, resumes it afterwards)
        if (cfg.autoEat && cfg.hungerEat && !recovering && !foodBreak && !eating && eatPending == 0 && !wantAttack
                && clock >= hungerCooldownUntil && !player.isCreative()
                && player.getFoodData().getFoodLevel() <= cfg.hungerThreshold) {
            beginFoodBreak(mc, player, cfg);
        }

        // 5. dropped anchors: collect them (but a cluster is cleared first)
        if (pickup != null && !pickupStillValid(player, cfg)) {
            endPickup(mc);
        }
        if (pickup == null && !recovering && !foodBreak && (cfg.autoPickup || clock < chestPickupUntil) && !wantAttack
                && !eating && ++itemScanClock >= 5) {
            itemScanClock = 0;
            ItemEntity found = findDroppedAnchor(level, player, PICKUP_RADIUS);
            if (found != null && clusterBlocked(level, player, cfg)) {
                ActivityLog.infoEvery("cluster-hold", 20000, "Anchor",
                        "Prioritizing the nearby anchor cluster. Drops will be collected afterwards.");
                found = null;
            }
            if (found != null) {
                clearTarget(mc);
                stopEating(mc);
                if (baritoneForItem) navCancel(mc, cfg, "Close enough to collect the drop directly. Stopping Baritone.");
                pickup = found;
                pickupTicks = 0;
                waitTicks = 0;
                stuckTicks = 0;
                lastPos = null;
                reactionTicks = 2 + rng.nextInt(4);
                ActivityLog.info("Mining", "Collecting a dropped anchor.");
            }
        }
        if (pickup != null) {
            runPickup(mc, player, level);
            return;
        }
        stopWalking(mc);

        // 6. eating (after anchors, hunger or healing); never in the middle of a cluster
        boolean eatingAllowed = recovering || foodBreak || !clusterBlocked(level, player, cfg);
        if ((cfg.autoEat || recovering || foodBreak) && eatPending > 0 && eatDelay == 0 && !wantAttack && eatingAllowed) {
            if (tickEating(mc, player, level, cfg)) return;
        } else if (eating || eatKeysDown) {
            stopEating(mc);
        }
        if (foodBreak && eatPending == 0 && !eating && eatPause == 0) endFoodBreak(mc, cfg);
        if (recovering && tickRecovery(mc, player, cfg)) return;
        if (foodBreak && eatPending > 0) return; // about to eat: stand still

        // 7. hotbar refills (cobblestone, food) while nothing else is going on
        if (refill == Refill.NONE && clock >= refillCooldownUntil && clock % 20 == 0 && quietForRefill()) {
            checkRefill(mc, player, cfg);
        }

        // 8. deposit anchors into an ender chest
        if (dep == Dep.NONE && cfg.depositAnchors && clock >= depositCooldownUntil && !wantAttack
                && eatPending == 0 && ++anchorCountTimer >= 10) {
            anchorCountTimer = 0;
            if (countAnchors(player) >= Math.max(cfg.depositThreshold, depositRetryAt) && playerStill(player)) {
                startDeposit(mc, player, level, cfg);
            }
        }
        if (dep != Dep.NONE) {
            tickDeposit(mc, player, level, cfg);
            return;
        }

        // 9. rain trident (owns the tick while launching)
        if (tri != Tri.NONE) {
            tickTrident(mc, player, level, cfg);
            return;
        }

        // 10. anchor breaking
        if (target != null && !stillValid(player, level)) {
            if (ticksOnTarget > 0 && !level.getBlockState(target).is(Blocks.RESPAWN_ANCHOR)) {
                onAnchorBroken(cfg);
            } else {
                ActivityLog.infoEvery("target-drop", 10000, "Mining", "Anchor target dropped (out of range or no longer there).");
            }
            clearTarget(mc);
        }
        double aimMax = Math.min(cfg.aimDistance, player.blockInteractionRange() - 0.05);

        if (target == null) {
            tickFarScan(mc, level, player, cfg);
            tickNavMonitor(mc, player, cfg);
            tickFloodWatch(mc, player, level, cfg);
            tickItemAssist(mc, cfg);
            if (++ticksSinceScan >= cfg.scanIntervalTicks) {
                ticksSinceScan = 0;
                acquire(player, level, aimMax);
                if (target != null) {
                    wat = Wat.NONE;
                    releaseSwimKeys(mc);
                    int near = anchorsNear(level, player, cfg.clusterRadius);
                    ActivityLog.infoEvery("anchor-found", 8000, "Anchor", near > 1
                            ? "Found " + near + " anchors within the current area. Prioritizing the nearby cluster."
                            : "Found an anchor in reach.");
                } else if (!waterIdle(mc, player, level, cfg)) {
                    tridentIdle(mc, player, level, cfg);
                    if (tri == Tri.NONE && !triHold) maybeSendGoto(mc, cfg, level, player);
                }
            }
            if (target == null) {
                wantAttack = false;
                if (wat != Wat.NONE) tickWater(mc, player, level, cfg);
                return;
            }
        }

        Vec3 eye = player.getEyePosition();
        double reach = player.blockInteractionRange();
        targetAge++;

        if (clock % 10 == 0 && resolveBreakBlock(level, player, eye, aimPoint, target) == null) {
            Vec3 np = findAimPoint(level, player, target, reach);
            if (np == null) {
                ignore(target, 200);
                ActivityLog.warn("Mining", "The view of this anchor is blocked. Skipping it for now.");
                clearTarget(mc);
                return;
            }
            aimPoint = np;
        }

        // the block to mine right now: the anchor itself, or a snow layer covering it
        breakBlock = resolveBreakBlock(level, player, eye, aimPoint, target);
        boolean aimed = breakBlock != null && hitsTarget(mc);
        double dist = eye.distanceTo(aimPoint);

        // never keep staring at an anchor that can't be reached (high in the air, behind things)
        if (dist > aimMax + 0.4) {
            if (++outOfRangeTicks > 40) {
                ignore(target, 160);
                ActivityLog.warn("Mining", "Anchor is out of reach (" + String.format(Locale.ROOT, "%.1f", dist)
                        + " blocks). Skipping it instead of staring at it.");
                clearTarget(mc);
                return;
            }
        } else {
            outOfRangeTicks = 0;
        }
        if (ticksOnTarget == 0 && targetAge > 200) {
            ignore(target, 300);
            ActivityLog.warn("Mining", "Couldn't hit the anchor within 10 seconds. Skipping it for now.");
            clearTarget(mc);
            return;
        }

        if (reactionTicks > 0) {
            reactionTicks--;
        } else if (!aimed) {
            rotateToward(player, eye, aimPoint);
        }

        // Baritone must not fight us for the hotbar slot or for movement while we mine
        if (aimed && dist <= reach && baritoneActive) {
            navCancel(mc, cfg, "Anchor in reach. Pausing Baritone so mining and block placing don't conflict.");
        }
        boolean quiet = clock >= navQuietUntil;
        boolean slotReady = !aimed || !cfg.autoSwap || (quiet && ensurePickSlot(player, cfg));
        wantAttack = aimed && slotReady && quiet;

        if (wantAttack) {
            if (ticksOnTarget == 0) ActivityLog.infoEvery("mining", 5000, "Mining", "Breaking respawn anchor...");
            ticksOnTarget++;
            inReachNotAimed = 0;
            if (ticksOnTarget > 1200) {
                ignore(target, 400);
                ActivityLog.warn("Mining", "The anchor hasn't broken after 60 seconds. Skipping it.");
                clearTarget(mc);
            }
        } else if (!aimed) {
            if (dist <= reach) {
                if (++inReachNotAimed > 60) {
                    ignore(target, 200);
                    ActivityLog.warn("Mining", "Something blocks the crosshair. Skipping this anchor for now.");
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
        if (player.isInWater() || level.getBlockState(pickup.blockPosition()).getFluidState().is(FluidTags.WATER)) {
            // in water we swim to the drop ourselves (Baritone and plain walking are poor there)
            if (++pickupTicks > 600) {
                giveUpPickup(mc);
                return;
            }
            if (swimTo(mc, player, pickup.position().add(0, 0.2, 0))) {
                if (++waitTicks > 60) giveUpPickup(mc);
            }
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
        ItemEntity it = pickup;
        endPickup(mc);
        if (it == null) return;
        Config cfg = Config.get();
        LocalPlayer p = mc.player;
        if (cfg.itemAssist && cfg.baritoneGoto && p != null && !it.isRemoved() && !assisted.contains(it.getId())
                && !p.isInWater() && rtp == Rtp.NONE && it.position().distanceTo(p.position()) <= 48.0) {
            // We can't simply walk to it (gap, wall, ledge). Baritone can place and break blocks, so ask it to path there.
            if (assisted.size() > 200) assisted.clear();
            assisted.add(it.getId());
            String prefix = cfg.gotoCommand.trim().split("\s+")[0];
            BlockPos ip = it.blockPosition();
            sendChatCommand(mc, prefix + " " + ip.getX() + " " + ip.getY() + " " + ip.getZ());
            baritoneActive = true;
            baritoneForItem = true;
            assistItem = it;
            assistStart = clock;
            itemAssistFailed = false;
            lastGotoTick = clock;
            resetProgress(p);
            ignoredItems.put(it.getId(), clock + 200); // don't fight Baritone over it for a few seconds
            ActivityLog.info("Inventory", "Can't walk straight to a dropped anchor. Asking Baritone to path to it (it can place and break blocks).");
            return;
        }
        ignoredItems.put(it.getId(), clock + 600);
        ActivityLog.warnEvery("drop-skip", 30000, "Inventory", "Couldn't reach a dropped anchor. Skipping it for now.");
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
        releaseSwimKeys(mc);
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
                if (!st.getCollisionShape(level, bp).isEmpty() || !st.getFluidState().isEmpty()) {
                    ground = true; // solid ground, or water (a soft landing)
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
        Stats.recordMined();
        restartTicks.clear();
        noAnchorSince = -1;
        profitSince = -1;
        ActivityLog.info("Mining", "Anchor broken." + (cfg.autoPickup ? " Collecting drops." : ""));
        if (cfg.autoEat && cfg.eatAfterAnchor) {
            eatPending = cfg.eatCount;
            eatToLevel = 0;
            eatDelay = 12; // give the drop time to appear and be picked up first
        }
    }

    /** Eats one piece at a time from the food slot. Returns true while eating owns this tick. */
    private boolean tickEating(Minecraft mc, LocalPlayer player, ClientLevel level, Config cfg) {
        if (eatPause > 0) {
            eatPause--;
            return true;
        }
        if (!eating && eatToLevel > 0 && player.getFoodData().getFoodLevel() >= eatToLevel) {
            eatPending = 0; // reached the target level
            return false;
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
            if (!useSeen) foodItem = player.getUseItem().getItem(); // remember what we eat, to refill it later
            useSeen = true;
        } else if (useSeen) { // bite finished
            stopEating(mc);
            eatPending--;
            eatPause = 6;
            return true;
        }
        if ((!useSeen && eatTicks > 12) || eatTicks > 120) { // not edible / can't eat / stuck
            eatPending = 0;
            eatFailed = true;
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

    /** Once everything nearby is mined, picked up and eaten (or a cluster is still waiting), send Baritone on. */
    private void maybeSendGoto(Minecraft mc, Config cfg, ClientLevel level, LocalPlayer player) {
        if (!cfg.baritoneGoto || rtp != Rtp.NONE) return;
        if (pickup != null || eatPending > 0 || eating || dep != Dep.NONE || recovering || foodBreak
                || refill != Refill.NONE || tot != Tot.NONE) return;
        if (baritoneActive) return; // already navigating; the stall monitor watches it
        if (cfg.waterMining && player.isInWater() && clock >= landFailUntil) {
            if (wat == Wat.NONE) { // Baritone is poor in water: get onto solid ground before sending it anywhere
                wat = Wat.LAND;
                watTicks = 0;
                landTarget = null;
                ActivityLog.info("Water", "Finished in the water. Swimming to solid ground before navigating again.");
            }
            return;
        }
        boolean cluster = clusterBlocked(level, player, cfg);
        if (!cluster && minedSinceGoto <= 0) return;
        if (!cluster && clock - lastBreakTick < 40) return;
        if (navGoto(mc, cfg, cluster
                ? "Anchors remain in this cluster. Heading to the next one."
                : "No anchors in reach. Baritone is traveling to the next anchor.")) {
            minedSinceGoto = 0;
        }
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
        String l = msg.toLowerCase(Locale.ROOT);
        String cat = l.contains("deposit") || l.contains("ender chest") ? "Deposit"
                : l.contains("health") ? "Health" : l.contains("totem") ? "Totem"
                : l.contains("trident") ? "Trident" : "System";
        ActivityLog.warn(cat, msg);
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
            skipDeposit(player, "there's no ender chest in the hotbar");
            return;
        }
        if (!findChestSpot(level, player)) {
            skipDeposit(player, "there's no good spot to place the ender chest here");
            return;
        }
        depositStartCount = countAnchors(player);
        ActivityLog.info("Deposit", "Carrying " + depositStartCount + " anchors (quota reached). Placing the ender chest.");
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

    /** A failed deposit is skipped until the quota is reached a second time, so it doesn't loop. */
    private void skipDeposit(LocalPlayer player, String why) {
        depositRetryAt = countAnchors(player) + Config.get().depositThreshold;
        depositCooldownUntil = clock + 200;
        ActivityLog.warn("Deposit", "Skipping the deposit: " + why + ". Will try again once the quota is reached a second time ("
                + depositRetryAt + " anchors).");
    }

    private void abortDeposit(Minecraft mc, String why) {
        depositRetryAt = invAnchors + Config.get().depositThreshold;
        ActivityLog.warn("Deposit", "Deposit failed: " + why + " Will try again once the quota is reached a second time ("
                + depositRetryAt + " anchors).");
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
        if (mc.player != null) {
            int moved = depositStartCount - countAnchors(mc.player);
            if (moved > 0) {
                Stats.recordDeposited(moved);
                depositRetryAt = 0;
                ActivityLog.info("Deposit", "Deposited " + moved + " anchors into the ender chest.");
            } else {
                ActivityLog.warn("Deposit", "No anchors were moved into the ender chest (it may be full).");
                depositRetryAt = invAnchors + cfg.depositThreshold;
            }
        }
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
        navCancel(mc, cfg, "Baritone navigation cancelled.");
    }

    private void beginRecovery(Minecraft mc, LocalPlayer player, Config cfg) {
        recovering = true;
        recTicks = 0;
        clearTarget(mc);
        stopWalking(mc);
        if (pickup != null) endPickup(mc);
        if (dep != Dep.NONE && dep != Dep.TRANSFER) endDeposit(mc, 0);
        if (tri != Tri.NONE && tri != Tri.FLIGHT) endTrident(mc, 0);
        if (wat != Wat.NONE) {
            wat = Wat.NONE;
            releaseSwimKeys(mc);
        }
        navCancel(mc, cfg, "Low health. Cancelling Baritone.");
        eatPending = 40;                  // "until full": tickEating stops by itself once the hunger bar is full
        eatToLevel = 20;
        eatFailed = false;
        eatDelay = 10 + rng.nextInt(10);  // Baritone needs a moment to stop, and so would a person
        ActivityLog.important("Health", "Health is low (" + String.format(Locale.ROOT, "%.1f", player.getHealth() / 2.0F)
                + " hearts). Cancelling Baritone, eating, then healing before resuming.");
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
            ActivityLog.warn("Health", "Health isn't refilling after 5 minutes. Carrying on anyway.");
            return false;
        }
        return true;
    }

    private void endRecovery(Minecraft mc, boolean success) {
        recovering = false;
        eatPending = 0;
        if (success) {
            eatToLevel = 0;
            pendingGotoAt = clock + 30; // the idle logic sends the goto again once nothing is left to mine
            pendingGotoWhy = "Health restored. Resuming navigation.";
            ActivityLog.important("Health", "Health restored. Resuming the anchor search.");
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
        ActivityLog.important("Totem", "Totem popped! Cancelling Baritone.");
        cancelBaritone(mc, cfg);
        clearTarget(mc);
        stopWalking(mc);
        stopEating(mc);
        if (pickup != null) endPickup(mc);
        if (dep != Dep.NONE && dep != Dep.TRANSFER) endDeposit(mc, 0);
        if (tri != Tri.NONE && tri != Tri.FLIGHT) endTrident(mc, 0);
        if (spareTotems(p) == 0) {
            ActivityLog.warn("Totem", "No spare totem in the inventory to swap in.");
            return;
        }
        tot = Tot.OPENING;
        totTicks = 0;
        totWait = 8 + rng.nextInt(10); // a beat before reaching for the inventory
    }

    /**
     * Opens the inventory, then waits until AutoTotem has really put a totem in the offhand: it must sit there for a
     * full second with nothing left on the cursor before the inventory is closed. True while it owns the tick.
     */
    private boolean tickTotem(Minecraft mc, Config cfg) {
        LocalPlayer p = mc.player;
        if (p == null) {
            tot = Tot.NONE;
            return false;
        }
        boolean has = p.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
        switch (tot) {
            case OPENING -> {
                if (has) { // AutoTotem was already quick enough
                    ActivityLog.info("Totem", "AutoTotem already refilled the offhand.");
                    tot = Tot.NONE;
                    return false;
                }
                if (totWait-- > 0) return true;
                mc.gui.setScreen(new InventoryScreen(p));
                ActivityLog.info("Totem", "Inventory opened. Waiting for AutoTotem to move a totem to the offhand.");
                tot = Tot.WAIT;
                totTicks = 0;
                totStable = 0;
                totTimedOut = false;
            }
            case WAIT -> {
                if (!(mc.gui.screen() instanceof InventoryScreen)) { // you closed it yourself
                    tot = Tot.NONE;
                    return false;
                }
                boolean carrying = !p.containerMenu.getCarried().isEmpty();
                totStable = (has && !carrying) ? totStable + 1 : 0;
                if (totStable >= 20) {
                    tot = Tot.CLOSING;
                    totWait = 20 + rng.nextInt(20);
                    ActivityLog.info("Totem", "Totem confirmed in the offhand. Closing the inventory shortly.");
                } else if (++totTicks > cfg.totemTimeoutSeconds * 20) {
                    ActivityLog.warn("Totem", "No totem reached the offhand within " + cfg.totemTimeoutSeconds
                            + " seconds. Closing the inventory.");
                    tot = Tot.CLOSING;
                    totTimedOut = true;
                    totWait = 20 + rng.nextInt(20);
                }
            }
            case CLOSING -> {
                if (totWait-- > 0) return true;
                if (!p.containerMenu.getCarried().isEmpty()) { // never close with something held on the cursor
                    totWait = 10;
                    return true;
                }
                if (!has && !totTimedOut) { // it left again: keep waiting instead of closing on a false alarm
                    tot = Tot.WAIT;
                    totStable = 0;
                    return true;
                }
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

    // ---------------------------------------------------------------- rain trident

    /**
     * Searches the loaded world out to your render distance for respawn anchors, nearest chunks first, with a cap on
     * block lookups per tick so it never causes a hitch. A finished pass starts over, so new anchors get noticed.
     */
    private void tickFarScan(Minecraft mc, ClientLevel level, LocalPlayer player, Config cfg) {
        if (farOrder == null) { // start a new pass
            int rd = Mth.clamp(mc.options.renderDistance().get(), 2, 32);
            BlockPos pp = player.blockPosition();
            int pcx = pp.getX() >> 4, pcz = pp.getZ() >> 4;
            farOrder = new ArrayList<>();
            for (int dx = -rd; dx <= rd; dx++) {
                for (int dz = -rd; dz <= rd; dz++) {
                    if (dx * dx + dz * dz <= rd * rd) farOrder.add(new int[]{pcx + dx, pcz + dz, dx * dx + dz * dz});
                }
            }
            farOrder.sort((p1, p2) -> Integer.compare(p1[2], p2[2]));
            farIndex = 0;
            farBudget = Mth.clamp((int) ((long) farOrder.size() * 256L * level.dimensionType().height() / 500L), 100_000, 300_000);
        }
        int minY = level.dimensionType().minY();
        int maxY = minY + level.dimensionType().height() - 1;
        int budget = farBudget;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        while (budget > 0 && farIndex < farOrder.size()) {
            int[] c = farOrder.get(farIndex++);
            if (!level.hasChunk(c[0], c[1])) continue;
            LevelChunk chunk = level.getChunk(c[0], c[1]);
            budget -= 256 * (maxY - minY + 1);
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int y = minY; y <= maxY; y++) {
                        m.set((c[0] << 4) + lx, y, (c[1] << 4) + lz);
                        if (chunk.getBlockState(m).is(Blocks.RESPAWN_ANCHOR)) {
                            BlockPos found = m.immutable();
                            passFound++;
                            if (!farFound.contains(found)) farFound.add(found);
                        }
                    }
                }
            }
        }
        if (farIndex >= farOrder.size()) { // pass finished
            farFound.removeIf(q -> !level.getBlockState(q).is(Blocks.RESPAWN_ANCHOR));
            farPasses++;
            if (passFound == 0 && farFound.isEmpty()) {
                if (noAnchorSince < 0) {
                    noAnchorSince = clock;
                    ActivityLog.info("Anchor", "No anchors found within render distance.");
                }
            } else {
                noAnchorSince = -1;
                if (!farFound.isEmpty()) {
                    Vec3 eye = player.getEyePosition();
                    double nearest = Double.MAX_VALUE;
                    for (BlockPos q : farFound) nearest = Math.min(nearest, eye.distanceTo(Vec3.atCenterOf(q)));
                    ActivityLog.infoEvery("far-found", 60000, "Anchor", "Found " + farFound.size()
                            + " anchors within render distance. Nearest is " + (int) nearest + " blocks away.");
                }
            }
            passFound = 0;
            farOrder = null;
        }
    }

    private boolean aimedAt(LocalPlayer p, Vec3 aim) {
        Vec3 want = aim.subtract(p.getEyePosition());
        if (want.lengthSqr() < 1.0E-6) return true;
        return p.getLookAngle().dot(want.normalize()) > 0.99985; // within about one degree
    }

    private boolean laneClear(ClientLevel level, LocalPlayer player, Vec3 eye, Vec3 aim, BlockPos anchor) {
        BlockHitResult hit = level.clip(new ClipContext(eye, aim, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit.getType() == HitResult.Type.MISS) return true;
        return resolveBreakBlock(level, player, eye, aim, anchor) != null;
    }

    /** Ground (or water) within maxDrop blocks below the point, and no lava, fire or magma on the way down. */
    private boolean landingSafe(ClientLevel level, Vec3 end, int maxDrop) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int ex = Mth.floor(end.x), ez = Mth.floor(end.z), ey = Mth.floor(end.y);
        for (int i = 0; i <= maxDrop + 8; i++) {
            m.set(ex, ey - i, ez);
            BlockState st = level.getBlockState(m);
            if (hazard(st)) return false;
            if (!st.getFluidState().isEmpty()) return true; // water: a soft landing
            if (!st.getCollisionShape(level, m).isEmpty()) return i <= maxDrop + 2;
        }
        return false;
    }

    /**
     * Where to point the launch: straight at the anchor if there's a clear line, otherwise along the best clear lane
     * toward it (tilting up and sideways a little to get over or around terrain), ending somewhere safe to land.
     */
    private Vec3 planLaunch(ClientLevel level, LocalPlayer player, BlockPos pos, Config cfg) {
        Vec3 eye = player.getEyePosition();
        Vec3 direct = findAimPoint(level, player, pos, Double.MAX_VALUE);
        if (direct != null) return direct;
        Vec3 to = Vec3.atCenterOf(pos).subtract(eye);
        double len = to.length();
        if (len < 1.0E-6) return null;
        Vec3 toN = to.scale(1.0 / len);
        double basePitch = Math.atan2(to.y, Math.hypot(to.x, to.z));
        double baseYaw = Math.atan2(to.z, to.x);
        double hop = cfg.tridentHop;
        double[] pitchUp = {0, 10, 20, 30, 40};
        double[] yawOff = {0, 15, -15, 30, -30};
        for (double pu : pitchUp) {
            for (double yo : yawOff) {
                double th = Mth.clamp(basePitch + Math.toRadians(pu), -1.0, 1.2);
                double ph = baseYaw + Math.toRadians(yo);
                Vec3 dir = new Vec3(Math.cos(th) * Math.cos(ph), Math.sin(th), Math.cos(th) * Math.sin(ph));
                if (dir.dot(toN) < 0.5) continue;
                Vec3 end = eye.add(dir.scale(hop));
                BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE,
                        ClipContext.Fluid.NONE, player));
                if (hit.getType() != HitResult.Type.MISS) continue;
                if (!landingSafe(level, eye.add(dir.scale(hop * 0.5)), cfg.maxDropBlocks)
                        || !landingSafe(level, end, cfg.maxDropBlocks)) continue;
                return end;
            }
        }
        return null;
    }

    /** While idle in rain: head for the closest known anchor that is out of reach, hop after hop. */
    private void tridentIdle(Minecraft mc, LocalPlayer player, ClientLevel level, Config cfg) {
        triHold = false;
        if (!cfg.tridentEnabled || !level.isRaining() || clock < triFailUntil) return;
        int slot0 = Mth.clamp(cfg.tridentSlot, 1, 9) - 1;
        if (!player.getInventory().getItem(slot0).is(Items.TRIDENT)) return;
        farFound.removeIf(q -> !level.getBlockState(q).is(Blocks.RESPAWN_ANCHOR));
        if (farFound.isEmpty() || !player.isInWaterOrRain()) return; // sheltered: let Baritone walk out

        Vec3 eye = player.getEyePosition();
        double aim = cfg.aimDistance;
        List<BlockPos> sorted = new ArrayList<>(farFound);
        sorted.removeIf(q -> ignoredUntil.containsKey(q));
        sorted.sort((a, b) -> Double.compare(
                eye.distanceToSqr(Vec3.atCenterOf(a)), eye.distanceToSqr(Vec3.atCenterOf(b))));
        int tried = 0;
        for (BlockPos pos : sorted) {
            double d = eye.distanceTo(Vec3.atCenterOf(pos));
            if (d <= aim) continue;              // normal mining handles these
            if (d > cfg.tridentRange) break;     // sorted by distance: everything further is out of range too
            if (tried++ >= 4) break;
            Vec3 plan = planLaunch(level, player, pos, cfg);
            if (plan == null) continue;
            triHold = true;
            if (clock < triCooldownUntil || recovering || foodBreak || tot != Tot.NONE || dep != Dep.NONE
                    || eatPending > 0 || rtp != Rtp.NONE || refill != Refill.NONE) return;
            if (player.getHealth() < cfg.tridentMinHealth) return;

            if (!pos.equals(triLastTarget)) {
                triLastTarget = pos;
                triHops = 0;
                triStall = 0;
                triLastDist = d;
                ActivityLog.info("Trident", "Raining. Heading for the closest anchor, " + (int) d + " blocks away.");
            } else if (d < triLastDist - 1.0) {
                triStall = 0;
                triLastDist = d;
            } else if (++triStall >= 3 || triHops >= 40) {
                ignore(pos, 1200);
                triLastTarget = null;
                ActivityLog.warn("Trident", "Three launches without getting closer. Skipping this anchor for a while.");
                return;
            }
            triHops++;
            triTarget = pos;
            triAim = plan;
            tri = Tri.PREP;
            triTicks = 0;
            triWait = 10 + rng.nextInt(14); // a beat to decide
            triHoldTicks = 11 + rng.nextInt(5); // riptide needs 10+ ticks of charge; people hold a little longer
            if (baritoneActive || clock - lastCancelTick > 100) navCancel(mc, cfg, "Baritone must be off while using the trident.");
            ActivityLog.info("Trident", "Launch " + triHops + " toward the closest anchor (" + (int) d + " blocks).");
            return;
        }
    }

    private void endTrident(Minecraft mc, int cooldownTicks) {
        if (triUseDown) {
            mc.options.keyUse.setDown(false);
            triUseDown = false;
        }
        tri = Tri.NONE;
        triCooldownUntil = clock + cooldownTicks;
    }

    private void failTrident(Minecraft mc, String why) {
        ActivityLog.warn("Trident", why);
        triFailUntil = clock + 3600; // leave the trident alone for a few minutes
        endTrident(mc, 0);
    }

    private void tickTrident(Minecraft mc, LocalPlayer player, ClientLevel level, Config cfg) {
        if (++triTicks > 500) {
            failTrident(mc, "The trident sequence timed out.");
            return;
        }
        if (triWait > 0) {
            triWait--;
            return;
        }
        boolean anchorThere = level.getBlockState(triTarget).is(Blocks.RESPAWN_ANCHOR);
        Vec3 eye = player.getEyePosition();
        switch (tri) {
            case PREP -> {
                if (!anchorThere) { endTrident(mc, 20); return; }
                if (!playerStill(player) && triTicks < 80) return; // let Baritone's momentum die down
                tri = Tri.SWAP;
            }
            case SWAP -> {
                if (!anchorThere) { endTrident(mc, 20); return; }
                if (ensureSlot(player, cfg.tridentSlot)) tri = Tri.AIM;
            }
            case AIM -> {
                if (!anchorThere) { endTrident(mc, 20); return; }
                if (!player.isInWaterOrRain()) { endTrident(mc, 200); return; }
                if (!laneClear(level, player, eye, triAim, triTarget)) {
                    Vec3 np = planLaunch(level, player, triTarget, cfg);
                    if (np == null) { endTrident(mc, 200); return; }
                    triAim = np;
                }
                if (aimedAt(player, triAim)) {
                    tri = Tri.CHARGE;
                    triChargeTicks = 0;
                    triWait = 2 + rng.nextInt(4);
                } else {
                    rotateToward(player, eye, triAim);
                }
            }
            case CHARGE -> {
                if (!anchorThere || !player.isInWaterOrRain()) { endTrident(mc, 100); return; }
                if (!aimedAt(player, triAim)) rotateToward(player, eye, triAim);
                mc.options.keyUse.setDown(true);
                triUseDown = true;
                triChargeTicks++;
                if (player.isUsingItem() && player.getTicksUsingItem() >= triHoldTicks) {
                    mc.options.keyUse.setDown(false); // letting go launches it
                    triUseDown = false;
                    tri = Tri.LAUNCH;
                    triLaunchWait = 0;
                } else if (triChargeTicks > 30 && !player.isUsingItem()) {
                    failTrident(mc, "The trident wouldn't charge.");
                }
            }
            case LAUNCH -> {
                if (player.isAutoSpinAttack()) {
                    tri = Tri.FLIGHT;
                } else if (++triLaunchWait > 10) {
                    failTrident(mc, "No launch: the trident needs Riptide, and rain or water.");
                }
            }
            case FLIGHT -> {
                if (!player.isAutoSpinAttack()) {
                    endTrident(mc, 25 + rng.nextInt(25));
                    ActivityLog.info("Trident", "Flight finished.");
                }
            }
            default -> { }
        }
    }

    // ---------------------------------------------------------------- state and navigation arbiter

    private String computeActivity() {
        if (!Config.get().enabled) return "Disabled";
        if (rtp != Rtp.NONE) return "RTP in progress";
        if (tot != Tot.NONE) return "Totem swap";
        if (recovering) return "Recovering health";
        if (foodBreak || eating) return "Eating";
        if (refill != Refill.NONE) return "Refilling hotbar";
        if (dep != Dep.NONE) return "Depositing anchors";
        if (tri != Tri.NONE) return "Trident launch";
        if (wat != Wat.NONE) return wat == Wat.SWIM ? "Swimming to anchor" : "Returning to land";
        if (pickup != null) return "Collecting drops";
        if (target != null) return wantAttack ? "Mining anchor" : "Aiming at anchor";
        if (baritoneForItem) return "Baritone fetching a drop";
        if (baritoneActive) return "Navigating (Baritone)";
        return "Searching for anchors";
    }

    /** Nothing time-critical is happening: safe to send or restart navigation commands. */
    private boolean navIdle() {
        return target == null && pickup == null && !eating && eatPending == 0 && dep == Dep.NONE && tri == Tri.NONE
                && !recovering && !foodBreak && tot == Tot.NONE && refill == Refill.NONE && rtp == Rtp.NONE
                && wat == Wat.NONE;
    }

    private boolean quietForRefill() {
        return navIdle() && !wantAttack && !breathing && !baritoneForItem;
    }

    private boolean navGoto(Minecraft mc, Config cfg, String reason) {
        if (!cfg.baritoneGoto || rtp != Rtp.NONE) return false; // no navigation commands during a teleport
        if (clock - lastGotoTick < 40) return false;            // rate limit
        sendChatCommand(mc, cfg.gotoCommand);
        baritoneActive = true;
        baritoneForItem = false;
        lastGotoTick = clock;
        if (mc.player != null) resetProgress(mc.player);
        ActivityLog.info("Navigation", reason);
        return true;
    }

    private void navCancel(Minecraft mc, Config cfg, String reason) {
        baritoneActive = false;
        baritoneForItem = false;
        if (!cfg.baritoneGoto || clock - lastCancelTick < 40) return;
        lastCancelTick = clock;
        sendChatCommand(mc, cfg.baritoneCancelCommand);
        navQuietUntil = clock + 10; // give Baritone a moment to let go of the controls
        ActivityLog.info("Navigation", reason);
    }

    private void resetProgress(LocalPlayer p) {
        progressPos = p.position();
        progressTick = clock;
        bestNearDist = Double.MAX_VALUE;
    }

    private double nearestKnownAnchorDist(LocalPlayer p) {
        if (farFound.isEmpty()) return -1;
        Vec3 eye = p.getEyePosition();
        double best = Double.MAX_VALUE;
        for (BlockPos q : farFound) best = Math.min(best, eye.distanceTo(Vec3.atCenterOf(q)));
        return best;
    }

    private static final String[] HARD_FAIL = {"no known locations", "canceling gettoblock", "cancelling gettoblock",
            "no path found", "no valid path", "unable to find any blocks"};
    private static final String[] SOFT_FAIL = {"unable to find any path", "unable to find a path", "blacklisting",
            "failed to compute", "path failed", "failed to path"};

    private static boolean containsAny(String t, String[] needles) {
        for (String n : needles) if (t.contains(n)) return true;
        return false;
    }

    /** Called for every new chat line (Baritone's included). Only Baritone-specific phrases count. */
    private void onChatLine(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        boolean hard = containsAny(t, HARD_FAIL);
        boolean soft = !hard && containsAny(t, SOFT_FAIL);
        if (!hard && !soft) return;
        if (baritoneForItem) { // that goto was for a dropped anchor: its failure is handled there, not as an RTP reason
            itemAssistFailed = true;
            return;
        }
        String shown = text.length() > 70 ? text.substring(0, 70) + "..." : text;
        ActivityLog.warn("Navigation", "Baritone reported: " + shown);
        if (hard) {
            navHardFail = true;
            navHardFailTick = clock;
        } else {
            if (clock - softFailSince > 2400) {
                softFails = 0;
                softFailSince = clock;
            }
            softFails++;
        }
    }

    private void tickNavEvents(Minecraft mc, LocalPlayer player, Config cfg) {
        if (navHardFail && clock - navHardFailTick > 600) navHardFail = false; // stale report
        if (!navIdle()) return;
        if (pendingGotoAt >= 0 && clock >= pendingGotoAt) {
            pendingGotoAt = -1;
            navGoto(mc, cfg, pendingGotoWhy);
        }
        boolean hard = navHardFail;
        boolean soft = softFails >= 3;
        if (!hard && !soft) return;
        navHardFail = false;
        softFails = 0;
        baritoneActive = false;
        ActivityLog.warn("Navigation", hard
                ? "Baritone says there are no anchors or paths it can use. Treating this as a navigation failure."
                : "Baritone keeps failing to path to anchors. Treating this as a navigation failure.");
        if (cfg.rtpEnabled && cfg.rtpOnNavFail) {
            requestRtp(mc, cfg, "Baritone navigation failure");
        } else {
            pendingGotoAt = clock + 200;
            pendingGotoWhy = "Retrying navigation after the failure.";
        }
    }

    /** Restarts a stalled goto. Skipped whenever we're busy (mining, eating...), so it never interrupts real work. */
    private void tickNavMonitor(Minecraft mc, LocalPlayer player, Config cfg) {
        if (clock - lastMonitorTick > 10) resetProgress(player); // we were busy: start the clock fresh
        lastMonitorTick = clock;
        if (!cfg.stuckRecovery || !baritoneActive || baritoneForItem || rtp != Rtp.NONE) {
            progressTick = clock;
            return;
        }
        if (progressPos == null) resetProgress(player);
        Vec3 pos = player.position();
        boolean progress = false;
        if (pos.distanceTo(progressPos) >= 3.0) { // it's actually travelling
            progress = true;
            progressPos = pos;
        }
        double near = nearestKnownAnchorDist(player);
        if (near >= 0 && near < bestNearDist - 1.0) { // or getting closer to the nearest anchor
            bestNearDist = near;
            progress = true;
        }
        if (progress) {
            progressTick = clock;
            return;
        }
        if (clock - progressTick < cfg.stuckSeconds * 20) return;

        while (!restartTicks.isEmpty() && clock - restartTicks.peekFirst() > 12000) restartTicks.pollFirst();
        restartTicks.addLast(clock);
        if (restartTicks.size() > cfg.navMaxRestarts && cfg.rtpEnabled && cfg.rtpOnNavFail) {
            ActivityLog.warn("Navigation", "Navigation has stalled " + restartTicks.size()
                    + " times in 10 minutes. Escalating to an RTP.");
            restartTicks.clear();
            requestRtp(mc, cfg, "Navigation keeps stalling");
        } else {
            ActivityLog.warn("Navigation", "No progress detected for " + cfg.stuckSeconds + " seconds. Restarting navigation.");
            navCancel(mc, cfg, "Cancelling the stalled navigation.");
            pendingGotoAt = clock + 40 + rng.nextInt(40);
            pendingGotoWhy = "Restarting navigation after a stall.";
        }
        resetProgress(player);
    }

    private void tickItemAssist(Minecraft mc, Config cfg) {
        if (!baritoneForItem) return;
        if (assistItem == null || assistItem.isRemoved()) {
            ActivityLog.info("Inventory", "The dropped anchor was collected (Baritone found a way to it).");
            endAssist(mc, cfg, true);
        } else if (itemAssistFailed || clock - assistStart > 1200) {
            ActivityLog.warn("Inventory", "Couldn't reach the dropped anchor even with Baritone. Skipping it.");
            ignoredItems.put(assistItem.getId(), clock + 6000);
            endAssist(mc, cfg, false);
        }
    }

    private void endAssist(Minecraft mc, Config cfg, boolean ok) {
        if (!ok) navCancel(mc, cfg, "Cancelling the failed fetch.");
        baritoneForItem = false;
        baritoneActive = false;
        assistItem = null;
        itemAssistFailed = false;
        minedSinceGoto = Math.max(1, minedSinceGoto);
        lastBreakTick = clock - 100;
    }

    // ---------------------------------------------------------------- RTP

    private void requestRtp(Minecraft mc, Config cfg, String reason) {
        if (!cfg.rtpEnabled || rtp != Rtp.NONE) return; // one at a time
        if (clock < rtpCooldownUntil) {
            ActivityLog.infoEvery("rtp-cooldown", 30000, "RTP", "RTP wanted (" + reason + ") but it is cooling down. Will do it when allowed.");
            if (rtpPendingReason == null) { rtpPendingReason = reason; rtpPendingSince = clock; }
            return;
        }
        while (!rtpTimes.isEmpty() && clock - rtpTimes.peekFirst() > 72000) rtpTimes.pollFirst();
        if (rtpTimes.size() >= cfg.rtpMaxPerHour) {
            ActivityLog.warnEvery("rtp-cap", 300000, "RTP", "Hourly RTP limit (" + cfg.rtpMaxPerHour + ") reached. Not teleporting (" + reason + ").");
            return;
        }
        if (tot != Tot.NONE || (mc.player != null && containerOpen(mc))) { // not in the middle of a swap
            if (rtpPendingReason == null) { rtpPendingReason = reason; rtpPendingSince = clock; }
            return;
        }
        startRtp(mc, cfg, reason);
    }

    private void startRtp(Minecraft mc, Config cfg, String reason) {
        LocalPlayer p = mc.player;
        if (p == null) return;
        ActivityLog.important("RTP", reason + ". Initiating RTP.");
        clearTarget(mc);
        stopEating(mc);
        stopWalking(mc);
        if (pickup != null) endPickup(mc);
        if (dep != Dep.NONE && dep != Dep.TRANSFER) endDeposit(mc, 0);
        if (tri != Tri.NONE && tri != Tri.FLIGHT) endTrident(mc, 0);
        wat = Wat.NONE;
        releaseSwimKeys(mc);
        recovering = false;
        foodBreak = false;
        eatPending = 0;
        eatToLevel = 0;
        navCancel(mc, cfg, "Cancelling Baritone for the teleport.");
        rtp = Rtp.SENT;
        rtpSentTick = clock;
        rtpFrom = p.position();
        rtpLevel = mc.level;
        rtpCooldownUntil = clock + cfg.rtpCooldownSeconds * 20;
        rtpTimes.addLast(clock);
        rtpMenuClicked = false;
        rtpMenuWait = 0;
        rtpPendingReason = null;
        ClientPacketListener conn = mc.getConnection();
        if (conn != null) {
            String c = cfg.rtpCommand.trim();
            if (c.startsWith("/")) conn.sendCommand(c.substring(1)); else conn.sendChat(c);
        }
    }

    /** True while an RTP owns the tick: waits for the teleport (or the menu), detects completion, handles timeouts. */
    private boolean tickRtp(Minecraft mc, Config cfg) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return true; // loading screen or world switch: just wait
        boolean moved = rtpFrom != null && p.position().distanceTo(rtpFrom) > 32.0;
        boolean levelChanged = rtpLevel != null && mc.level != rtpLevel;
        if (moved || levelChanged) {
            onRtpSuccess(cfg);
            return false;
        }
        if (containerOpen(mc)) { // some servers show a region menu after /rtp
            if (cfg.rtpMenuSlot >= 0 && !rtpMenuClicked && ++rtpMenuWait > 12 + rng.nextInt(10)) {
                mc.gameMode.handleContainerInput(p.containerMenu.containerId, cfg.rtpMenuSlot, 0, ContainerInput.PICKUP, p);
                rtpMenuClicked = true;
                ActivityLog.info("RTP", "Clicked menu slot " + cfg.rtpMenuSlot + ".");
            }
            return true;
        }
        if (clock - rtpSentTick > 600) {
            ActivityLog.warn("RTP", "The teleport didn't happen within 30 seconds. It will be retried after the cooldown if still needed.");
            rtp = Rtp.NONE;
            return false;
        }
        return true;
    }

    private void onRtpSuccess(Config cfg) {
        rtp = Rtp.NONE;
        farFound.clear();
        farOrder = null;
        farIndex = 0;
        farPasses = 0;
        passFound = 0;
        noAnchorSince = -1;
        profitSince = -1;
        ignoredUntil.clear();
        ignoredItems.clear();
        assisted.clear();
        target = null;
        aimPoint = null;
        breakBlock = null;
        pickup = null;
        baritoneActive = false;
        baritoneForItem = false;
        progressPos = null;
        restartTicks.clear();
        navHardFail = false;
        softFails = 0;
        triLastTarget = null;
        triCooldownUntil = clock + 40;
        rtpGraceUntil = clock + 300; // 15 s for the new chunks to load before the no-anchor timer counts
        ticksSinceScan = 1000;
        itemScanClock = 1000;
        pendingGotoAt = clock + 200; // by then the scan has had a look; if nothing is in reach, Baritone heads out
        pendingGotoWhy = "Teleport complete. Heading for the closest anchor.";
        ActivityLog.important("RTP", "Teleport successful. Resuming anchor search.");
    }

    private void checkRtpTriggers(Minecraft mc, LocalPlayer player, ClientLevel level, Config cfg) {
        if (!cfg.rtpEnabled || rtp != Rtp.NONE) return;
        if (rtpPendingReason != null) {
            String r = rtpPendingReason;
            rtpPendingReason = null;
            if (clock - rtpPendingSince <= 3600) requestRtp(mc, cfg, r);
            if (rtp != Rtp.NONE) return;
        }
        // C. another player close by
        if (cfg.rtpOnPlayer && clock % 10 == 0 && nearbyPlayer(mc, level, player, cfg.rtpPlayerRadius)) {
            ActivityLog.warnEvery("rtp-player", 20000, "RTP", "Nearby player detected within " + cfg.rtpPlayerRadius + " blocks.");
            requestRtp(mc, cfg, "A player is within " + cfg.rtpPlayerRadius + " blocks");
            if (rtp != Rtp.NONE) return;
        }
        // A. no anchors within render distance for a while
        if (cfg.rtpNoAnchor && noAnchorSince >= 0 && clock >= rtpGraceUntil && navIdle()
                && clock - noAnchorSince >= cfg.rtpNoAnchorSeconds * 20) {
            noAnchorSince = clock; // restart the timer so a refusal doesn't re-fire every tick
            requestRtp(mc, cfg, "No anchors detected within render distance for " + cfg.rtpNoAnchorSeconds + " seconds");
            if (rtp != Rtp.NONE) return;
        }
        // E. maximise profit: a very long walk to a lone anchor in a sparse area
        if (cfg.profitRtp) checkProfit(mc, player, level, cfg);
    }

    private boolean nearbyPlayer(Minecraft mc, ClientLevel level, LocalPlayer self, double radius) {
        ClientPacketListener conn = mc.getConnection();
        if (conn == null) return false;
        for (Player pl : level.players()) {
            if (pl == self || pl.isSpectator()) continue;
            if (conn.getPlayerInfo(pl.getUUID()) == null) continue; // not in the tab list: an NPC or a ghost
            if (pl.position().distanceTo(self.position()) <= radius) return true;
        }
        return false;
    }

    /**
     * Stringent on purpose: only when walking (not tridenting), the nearest anchor is very far, and the known anchors
     * don't form 2 clusters of 3+ or 3 clusters of 2+. It has to stay that way for a full minute.
     */
    private void checkProfit(Minecraft mc, LocalPlayer player, ClientLevel level, Config cfg) {
        if (clock % 40 != 0) return;
        if (farFound.isEmpty() || farPasses < 2 || !navIdle() || triHold
                || (cfg.tridentEnabled && level.isRaining() && player.isInWaterOrRain())) {
            profitSince = -1;
            return;
        }
        Vec3 eye = player.getEyePosition();
        double nearest = nearestKnownAnchorDist(player);
        if (nearest < cfg.profitFarDistance || richArea(cfg)) {
            profitSince = -1;
            return;
        }
        if (profitSince < 0) {
            profitSince = clock;
            ActivityLog.info("RTP", "The nearest anchor is " + (int) nearest
                    + " blocks away in a sparse area. Watching for a minute before deciding to RTP.");
            return;
        }
        if (clock - profitSince >= 1200) {
            profitSince = -1;
            requestRtp(mc, cfg, "Maximising profit: the nearest anchor is " + (int) nearest + " blocks away and the area is sparse");
        }
    }

    private boolean richArea(Config cfg) {
        int n = Math.min(farFound.size(), 300);
        if (n == 0) return false;
        List<BlockPos> pts = farFound.subList(0, n);
        int[] comp = new int[n];
        java.util.Arrays.fill(comp, -1);
        double r2 = (double) cfg.clusterRadius * cfg.clusterRadius;
        int big = 0;
        int pairs = 0;
        int c = 0;
        for (int i = 0; i < n; i++) {
            if (comp[i] >= 0) continue;
            int size = 0;
            ArrayDeque<Integer> q = new ArrayDeque<>();
            q.add(i);
            comp[i] = c;
            while (!q.isEmpty()) {
                int a = q.poll();
                size++;
                for (int j = 0; j < n; j++) {
                    if (comp[j] < 0 && pts.get(a).distSqr(pts.get(j)) <= r2) {
                        comp[j] = c;
                        q.add(j);
                    }
                }
            }
            if (size >= 3) big++;
            if (size >= 2) pairs++;
            c++;
        }
        return big >= 2 || pairs >= 3;
    }

    // ---------------------------------------------------------------- flood wall (pillaring) watch

    /**
     * Baritone pillaring up over a wall looks like: on the ground, in the same column, rising about one block at a
     * time. Walking around a wall, swimming, climbing stairs or ladders, or ordinary bridging never look like that.
     */
    private void tickFloodWatch(Minecraft mc, LocalPlayer player, ClientLevel level, Config cfg) {
        if (!cfg.floodAvoider || !baritoneActive || baritoneForItem) {
            floodSteps = 0;
            floodBaseY = Double.NaN;
            return;
        }
        if (clock % 2 != 0) return;
        if (player.onClimbable() || player.isInWater() || player.isInLava() || player.isFallFlying()) {
            floodSteps = 0;
            floodBaseY = Double.NaN;
            return;
        }
        if (!player.onGround()) return;
        BlockPos feet = player.blockPosition();
        double y = player.getY();
        if (Double.isNaN(floodBaseY) || floodColX != feet.getX() || floodColZ != feet.getZ()) {
            floodBaseY = y;
            floodColX = feet.getX();
            floodColZ = feet.getZ();
            floodSteps = 0;
            floodLastStep = clock;
            return;
        }
        if (y - floodBaseY >= 0.9) {
            floodSteps++;
            floodBaseY = y;
            floodLastStep = clock;
            if (floodSteps == 3) {
                ActivityLog.infoEvery("flood-watch", 20000, "Flood Avoider", "Baritone is building upward. Watching whether this is a pillar over a wall.");
            }
        } else if (clock - floodLastStep > 80) { // idle in this column for 4 s: not a pillar sequence
            floodSteps = 0;
            floodBaseY = y;
        }
        if (floodSteps >= cfg.floodSensitivity) {
            int steps = floodSteps;
            floodSteps = 0;
            floodBaseY = Double.NaN;
            ActivityLog.warn("Flood Avoider", "Detected inefficient vertical pillaring (" + steps + " blocks straight up).");
            navCancel(mc, cfg, "Cancelling Baritone: it is building up over a wall.");
            if (cfg.floodRtp && cfg.rtpEnabled) {
                requestRtp(mc, cfg, "Flood Avoider: Baritone was pillaring up over a wall");
            } else {
                pendingGotoAt = clock + 100;
                pendingGotoWhy = "Restarting navigation after abandoning the pillar.";
            }
        }
    }

    // ---------------------------------------------------------------- food (hunger driven)

    private void beginFoodBreak(Minecraft mc, LocalPlayer player, Config cfg) {
        foodBreak = true;
        eatFailed = false;
        foodResumeGoto = cfg.resumeAfterFood && baritoneActive && !baritoneForItem;
        int food = player.getFoodData().getFoodLevel();
        ActivityLog.info("Food", "Hunger at " + food + "/20, approaching the sprint threshold."
                + (baritoneActive ? " Pausing Baritone." : ""));
        if (baritoneActive || baritoneForItem) navCancel(mc, cfg, "Pausing Baritone to eat.");
        clearTarget(mc);
        stopWalking(mc);
        if (wat != Wat.NONE) {
            wat = Wat.NONE;
            releaseSwimKeys(mc);
        }
        if (dep != Dep.NONE && dep != Dep.TRANSFER) endDeposit(mc, 0);
        if (tri != Tri.NONE && tri != Tri.FLIGHT) endTrident(mc, 0);
        eatPending = 20; // tickEating stops by itself at the target level or when full
        eatToLevel = Math.max(cfg.hungerThreshold + 4, cfg.hungerTarget);
        eatDelay = 10 + rng.nextInt(10);
    }

    private void endFoodBreak(Minecraft mc, Config cfg) {
        foodBreak = false;
        eatToLevel = 0;
        if (eatFailed) {
            hungerCooldownUntil = clock + 1200;
            ActivityLog.warn("Food", "Couldn't eat: nothing edible in hotbar slot " + cfg.foodSlot
                    + " (or can't eat right now). Pausing hunger checks for a minute.");
        } else {
            ActivityLog.info("Food", "Eating complete." + (foodResumeGoto ? " Resuming previous destination." : ""));
        }
        if (foodResumeGoto) {
            foodResumeGoto = false;
            pendingGotoAt = clock + 20 + rng.nextInt(20);
            pendingGotoWhy = "Resuming the previous destination after eating.";
        }
    }

    // ---------------------------------------------------------------- clusters

    private int anchorsNear(ClientLevel level, LocalPlayer player, int radius) {
        BlockPos o = player.blockPosition();
        double r2 = (double) radius * radius;
        int n = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    m.set(o.getX() + dx, o.getY() + dy, o.getZ() + dz);
                    if (!level.getBlockState(m).is(Blocks.RESPAWN_ANCHOR)) continue;
                    if (ignoredUntil.containsKey(m) || o.distSqr(m) > r2) continue;
                    n++;
                }
            }
        }
        return n;
    }

    /** True while anchors remain in the cluster around us (for up to a minute after the last one broke). */
    private boolean clusterBlocked(ClientLevel level, LocalPlayer player, Config cfg) {
        if (!cfg.clusterMining || clock - lastBreakTick > 1200) return false;
        if (clock - clusterCacheTick >= 10) {
            clusterCacheTick = clock;
            clusterCache = anchorsNear(level, player, cfg.clusterRadius) > 0;
        }
        return clusterCache;
    }

    // ---------------------------------------------------------------- hotbar refills (cobblestone, food)

    private int countHotbar(LocalPlayer p, net.minecraft.world.item.Item item) {
        int n = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st.is(item)) n += st.getCount();
        }
        return n;
    }

    private int countMain(LocalPlayer p, net.minecraft.world.item.Item item) {
        int n = 0;
        for (int i = 9; i < 36; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st.is(item)) n += st.getCount();
        }
        return n;
    }

    private boolean reserved(Config cfg, int slot0) {
        return slot0 == cfg.pickaxeSlot - 1 || slot0 == cfg.foodSlot - 1 || slot0 == cfg.tridentSlot - 1;
    }

    private int chooseCobbleSlot(LocalPlayer p, Config cfg) {
        if (cfg.cobbleSlot >= 1) {
            int s0 = cfg.cobbleSlot - 1;
            return reserved(cfg, s0) ? -1 : s0;
        }
        for (int i = 0; i < 9; i++) {
            if (p.getInventory().getItem(i).isEmpty() && !reserved(cfg, i)) return i;
        }
        return -1;
    }

    private void checkRefill(Minecraft mc, LocalPlayer player, Config cfg) {
        if (cfg.autoEat && cfg.foodRefill && foodItem != null && player.getInventory().getItem(cfg.foodSlot - 1).isEmpty()) {
            String name = new ItemStack(foodItem).getHoverName().getString();
            if (countMain(player, foodItem) > 0) {
                startRefill(true, cfg.foodSlot - 1, "Food slot is empty. Refilling with " + name + ".");
            } else {
                ActivityLog.warnEvery("food-none", 300000, "Inventory", "Food slot is empty and there's no more "
                        + name + " in the inventory.");
            }
            return;
        }
        if (cfg.cobbleRefill && countHotbar(player, Items.COBBLESTONE) == 0) {
            if (countMain(player, Items.COBBLESTONE) <= 0) {
                ActivityLog.warnEvery("cobble-none", 300000, "Inventory",
                        "Hotbar cobblestone depleted and there's no cobblestone left in the inventory.");
                return;
            }
            int dest = chooseCobbleSlot(player, cfg);
            if (dest < 0) {
                ActivityLog.warnEvery("cobble-slot", 300000, "Inventory", "Hotbar cobblestone depleted but there's no free hotbar slot for it.");
                return;
            }
            startRefill(false, dest, "Hotbar cobblestone depleted. Refilling.");
        }
    }

    private void startRefill(boolean food, int dest, String msg) {
        refill = Refill.OPENING;
        refillFood = food;
        refillDest = dest;
        refillWait = 8 + rng.nextInt(18); // a moment before reaching for the inventory
        refillTicks = 0;
        ActivityLog.info("Inventory", msg);
    }

    private void closeInventoryScreen(Minecraft mc) {
        Screen sc = mc.gui.screen();
        if (sc instanceof InventoryScreen) sc.onClose();
    }

    /** Opens the inventory, swaps one stack into the hotbar slot, checks it worked, closes it. Human-paced. */
    private boolean tickRefill(Minecraft mc, Config cfg) {
        LocalPlayer p = mc.player;
        if (++refillTicks > 400) {
            ActivityLog.warn("Inventory", "Hotbar refill timed out.");
            closeInventoryScreen(mc);
            refill = Refill.NONE;
            refillCooldownUntil = clock + 1200;
            return false;
        }
        if (refillWait > 0) {
            refillWait--;
            return true;
        }
        net.minecraft.world.item.Item want = refillFood ? foodItem : Items.COBBLESTONE;
        switch (refill) {
            case OPENING -> {
                if (mc.gui.screen() != null) { // something else is open: not now
                    refill = Refill.NONE;
                    refillCooldownUntil = clock + 200;
                    return false;
                }
                mc.gui.setScreen(new InventoryScreen(p));
                refill = Refill.CLICK;
                refillWait = 8 + rng.nextInt(10);
            }
            case CLICK -> {
                if (!(mc.gui.screen() instanceof InventoryScreen) || want == null) {
                    refill = Refill.NONE;
                    return false;
                }
                AbstractContainerMenu menu = p.containerMenu;
                int src = -1;
                int best = 0;
                for (int i = 0; i < menu.slots.size(); i++) {
                    Slot sl = menu.slots.get(i);
                    if (sl.container != p.getInventory()) continue;
                    int cs = sl.getContainerSlot();
                    if (cs < 9 || cs > 35) continue;
                    ItemStack st = sl.getItem();
                    if (st.is(want) && st.getCount() > best) {
                        best = st.getCount();
                        src = i;
                    }
                }
                if (src < 0) {
                    ActivityLog.warn("Inventory", "Nothing to move any more. Closing the inventory.");
                    refill = Refill.CLOSING;
                    refillWait = 6 + rng.nextInt(8);
                    return true;
                }
                mc.gameMode.handleContainerInput(menu.containerId, src, refillDest, ContainerInput.SWAP, p);
                refill = Refill.VERIFY;
                refillWait = 8 + rng.nextInt(8);
            }
            case VERIFY -> {
                boolean ok = want != null && countHotbar(p, want) > 0;
                ActivityLog.info("Inventory", ok ? "Hotbar refilled." : "The refill didn't work. Will retry later.");
                if (!ok) refillCooldownUntil = clock + 1200;
                refill = Refill.CLOSING;
                refillWait = 6 + rng.nextInt(10);
            }
            case CLOSING -> {
                if (!p.containerMenu.getCarried().isEmpty()) { // never close with something on the cursor
                    refillWait = 10;
                    return true;
                }
                closeInventoryScreen(mc);
                refill = Refill.NONE;
                if (refillCooldownUntil < clock + 100) refillCooldownUntil = clock + 100;
                return false;
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- water (Baritone is poor in water)

    private boolean touchesWater(ClientLevel level, BlockPos p) {
        for (Direction d : Direction.values()) {
            if (level.getBlockState(p.relative(d)).getFluidState().is(FluidTags.WATER)) return true;
        }
        return false;
    }

    /** An underwater anchor is close by: take over from Baritone and swim to it. Returns true while it handles things. */
    private boolean waterIdle(Minecraft mc, LocalPlayer player, ClientLevel level, Config cfg) {
        if (!cfg.waterMining || rtp != Rtp.NONE || !navIdle() && wat == Wat.NONE) return false;
        Vec3 eye = player.getEyePosition();
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos q : farFound) {
            if (!level.getBlockState(q).is(Blocks.RESPAWN_ANCHOR) || ignoredUntil.containsKey(q)) continue;
            double d = eye.distanceTo(Vec3.atCenterOf(q));
            if (d > cfg.waterRange || d >= bd || !touchesWater(level, q)) continue;
            best = q;
            bd = d;
        }
        if (best == null) return wat == Wat.SWIM && watTarget != null;
        if (wat != Wat.SWIM) {
            navCancel(mc, cfg, "An underwater anchor is close. Pausing Baritone: swimming there ourselves.");
            ActivityLog.info("Water", "Underwater anchor " + (int) bd + " blocks away. Swimming to it.");
            wat = Wat.SWIM;
            watTicks = 0;
            watClose = 0;
        }
        watTarget = best;
        return true;
    }

    private boolean tickWater(Minecraft mc, LocalPlayer player, ClientLevel level, Config cfg) {
        watTicks++;
        if (player.isInLava()) {
            ActivityLog.warn("Water", "In lava! Leaving water mode.");
            wat = Wat.NONE;
            releaseSwimKeys(mc);
            return false;
        }
        if (wat == Wat.SWIM) {
            if (watTarget == null || !level.getBlockState(watTarget).is(Blocks.RESPAWN_ANCHOR) || ignoredUntil.containsKey(watTarget)) {
                wat = Wat.NONE;
                releaseSwimKeys(mc);
                return false;
            }
            Vec3 c = Vec3.atCenterOf(watTarget);
            double d = player.getEyePosition().distanceTo(c);
            if (watTicks > 1200 || watClose > 80) {
                ignore(watTarget, 1200);
                ActivityLog.warn("Water", "Couldn't reach or hit the underwater anchor. Skipping it.");
                wat = Wat.NONE;
                releaseSwimKeys(mc);
                return false;
            }
            if (d <= Math.max(2.0, cfg.aimDistance - 0.8)) { // close enough: acquire() takes over and mines it
                releaseSwimKeys(mc);
                watClose++;
                return true;
            }
            watClose = 0;
            swimTo(mc, player, c);
            return true;
        }
        if (wat == Wat.LAND) {
            if (!player.isInWater() && player.onGround()) {
                ActivityLog.info("Water", "Back on solid ground.");
                wat = Wat.NONE;
                releaseSwimKeys(mc);
                return false;
            }
            if (watTicks > 1200) {
                ActivityLog.warn("Water", "Couldn't get out of the water in 60 seconds. Carrying on regardless.");
                landFailUntil = clock + 600;
                wat = Wat.NONE;
                releaseSwimKeys(mc);
                return false;
            }
            if (landTarget == null || clock % 100 == 0) landTarget = findLandTarget(level, player);
            if (landTarget == null) { // nothing solid in sight: at least come up for air
                if (watTicks > 200) {
                    ActivityLog.warn("Water", "No solid ground found nearby. Carrying on regardless.");
                    landFailUntil = clock + 600;
                    wat = Wat.NONE;
                    releaseSwimKeys(mc);
                    return false;
                }
                mc.options.keyJump.setDown(true);
                swimKeysDown = true;
                return true;
            }
            swimTo(mc, player, landTarget);
            return true;
        }
        return false;
    }

    /** The nearest solid block with two free blocks above it and no liquid or hazard, within 24 blocks. */
    private Vec3 findLandTarget(ClientLevel level, LocalPlayer player) {
        BlockPos base = player.blockPosition();
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -24; dx <= 24; dx++) {
            for (int dz = -24; dz <= 24; dz++) {
                for (int dy = -6; dy <= 12; dy++) {
                    m.set(base.getX() + dx, base.getY() + dy, base.getZ() + dz);
                    double d = base.distSqr(m);
                    if (d >= bd) continue;
                    BlockState st = level.getBlockState(m);
                    if (!st.getFluidState().isEmpty() || hazard(st) || !st.isFaceSturdy(level, m, Direction.UP)) continue;
                    BlockPos a1 = m.above();
                    BlockPos a2 = a1.above();
                    BlockState s1 = level.getBlockState(a1);
                    BlockState s2 = level.getBlockState(a2);
                    if (!s1.getCollisionShape(level, a1).isEmpty() || !s1.getFluidState().isEmpty() || hazard(s1)) continue;
                    if (!s2.getCollisionShape(level, a2).isEmpty() || hazard(s2)) continue;
                    best = m.immutable();
                    bd = d;
                }
            }
        }
        return best == null ? null : new Vec3(best.getX() + 0.5, best.getY() + 1.0, best.getZ() + 0.5);
    }

    /** Swims (or wades) toward dest with the camera leading. Returns true when within about a block of it. */
    private boolean swimTo(Minecraft mc, LocalPlayer p, Vec3 dest) {
        Vec3 eye = p.getEyePosition();
        double dx = dest.x - p.getX();
        double dz = dest.z - p.getZ();
        double dy = dest.y - p.getY();
        double horiz = Math.hypot(dx, dz);
        if (Math.sqrt(horiz * horiz + dy * dy) < 0.9) {
            releaseSwimKeys(mc);
            return true;
        }
        float yaw = (float) (Mth.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F;
        float pitch = (float) (-(Mth.atan2(dest.y - eye.y, Math.max(horiz, 0.3)) * 180.0 / Math.PI));
        pitch = Mth.clamp(pitch, -70.0F, 70.0F);
        rotateTo(p, horiz > 0.4 ? yaw : p.getYRot(), pitch);
        mc.options.keyUp.setDown(true);
        mc.options.keySprint.setDown(true); // sprint-swimming follows the look direction, up and down as well
        swimKeysDown = true;
        Vec3 pos = p.position();
        if (swimLastPos == null) swimLastPos = pos;
        if (++swimTick % 10 == 0) { // not moving? hop up for a moment (ledges, steps out of the water)
            swimStuck = pos.distanceTo(swimLastPos) < 0.15 ? swimStuck + 1 : 0;
            swimLastPos = pos;
        }
        mc.options.keyJump.setDown(dy > 0.8 || (swimStuck > 0 && swimStuck % 3 != 0));
        return false;
    }

    private void releaseSwimKeys(Minecraft mc) {
        if (swimKeysDown) {
            mc.options.keyUp.setDown(false);
            mc.options.keySprint.setDown(false);
            mc.options.keyJump.setDown(false);
            swimKeysDown = false;
        }
        swimLastPos = null;
        swimStuck = 0;
    }

    /** Running low on air: stop everything and swim up. */
    private void breathe(Minecraft mc, LocalPlayer player) {
        if (!breathing) {
            breathing = true;
            ActivityLog.warn("Water", "Running low on air. Swimming up to breathe.");
        }
        clearTarget(mc);
        stopEating(mc);
        mc.options.keyJump.setDown(true);
        swimKeysDown = true;
        rotateTo(player, player.getYRot(), -50.0F);
    }

    // ---------------------------------------------------------------- pickaxe swap (once per cooldown)

    /** Selects the pickaxe slot at most once per cooldown, and backs off if something else keeps changing the slot. */
    private boolean ensurePickSlot(LocalPlayer p, Config cfg) {
        int want = Mth.clamp(cfg.pickaxeSlot, 1, 9) - 1;
        Inventory inv = p.getInventory();
        if (inv.getSelectedSlot() == want) {
            swapDelay = -1;
            return true;
        }
        if (clock < swapBackoffUntil || clock - lastPickSwapTick < cfg.swapCooldownTicks) return false;
        if (swapDelay < 0) swapDelay = 2 + rng.nextInt(4);
        if (--swapDelay <= 0) {
            inv.setSelectedSlot(want);
            swapDelay = -1;
            lastPickSwapTick = clock;
            pickSwaps.addLast(clock);
            while (!pickSwaps.isEmpty() && clock - pickSwaps.peekFirst() > 200) pickSwaps.pollFirst();
            if (pickSwaps.size() >= 4) {
                swapBackoffUntil = clock + 100;
                pickSwaps.clear();
                ActivityLog.warn("Mining", "The hotbar slot keeps being changed by something else (Baritone placing blocks?). "
                        + "Pausing pickaxe swaps for 5 seconds.");
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
                && (eating || tri != Tri.NONE || !mc.player.isUsingItem());
    }

    private void reset(Minecraft mc) {
        releaseDepKeys(mc);
        releaseSwimKeys(mc);
        wat = Wat.NONE;
        if (tri != Tri.NONE) endTrident(mc, 0);
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
                targetAge = 0;
                outOfRangeTicks = 0;
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
