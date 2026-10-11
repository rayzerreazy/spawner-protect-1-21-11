package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Spawner Protect (Meteor Client addon module, Fabric 1.21.11, Yarn mappings)
 *
 * Flow: player shows up -> mine every spawner (and, with shulker-load, every placed shulker box)
 *       -> collect the drops -> double check -> store everything in the ender chest -> leave
 *       -> rejoin -> check again -> repeat if anything is left, otherwise turn off and leave.
 *
 * If another player gets within panic-range while it's working, it stores whatever it has
 * picked up in the ender chest and leaves immediately.
 */
public class SpawnerProtect extends Module {
    private static final int MAX_VERIFY_PASSES = 5;
    private static final int MAX_ATTEMPTS = 2;
    private static final int MAX_PACK_ROUNDS = 6;

    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Integer> detectRange = sg.add(new IntSetting.Builder()
        .name("detect-range").description("Range to detect other players. 0 = any loaded player.")
        .defaultValue(64).min(0).sliderMax(128).build());

    private final Setting<List<String>> ignoredNames = sg.add(new StringListSetting.Builder()
        .name("ignored-players").description("Player names that never trigger the module.")
        .defaultValue(new ArrayList<>()).build());

    private final Setting<Integer> spawnerRange = sg.add(new IntSetting.Builder()
        .name("spawner-range").description("How far to look for spawners and shulker boxes.")
        .defaultValue(12).min(1).sliderMax(16).build());

    private final Setting<Integer> panicRange = sg.add(new IntSetting.Builder()
        .name("panic-range").description("If another player gets this close while working, store everything you picked up in the ender chest and leave right away. 0 = off.")
        .defaultValue(5).min(0).sliderMax(16).build());

    private final Setting<Integer> depositDelay = sg.add(new IntSetting.Builder()
        .name("deposit-delay").description("Ticks between each shift-click into a chest or shulker box.")
        .defaultValue(2).min(0).sliderMax(10).build());

    private final Setting<Boolean> shulkerLoad = sg.add(new BoolSetting.Builder()
        .name("shulker-load").description("Break every placed shulker box too, and when your inventory is full put the spawners in a shulker box and store it in the ender chest.")
        .defaultValue(false).build());

    private final Setting<Integer> holdClickChance = sg.add(new IntSetting.Builder()
        .name("hold-click-chance").description("Percent of blocks mined by holding the attack key like a player holding left-click. The rest use the per-tick break method. 100 = always hold, 0 = never.")
        .defaultValue(75).min(0).max(100).sliderMax(100).build());

    private final Setting<Integer> attemptSeconds = sg.add(new IntSetting.Builder()
        .name("attempt-seconds").description("How long one attempt to break a block gets. After 2 failed attempts (anti-cheat) the block is skipped.")
        .defaultValue(5).min(2).sliderMax(20).build());

    private final Setting<Integer> lookSpeed = sg.add(new IntSetting.Builder()
        .name("look-speed").description("Max degrees your view turns per tick when facing a block.")
        .defaultValue(25).min(1).sliderMax(90).build());

    private final Setting<Boolean> rotate = sg.add(new BoolSetting.Builder()
        .name("rotate").description("Silent rotation when placing the ender chest or a shulker box.")
        .defaultValue(true).build());

    private final Setting<Boolean> sounds = sg.add(new BoolSetting.Builder()
        .name("sounds").description("Play sound effects for key moments (player detected, panic, spawner mined, stored, rejoined, finished).")
        .defaultValue(true).build());

    private final Setting<Integer> soundVolume = sg.add(new IntSetting.Builder()
        .name("sound-volume").description("Volume of the sound effects, in percent.")
        .defaultValue(60).min(0).max(100).sliderMax(100).visible(sounds::get).build());

    private final Setting<Integer> timeout = sg.add(new IntSetting.Builder()
        .name("timeout-seconds").description("Safety: store everything and leave after this long even if not finished. 0 = off.")
        .defaultValue(180).min(0).sliderMax(600).build());

    private final Setting<Integer> rejoinDelay = sg.add(new IntSetting.Builder()
        .name("rejoin-delay").description("Seconds to wait before rejoining.")
        .defaultValue(8).min(1).sliderMax(60).build());

    private final Setting<Integer> maxRounds = sg.add(new IntSetting.Builder()
        .name("max-recheck-rounds").description("Give up (and leave for good) after this many leave/rejoin/recheck rounds.")
        .defaultValue(3).min(1).sliderMax(10).build());

    private enum State {
        IDLE, MINING, COLLECTING, VERIFY, DEPOSIT, LEAVE, REJOIN_WAIT, REJOINING, RECHECK,
        SHULKER_LOAD, SHULKER_BREAK, SHULKER_COLLECT
    }

    /** What the next ender chest deposit should move. */
    private enum Dep { ALL, LOADED_SHULKERS }

    private State state = State.IDLE;
    private State resumeState = State.MINING;
    private Dep depMode = Dep.ALL;

    private int ticksActive, waitTicks, actionTimer, noProgressTicks, verifyPasses, lastCount;
    private int lookTicks, mineTicks, openCooldown, chestLookTicks, shulkerTicks, shulkersStored;
    private int rejoinTimer, connectTicks, connectAttempts, rounds;
    private int packRounds, shulkerItemsBefore, collectTargetId = -1, collectTicks;

    private String reason = "";
    private String lastMsg = "";
    private ServerInfo lastServer;
    private BlockPos lookTarget, mineTarget, shulkerPos;

    private final Set<BlockPos> skipped = new HashSet<>();
    private final Map<BlockPos, Integer> attempts = new HashMap<>();
    private final Map<BlockPos, Integer> skippedAt = new HashMap<>();
    private int handled; // blocks mined or given up on so far
    private BlockPos pendingBreak; // a block that vanished; waiting to see if the server really broke it
    private int pendingTicks;
    private final Random rng = new Random();
    private boolean holdMode;
    private boolean retriedSkipped, chestOpenSeen, boxOpenSeen, shulkerDisabled, noRejoin, panicking;
    private boolean shulkersUnlocked, midRunDeposit;
    private final Set<Integer> ignoredDrops = new HashSet<>();

    public SpawnerProtect() {
        super(AddonTemplate.CATEGORY, "spawner-protect",
            "Picks up spawners when a player shows up, stashes them in your ender chest, then logs you out.");
    }

    @Override
    public void onActivate() { reset(); }

    @Override
    public void onDeactivate() { stopWalking(); reset(); }

    private void reset() {
        state = State.IDLE;
        resumeState = State.MINING;
        depMode = Dep.ALL;
        ticksActive = waitTicks = actionTimer = noProgressTicks = verifyPasses = lastCount = 0;
        lookTicks = mineTicks = openCooldown = chestLookTicks = shulkerTicks = shulkersStored = 0;
        rejoinTimer = connectTicks = connectAttempts = rounds = 0;
        reason = "";
        lastMsg = "";
        lastServer = null;
        lookTarget = mineTarget = shulkerPos = null;
        skipped.clear();
        attempts.clear();
        skippedAt.clear();
        handled = 0;
        pendingBreak = null;
        pendingTicks = 0;
        retriedSkipped = chestOpenSeen = boxOpenSeen = shulkerDisabled = noRejoin = panicking = false;
        shulkersUnlocked = midRunDeposit = false;
        packRounds = shulkerItemsBefore = collectTicks = 0;
        collectTargetId = -1;
        ignoredDrops.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (state == State.REJOIN_WAIT || state == State.REJOINING) {
            handleRejoin();
            return;
        }

        if (mc.player == null || mc.world == null) return;

        // Only take over the movement keys once we're actually working,
        // so you can still walk around normally while the module is just watching.
        if (state != State.IDLE) {
            stopWalking();
            ticksActive++;
            if (timeout.get() > 0 && ticksActive > timeout.get() * 20
                && state != State.LEAVE && state != State.DEPOSIT) {
                storeAndLeave("Timed out - storing everything and leaving.", true);
            }
        }

        // Panic: someone is right next to us while we're working.
        if (panicRange.get() > 0 && isWorking()) {
            PlayerEntity close = findIntruder(panicRange.get());
            if (close != null) {
                reason = close.getName().getString();
                beep(SoundEvents.ENTITY_ENDER_DRAGON_GROWL, 1.0f);
                storeAndLeave("Player " + reason + " is within " + panicRange.get()
                    + " blocks - storing everything and leaving!", true);
            }
        }

        switch (state) {
            case IDLE -> {
                PlayerEntity p = findIntruder(detectRange.get());
                if (p != null) {
                    reason = p.getName().getString();
                    info("Player detected: " + reason + " - securing spawners.");
                    beep(SoundEvents.BLOCK_BELL_USE, 0.8f);
                    state = State.MINING;
                }
            }
            case MINING -> mine();
            case COLLECTING -> collect();
            case VERIFY -> verify();
            case DEPOSIT -> deposit();
            case LEAVE -> leave();
            case RECHECK -> recheck();
            case SHULKER_LOAD -> shulkerLoadStep();
            case SHULKER_BREAK -> shulkerBreakStep();
            case SHULKER_COLLECT -> shulkerCollectStep();
            default -> { }
        }
    }

    private boolean isWorking() {
        return switch (state) {
            case MINING, COLLECTING, VERIFY, RECHECK, SHULKER_LOAD, SHULKER_BREAK, SHULKER_COLLECT -> true;
            default -> false;
        };
    }

    // ---------------------------------------------------------------- detection

    private PlayerEntity findIntruder(int range) {
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player || p.isSpectator()) continue;
            if (Friends.get().isFriend(p)) continue;
            String name = p.getName().getString();
            if (ignoredNames.get().stream().anyMatch(n -> n.equalsIgnoreCase(name))) continue;
            if (range > 0 && mc.player.distanceTo(p) > range) continue;
            return p;
        }
        return null;
    }

    // ---------------------------------------------------------------- mining

    private void mine() {
        if (maybeStartShulker(State.MINING)) return;

        // A block that vanished may just be the client guessing: the server can put it back
        // (anti-cheat, timing, tool mismatch). Only count it as mined once it has stayed gone.
        if (pendingBreak != null) {
            if (isMineable(pendingBreak)) {
                BlockPos back = pendingBreak;
                pendingBreak = null;
                failAttempt(back, "The server put the block back (it was not really broken).");
            } else if (++pendingTicks >= 12) {
                handled++;
                beep(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.3f);
                attempts.remove(pendingBreak);
                pendingBreak = null;
            }
        }
        if (pendingBreak == null && mineTarget != null && !isMineable(mineTarget)) {
            pendingBreak = mineTarget;
            pendingTicks = 0;
            mineTarget = null;
        }

        // A skipped block only gets another go after 2 other blocks were mined (or given up on).
        for (Iterator<BlockPos> it = skipped.iterator(); it.hasNext(); ) {
            BlockPos p = it.next();
            if (handled - skippedAt.getOrDefault(p, 0) >= 2) {
                it.remove();
                skippedAt.remove(p);
                attempts.remove(p);
                if (isMineable(p)) say("Going back to the block at " + p.getX() + " " + p.getY() + " " + p.getZ() + ".");
            }
        }

        List<BlockPos> spawnersAll = findBlocks(spawnerRange.get(), true);
        List<BlockPos> shulkersAll = (shulkerLoad.get() && shulkersUnlocked)
            ? findShulkerBlocks(spawnerRange.get()) : new ArrayList<>();

        // Spawners first, then (with shulker-load) every placed shulker box.
        List<BlockPos> targets = unskipped(spawnersAll);
        boolean shulkerTarget = false;
        if (targets.isEmpty()) {
            targets = unskipped(shulkersAll);
            shulkerTarget = !targets.isEmpty();
        }

        if (targets.isEmpty()) {
            int stuck = spawnersAll.size() + shulkersAll.size();
            if (stuck > 0) {
                // Only blocks that failed earlier are left: give them one more go.
                if (!retriedSkipped) {
                    retriedSkipped = true;
                    skipped.clear();
                    attempts.clear();
                    skippedAt.clear();
                    say("Everything else is done - trying the skipped block(s) once more.");
                    return;
                }
                warning("Couldn't break " + stuck + " block(s) after " + MAX_ATTEMPTS + " attempts each (anti-cheat?). Skipping them.");
            }
            waitTicks = 0;
            state = State.COLLECTING;
            return;
        }

        final boolean forShulker = shulkerTarget;
        FindItemResult pick = InvUtils.findInHotbar(s -> s.isIn(ItemTags.PICKAXES) && (forShulker || hasSilkTouch(s)));
        if (pick.found()) {
            // Only switch slots if we aren't already holding it (switching mid-break resets progress).
            if (mc.player.getMainHandStack() != mc.player.getInventory().getStack(pick.slot())) {
                InvUtils.swap(pick.slot(), false);
            }
        } else if (!forShulker) {
            error("No silk touch pickaxe in hotbar!");
            noRejoin = true;
            state = State.LEAVE;
            return;
        }

        BlockPos target = targets.get(0);
        Vec3d center = Vec3d.ofCenter(target);

        if (mc.player.getEyePos().distanceTo(center) > 4.3) {
            walkTo(center);
            return;
        }

        if (!forShulker) mc.options.sneakKey.setPressed(true); // crouch while breaking spawners

        if (!target.equals(mineTarget)) {
            mineTarget = target;
            mineTicks = 0;
            holdMode = rng.nextInt(100) < holdClickChance.get();
            info("Mining " + (forShulker ? "the shulker box" : "the spawner") + " at " + target.getX() + " "
                + target.getY() + " " + target.getZ() + (holdMode ? " (holding the attack key)." : " (break packets)."));
        }

        if (!target.equals(lookTarget)) {
            lookTarget = target;
            lookTicks = 0;
        }
        // Turn to the block only if we aren't already aimed at it, then hold the aim steady.
        // If something is in the way and it never lines up, stop waiting after 2 seconds.
        if (!isLookingAt(target)) {
            lookAt(target);
            if (!isLookingAt(target) && ++lookTicks < 40) return;
        }

        // One attempt = attempt-seconds of trying. After 2 failed attempts, skip the block.
        if (++mineTicks > attemptSeconds.get() * 20) {
            failAttempt(target, "Not broken in time.");
            return;
        }

        if (holdMode && isLookingAt(target)) {
            // Hold left-click: the game's own mining code does the rest, only while we're really aimed at it.
            mc.options.attackKey.setPressed(true);
        } else {
            breakBlockLegit(target);
        }
    }

    /** One failed attempt on a block; after MAX_ATTEMPTS it gets skipped for now. */
    private void failAttempt(BlockPos target, String why) {
        int n = attempts.merge(target, 1, Integer::sum);
        mineTicks = 0;
        lookTicks = 0;
        String where = target.getX() + " " + target.getY() + " " + target.getZ();
        if (n >= MAX_ATTEMPTS) {
            skipped.add(target);
            beep(SoundEvents.ENTITY_VILLAGER_NO, 1.0f);
            handled++;
            skippedAt.put(target, handled);
            if (target.equals(mineTarget)) mineTarget = null;
            warning(why + " Attempt " + n + "/" + MAX_ATTEMPTS + " failed at " + where + " - skipping it until 2 other blocks are done.");
        } else {
            warning(why + " Attempt " + n + "/" + MAX_ATTEMPTS + " failed at " + where + " - trying again.");
        }
    }

    /** Break a block the way the vanilla client does: using the face we are really looking at. */
    private void breakBlockLegit(BlockPos pos) {
        Direction side = Direction.UP;
        HitResult hit = mc.player.raycast(4.5, 1f, false);
        if (hit instanceof BlockHitResult bhr && bhr.getBlockPos().equals(pos)) side = bhr.getSide();

        if (mc.interactionManager.isBreakingBlock()) {
            mc.interactionManager.updateBlockBreakingProgress(pos, side);
        } else {
            mc.interactionManager.attackBlock(pos, side);
        }
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    private void lookAt(BlockPos pos) {
        float targetYaw = (float) Rotations.getYaw(pos);
        float targetPitch = (float) Rotations.getPitch(pos);
        float max = lookSpeed.get();

        float dYaw = MathHelper.wrapDegrees(targetYaw - mc.player.getYaw());
        float dPitch = targetPitch - mc.player.getPitch();

        mc.player.setYaw(mc.player.getYaw() + MathHelper.clamp(dYaw, -max, max));
        mc.player.setPitch(MathHelper.clamp(mc.player.getPitch() + MathHelper.clamp(dPitch, -max, max), -90f, 90f));
    }

    private boolean isLookingAt(BlockPos pos) {
        HitResult hit = mc.player.raycast(4.5, 1f, false);
        return hit instanceof BlockHitResult bhr && bhr.getBlockPos().equals(pos);
    }

    // ---------------------------------------------------------------- collecting

    private void collect() {
        ItemEntity nearest = nearestDrop(spawnerRange.get() + 8);
        if (nearest != null) {
            if (maybeStartShulker(State.COLLECTING)) return;

            // Don't chase one item forever (e.g. it fell somewhere unreachable).
            if (nearest.getId() != collectTargetId) {
                collectTargetId = nearest.getId();
                collectTicks = 0;
            }
            if (++collectTicks > 200) {
                ignoredDrops.add(nearest.getId());
                collectTargetId = -1;
                warning("Couldn't pick up an item at " + (int) nearest.getX() + " " + (int) nearest.getY()
                    + " " + (int) nearest.getZ() + " (unreachable or inventory full?) - ignoring it.");
                return;
            }

            waitTicks = 0;
            walkTo(new Vec3d(nearest.getX(), nearest.getY(), nearest.getZ()));
            return;
        }

        // Nothing on the ground right now. Wait a moment: drops can take a second to appear.
        if (++waitTicks < 30) return;

        if (verifyPasses < MAX_VERIFY_PASSES) {
            waitTicks = 0;
            state = State.VERIFY;
            return;
        }
        finishPickup();
    }

    // ---------------------------------------------------------------- double check

    private void verify() {
        if (++waitTicks < 20) return; // give the server a second to settle

        if (hasUnskippedTarget()) {
            verifyPasses++;
            warning("Double check: something is still placed, mining it (" + verifyPasses + "/" + MAX_VERIFY_PASSES + ").");
            state = State.MINING;
            return;
        }

        if (nearestDrop(spawnerRange.get() + 12) != null) {
            verifyPasses++;
            warning("Double check: something is still on the ground, collecting it (" + verifyPasses + "/" + MAX_VERIFY_PASSES + ").");
            waitTicks = 0;
            state = State.COLLECTING;
            return;
        }

        info("Double check passed: nothing left nearby.");
        finishPickup();
    }

    private void finishPickup() {
        // Shulker mode: before storing anything, pack the spawners we carry into a shulker box
        // (a nearby placed one, or one from the hotbar), then break every shulker box.
        // If a shulker box fills up, the leftover spawners just go into the ender chest with it.
        if (shulkerLoad.get() && !shulkerDisabled) {
            shulkersUnlocked = true;

            if (countSpawners() > 0 && packRounds < MAX_PACK_ROUNDS && shulkerAvailable()) {
                packRounds++;
                beginShulkerCycle(State.MINING, "Packing the spawners you're carrying into a shulker box.");
                return;
            }

            if (!unskipped(findShulkerBlocks(spawnerRange.get())).isEmpty()) {
                verifyPasses = 0;
                state = State.MINING; // mine() breaks the shulker boxes
                return;
            }
        }

        depMode = Dep.ALL;
        if (countDeposit() > 0) {
            startDeposit();
        } else if (shulkersStored > 0) {
            info("Everything is already stored in the ender chest.");
            state = State.LEAVE;
        } else {
            warning("Nothing to store.");
            state = State.LEAVE;
        }
    }

    // ---------------------------------------------------------------- ender chest

    private void startDeposit() {
        midRunDeposit = false;
        noProgressTicks = 0;
        openCooldown = 0;
        chestLookTicks = 0;
        chestOpenSeen = false;
        state = State.DEPOSIT;
    }

    private void deposit() {
        if (countDeposit() == 0) {
            if (mc.player.currentScreenHandler instanceof GenericContainerScreenHandler) mc.player.closeHandledScreen();
            if (midRunDeposit) {
                midRunDeposit = false;
                depMode = Dep.ALL;
                shulkersStored++;
                info("Loaded shulker box stored in the ender chest - carrying on.");
                waitTicks = 0;
                state = resumeState;
                return;
            }
            info("Everything stored in the ender chest.");
            beep(SoundEvents.ENTITY_PLAYER_LEVELUP, 1.0f);
            state = State.LEAVE;
            return;
        }

        // Chest GUI is open -> shift-click everything in.
        if (mc.player.currentScreenHandler instanceof GenericContainerScreenHandler handler) {
            if (!chestOpenSeen) {
                chestOpenSeen = true;
                actionTimer = 5; // short pause so the server and client agree on the contents
                lastCount = countDeposit();
                noProgressTicks = 0;
                say("Ender chest open - storing everything.");
            }
            if (actionTimer-- > 0) return;
            actionTimer = depositDelay.get();

            // Nothing is moving any more -> the chest is full (or refusing).
            int now = countDeposit();
            if (now != lastCount) {
                lastCount = now;
                noProgressTicks = 0;
            } else if (++noProgressTicks > 40) {
                warning("The ender chest isn't taking any more items (full?).");
                mc.player.closeHandledScreen();
                noRejoin = true;
                state = State.LEAVE;
                return;
            }

            int chestSlots = handler.getRows() * 9;
            for (int i = chestSlots; i < handler.slots.size(); i++) {
                if (shouldDeposit(handler.getSlot(i).getStack())) {
                    InvUtils.shiftClick().slotId(i);
                    return;
                }
            }
            return;
        }
        chestOpenSeen = false;

        if (++noProgressTicks > (panicking ? 200 : 300)) {
            warning("Couldn't open the ender chest.");
            state = State.LEAVE;
            return;
        }

        List<BlockPos> chests = findBlocks(8, false);
        if (chests.isEmpty()) {
            FindItemResult ec = InvUtils.findInHotbar(Items.ENDER_CHEST);
            if (!ec.found()) {
                error("No ender chest nearby and none in hotbar.");
                noRejoin = true;
                state = State.LEAVE;
                return;
            }
            say("No ender chest nearby - placing the one from your hotbar.");
            BlockPos spot = findPlaceSpot();
            if (spot != null) BlockUtils.place(spot, ec, rotate.get(), 50);
            return;
        }

        BlockPos chest = chests.get(0);
        Vec3d center = Vec3d.ofCenter(chest);
        if (mc.player.getEyePos().distanceTo(center) > 4.3) {
            say("Walking to the ender chest.");
            walkTo(center);
            return;
        }

        // Look at the chest like a real player, then right-click what we're actually aiming at.
        lookAt(chest);
        HitResult hit = mc.player.raycast(4.5, 1f, false);
        boolean aimed = hit instanceof BlockHitResult bhr && bhr.getBlockPos().equals(chest);
        if (!aimed && ++chestLookTicks < 40) return;

        if (openCooldown-- > 0) return;
        openCooldown = 10;

        say("Opening the ender chest.");
        BlockHitResult use = aimed ? (BlockHitResult) hit : new BlockHitResult(center, Direction.UP, chest, false);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, use);
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    private BlockPos findPlaceSpot() {
        BlockPos base = mc.player.getBlockPos();
        for (Direction d : Direction.Type.HORIZONTAL) {
            BlockPos p = base.offset(d);
            if (mc.world.getBlockState(p).isReplaceable()
                && mc.world.getBlockState(p.up()).isAir()
                && !mc.world.getBlockState(p.down()).isReplaceable()) return p;
        }
        return null;
    }

    // ---------------------------------------------------------------- panic / abort

    /** Stop what we're doing, put everything we carry in the ender chest, then leave. */
    private void storeAndLeave(String why, boolean allowRejoin) {
        warning(why);
        noRejoin = !allowRejoin;
        panicking = true;
        stopWalking();
        if (mc.player.currentScreenHandler instanceof ShulkerBoxScreenHandler) mc.player.closeHandledScreen();
        depMode = Dep.ALL;
        if (countDeposit() > 0) {
            startDeposit();
        } else {
            state = State.LEAVE;
        }
    }

    // ---------------------------------------------------------------- leaving

    private void leave() {
        stopWalking();
        if (mc.player.currentScreenHandler instanceof GenericContainerScreenHandler
            || mc.player.currentScreenHandler instanceof ShulkerBoxScreenHandler) mc.player.closeHandledScreen();

        String msg = "[SpawnerProtect] " + (reason.isEmpty() ? "" : "Player detected (" + reason + ") - ")
            + "everything secured.";

        ServerInfo server = mc.getCurrentServerEntry();
        if (!noRejoin && server != null && mc.getNetworkHandler() != null) {
            lastServer = server;
            rejoinTimer = rejoinDelay.get() * 20;
            connectTicks = 0;
            connectAttempts = 0;
            state = State.REJOIN_WAIT;
            disconnect(msg);
            return;
        }

        if (!noRejoin) warning("Rejoining only works on multiplayer servers - leaving for good.");
        finish(msg);
    }

    private void disconnect(String msg) {
        try {
            if (mc.getNetworkHandler() != null) {
                mc.getNetworkHandler().getConnection().disconnect(Text.literal(msg));
            } else {
                info(msg);
            }
        } catch (Exception ignored) { }
    }

    /** Leave the game and turn the module off for good. */
    private void finish(String msg) {
        info("Spawner Protect finished - turning itself off.");
        beep(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f);
        disconnect(msg);
        if (isActive()) toggle();
    }

    // ---------------------------------------------------------------- rejoin + recheck

    private void handleRejoin() {
        // Already back in the world -> start checking.
        if (mc.player != null && mc.world != null) {
            if (state == State.REJOINING) {
                info("Rejoined, checking for leftover spawners and shulker boxes...");
                beep(SoundEvents.ENTITY_ENDERMAN_TELEPORT, 1.0f);
                waitTicks = 0;
                ticksActive = 0;
                skipped.clear();
                attempts.clear();
                skippedAt.clear();
                handled = 0;
                ignoredDrops.clear();
                retriedSkipped = false;
                shulkersUnlocked = true;
                packRounds = 0;
                panicking = false;
                verifyPasses = 0;
                state = State.RECHECK;
            }
            return;
        }

        if (state == State.REJOIN_WAIT) {
            if (--rejoinTimer > 0) return;
            connect();
            state = State.REJOINING;
            return;
        }

        // REJOINING: still connecting. Retry every 30 seconds if it hasn't worked.
        if (++connectTicks > 600) {
            if (connectAttempts >= 10) {
                warning("Couldn't rejoin after 10 tries, giving up.");
                if (isActive()) toggle();
                return;
            }
            connect();
        }
    }

    private void connect() {
        connectTicks = 0;
        connectAttempts++;
        if (lastServer == null) {
            toggle();
            return;
        }
        ConnectScreen.connect(new TitleScreen(), mc, ServerAddress.parse(lastServer.address), lastServer, false, null);
    }

    private void recheck() {
        if (++waitTicks < 100) return; // let chunks and entities load

        if (hasUnskippedTarget()) {
            if (!nextRound("A spawner or shulker box is still placed")) return;
            state = State.MINING;
            return;
        }

        if (nearestDrop(spawnerRange.get() + 12) != null) {
            if (!nextRound("A spawner or shulker box is still on the ground")) return;
            waitTicks = 0;
            state = State.COLLECTING;
            return;
        }

        depMode = Dep.ALL;
        if (countDeposit() > 0) {
            if (!nextRound("Spawners or shulker boxes are still in your inventory")) return;
            startDeposit();
            return;
        }

        info("Recheck passed: no spawners or shulker boxes left.");
        finish("[SpawnerProtect] All clear - nothing left.");
    }

    private boolean nextRound(String why) {
        rounds++;
        if (rounds > maxRounds.get()) {
            warning(why + ", but the max number of rounds was reached. Leaving for good.");
            finish("[SpawnerProtect] Gave up after " + maxRounds.get() + " rounds.");
            return false;
        }
        warning(why + " - going again (round " + rounds + "/" + maxRounds.get() + ").");
        verifyPasses = 0;
        ticksActive = 0;
        return true;
    }

    // ---------------------------------------------------------------- shulker load

    private boolean maybeStartShulker(State resume) {
        if (!shulkerLoad.get() || shulkerDisabled) return false;
        if (countSpawners() == 0 || !inventoryFull()) return false;
        beginShulkerCycle(resume, "Inventory full - loading the spawners into a shulker box.");
        return true;
    }

    private void beginShulkerCycle(State resume, String message) {
        resumeState = resume;
        noProgressTicks = 0;
        openCooldown = 0;
        chestLookTicks = 0;
        lookTicks = 0;
        shulkerTicks = 0;
        collectTicks = 0;
        boxOpenSeen = false;
        shulkerPos = null;
        info(message);
        state = State.SHULKER_LOAD;
    }

    /** A placed shulker box nearby, or an empty one in the hotbar to place. */
    private boolean shulkerAvailable() {
        if (!findShulkerBlocks(6).isEmpty()) return true;
        return InvUtils.findInHotbar(s -> isShulker(s) && !isLoadedShulker(s)).found();
    }

    private void shulkerFail(String why) {
        warning(why + " - carrying on without shulker loading.");
        shulkerDisabled = true;
        if (mc.player.currentScreenHandler instanceof ShulkerBoxScreenHandler) mc.player.closeHandledScreen();
        waitTicks = 0;
        noProgressTicks = 0;
        state = resumeState;
    }

    /** Open a placed shulker box (or place one from the hotbar) and fill it with spawners. */
    private void shulkerLoadStep() {
        if (mc.player.currentScreenHandler instanceof ShulkerBoxScreenHandler handler) {
            if (!boxOpenSeen) {
                boxOpenSeen = true;
                actionTimer = 5;
                lastCount = countSpawners();
                noProgressTicks = 0;
                say("Shulker box open - loading spawners.");
            }
            if (actionTimer-- > 0) return;
            actionTimer = depositDelay.get();

            int now = countSpawners();
            if (now != lastCount) {
                lastCount = now;
                noProgressTicks = 0;
            } else if (++noProgressTicks > 40) {
                // Not taking any more: it's full. Store it as it is; leftovers go in the ender chest.
                say("Shulker box is full - it will be stored with the rest.");
                finishLoading();
                return;
            }

            // Slots 0-26 are the shulker, the rest is your inventory.
            for (int i = 27; i < handler.slots.size(); i++) {
                if (handler.getSlot(i).getStack().isOf(Items.SPAWNER)) {
                    InvUtils.shiftClick().slotId(i);
                    return;
                }
            }

            // Nothing left to move in.
            finishLoading();
            return;
        }
        boxOpenSeen = false;

        if (++noProgressTicks > 300) {
            shulkerFail("Couldn't open a shulker box (something above it?)");
            return;
        }

        BlockPos target = nearestShulker(6);
        if (target == null) {
            FindItemResult box = InvUtils.findInHotbar(s -> isShulker(s) && !isLoadedShulker(s));
            if (!box.found()) {
                shulkerFail("No shulker box nearby and no empty one in your hotbar");
                return;
            }
            say("Placing a shulker box.");
            BlockPos spot = findPlaceSpot();
            if (spot != null) BlockUtils.place(spot, box, rotate.get(), 50);
            return;
        }

        shulkerPos = target;
        Vec3d center = Vec3d.ofCenter(target);
        if (mc.player.getEyePos().distanceTo(center) > 4.3) {
            say("Walking to the shulker box.");
            walkTo(center);
            return;
        }

        lookAt(target);
        HitResult hit = mc.player.raycast(4.5, 1f, false);
        boolean aimed = hit instanceof BlockHitResult bhr && bhr.getBlockPos().equals(target);
        if (!aimed && ++chestLookTicks < 40) return;

        if (openCooldown-- > 0) return;
        openCooldown = 10;

        say("Opening the shulker box.");
        BlockHitResult use = aimed ? (BlockHitResult) hit : new BlockHitResult(center, Direction.UP, target, false);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, use);
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    private void finishLoading() {
        mc.player.closeHandledScreen();
        boxOpenSeen = false;
        shulkerTicks = 0;
        lookTicks = 0;
        collectTicks = 0;
        shulkerItemsBefore = countShulkerItems();
        state = State.SHULKER_BREAK;
    }

    /** Break the loaded shulker box so it drops as an item. */
    private void shulkerBreakStep() {
        if (shulkerPos == null || !(mc.world.getBlockState(shulkerPos).getBlock() instanceof ShulkerBoxBlock)) {
            waitTicks = 0;
            state = State.SHULKER_COLLECT;
            return;
        }

        if (++shulkerTicks > 400) {
            shulkerFail("Couldn't break the shulker box");
            return;
        }

        Vec3d center = Vec3d.ofCenter(shulkerPos);
        if (mc.player.getEyePos().distanceTo(center) > 4.3) {
            walkTo(center);
            return;
        }

        FindItemResult tool = InvUtils.findInHotbar(s -> s.isIn(ItemTags.PICKAXES));
        if (tool.found()) InvUtils.swap(tool.slot(), false);

        lookAt(shulkerPos);
        if (!isLookingAt(shulkerPos) && ++lookTicks < 40) return;

        say("Breaking the loaded shulker box.");
        breakBlockLegit(shulkerPos);
    }

    /** Pick the broken shulker box back up (and confirm it), then store it in the ender chest. */
    private void shulkerCollectStep() {
        // Confirmed: the shulker box item is in the inventory.
        if (countShulkerItems() > shulkerItemsBefore) {
            Dep mode = countLoadedShulkers() > 0 ? Dep.LOADED_SHULKERS : Dep.ALL;
            depMode = mode;
            startDeposit();
            midRunDeposit = true;
            return;
        }

        ItemEntity drop = nearestShulkerDrop(10);
        if (drop != null) {
            waitTicks = 0;
            if (++collectTicks > 300) {
                shulkerFail("Couldn't pick up the broken shulker box");
                return;
            }
            walkTo(new Vec3d(drop.getX(), drop.getY(), drop.getZ()));
            return;
        }

        // The drop can take a couple of seconds to appear on a laggy server.
        if (++waitTicks < 60) return;

        // The normal collect step will still hunt it down later.
        shulkerFail("The broken shulker box never showed up");
    }

    // ---------------------------------------------------------------- helpers

    private void beep(SoundEvent sound, float pitch) {
        if (!sounds.get() || soundVolume.get() == 0) return;
        try {
            mc.getSoundManager().play(PositionedSoundInstance.master(sound, pitch, soundVolume.get() / 100f));
        } catch (Exception ignored) { }
    }

    private boolean isMineable(BlockPos pos) {
        var b = mc.world.getBlockState(pos).getBlock();
        return b == Blocks.SPAWNER || b instanceof ShulkerBoxBlock;
    }

    private void say(String message) {
        if (!message.equals(lastMsg)) {
            lastMsg = message;
            info(message);
        }
    }

    private List<BlockPos> unskipped(List<BlockPos> list) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos p : list) if (!skipped.contains(p)) out.add(p);
        return out;
    }

    private boolean hasUnskippedTarget() {
        if (!unskipped(findBlocks(spawnerRange.get(), true)).isEmpty()) return true;
        return shulkerLoad.get() && shulkersUnlocked && !unskipped(findShulkerBlocks(spawnerRange.get())).isEmpty();
    }

    /** Anything we want to pick up: spawners, and with shulker-load also shulker boxes. */
    private boolean isCollectible(ItemStack s) {
        return s.isOf(Items.SPAWNER) || (shulkerLoad.get() && isShulker(s));
    }

    private ItemEntity nearestDrop(double radius) {
        ItemEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (ItemEntity e : mc.world.getEntitiesByClass(ItemEntity.class,
                mc.player.getBoundingBox().expand(radius),
                i -> isCollectible(i.getStack()) && !ignoredDrops.contains(i.getId()))) {
            double d = mc.player.squaredDistanceTo(e);
            if (d < best) { best = d; nearest = e; }
        }
        return nearest;
    }

    private ItemEntity nearestShulkerDrop(double radius) {
        ItemEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (ItemEntity e : mc.world.getEntitiesByClass(ItemEntity.class,
                mc.player.getBoundingBox().expand(radius),
                i -> isShulker(i.getStack()))) {
            double d = mc.player.squaredDistanceTo(e);
            if (d < best) { best = d; nearest = e; }
        }
        return nearest;
    }

    private List<BlockPos> findBlocks(int range, boolean spawner) {
        List<BlockPos> list = new ArrayList<>();
        BlockPos c = mc.player.getBlockPos();
        for (int x = -range; x <= range; x++)
            for (int y = -range; y <= range; y++)
                for (int z = -range; z <= range; z++) {
                    BlockPos p = c.add(x, y, z);
                    var b = mc.world.getBlockState(p).getBlock();
                    if (spawner ? b == Blocks.SPAWNER : b == Blocks.ENDER_CHEST) list.add(p);
                }
        list.sort(Comparator.comparingDouble(p -> mc.player.squaredDistanceTo(Vec3d.ofCenter(p))));
        return list;
    }

    private List<BlockPos> findShulkerBlocks(int range) {
        List<BlockPos> list = new ArrayList<>();
        BlockPos c = mc.player.getBlockPos();
        for (int x = -range; x <= range; x++)
            for (int y = -range; y <= range; y++)
                for (int z = -range; z <= range; z++) {
                    BlockPos p = c.add(x, y, z);
                    if (mc.world.getBlockState(p).getBlock() instanceof ShulkerBoxBlock) list.add(p);
                }
        list.sort(Comparator.comparingDouble(p -> mc.player.squaredDistanceTo(Vec3d.ofCenter(p))));
        return list;
    }

    private BlockPos nearestShulker(int range) {
        List<BlockPos> list = findShulkerBlocks(range);
        return list.isEmpty() ? null : list.get(0);
    }

    private boolean inventoryFull() {
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) return false;
        }
        return true;
    }

    private boolean isShulker(ItemStack s) {
        return s.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock;
    }

    /** A shulker box item with anything inside it. */
    private boolean isLoadedShulker(ItemStack s) {
        if (!isShulker(s)) return false;
        ContainerComponent c = s.get(DataComponentTypes.CONTAINER);
        return c != null && c.streamNonEmpty().findAny().isPresent();
    }

    private int countShulkerItems() {
        int n = 0;
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack st = mc.player.getInventory().getStack(i);
            if (isShulker(st)) n += st.getCount();
        }
        return n;
    }

    private int countLoadedShulkers() {
        int n = 0;
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            if (isLoadedShulker(mc.player.getInventory().getStack(i))) n++;
        }
        return n;
    }

    private boolean shouldDeposit(ItemStack s) {
        if (depMode == Dep.LOADED_SHULKERS) return isLoadedShulker(s);
        return s.isOf(Items.SPAWNER) || (shulkerLoad.get() && isShulker(s));
    }

    private int countDeposit() {
        int n = 0;
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (shouldDeposit(s)) n += s.getCount();
        }
        return n;
    }

    private int countSpawners() {
        int n = 0;
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (s.isOf(Items.SPAWNER)) n += s.getCount();
        }
        return n;
    }

    private boolean hasSilkTouch(ItemStack stack) {
        for (RegistryEntry<Enchantment> e : stack.getEnchantments().getEnchantments())
            if (e.matchesKey(Enchantments.SILK_TOUCH)) return true;
        return false;
    }

    private void walkTo(Vec3d target) {
        double dx = target.x - mc.player.getX();
        double dz = target.z - mc.player.getZ();
        mc.player.setYaw((float) Math.toDegrees(Math.atan2(dz, dx)) - 90f);
        mc.options.forwardKey.setPressed(true);
        if (mc.player.horizontalCollision) mc.options.jumpKey.setPressed(true);
    }

    private void stopWalking() {
        if (mc.options != null) {
            mc.options.forwardKey.setPressed(false);
            mc.options.jumpKey.setPressed(false);
            mc.options.sneakKey.setPressed(false);
            mc.options.attackKey.setPressed(false);
        }
    }
}

