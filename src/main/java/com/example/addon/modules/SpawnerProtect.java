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
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Spawner Protect (Meteor Client addon module, Fabric 1.21.11, Yarn mappings)
 *
 * Flow: detect player -> mine every spawner nearby (crouching, silk touch pickaxe in hotbar)
 *       -> walk over and collect the drops -> double check nothing was missed
 *       -> put them in your ender chest -> disconnect.
 *
 * Requirements: silk touch pickaxe in hotbar. An ender chest either already placed
 * nearby, or an ender chest item in your hotbar (the module will place one).
 */
public class SpawnerProtect extends Module {
    private static final int MAX_VERIFY_PASSES = 3;

    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Integer> detectRange = sg.add(new IntSetting.Builder()
        .name("detect-range").description("Range to detect other players. 0 = any loaded player.")
        .defaultValue(64).min(0).sliderMax(128).build());

    private final Setting<List<String>> ignoredNames = sg.add(new StringListSetting.Builder()
        .name("ignored-players").description("Player names that never trigger the module.")
        .defaultValue(new ArrayList<>()).build());

    private final Setting<Integer> spawnerRange = sg.add(new IntSetting.Builder()
        .name("spawner-range").description("How far to look for spawners.")
        .defaultValue(12).min(1).sliderMax(16).build());

    private final Setting<Integer> depositDelay = sg.add(new IntSetting.Builder()
        .name("deposit-delay").description("Ticks between each shift-click into the ender chest.")
        .defaultValue(2).min(0).sliderMax(10).build());

    private final Setting<Integer> lookSpeed = sg.add(new IntSetting.Builder()
        .name("look-speed").description("Max degrees your view turns per tick when facing a spawner.")
        .defaultValue(25).min(1).sliderMax(90).build());

    private final Setting<Boolean> shulkerLoad = sg.add(new BoolSetting.Builder()
        .name("shulker-load").description("When your inventory is full, put the spawners in a shulker box (a placed one nearby, or one from your hotbar), break it, store it in the ender chest, then carry on.")
        .defaultValue(false).build());

    private final Setting<Integer> skipTimeout = sg.add(new IntSetting.Builder()
        .name("skip-timeout").description("If a spawner can't be broken within this many seconds (anti-cheat), skip it, mine the rest, then try it once more. If it still fails, store the rest, leave and rejoin.")
        .defaultValue(10).min(2).sliderMax(30).build());

    private final Setting<Boolean> rotate = sg.add(new BoolSetting.Builder()
        .name("rotate").description("Silent rotation when placing the ender chest.")
        .defaultValue(true).build());

    private final Setting<Integer> timeout = sg.add(new IntSetting.Builder()
        .name("timeout-seconds").description("Safety: disconnect after this long even if not finished. 0 = off.")
        .defaultValue(180).min(0).sliderMax(600).build());

    private final Setting<Boolean> disconnect = sg.add(new BoolSetting.Builder()
        .name("disconnect-when-done").description("Leave the server when finished.")
        .defaultValue(true).build());

    private final Setting<Boolean> rejoinRecheck = sg.add(new BoolSetting.Builder()
        .name("rejoin-and-recheck").description("After storing the spawners and leaving, rejoin the server and check again for spawners left. Repeats if any are found.")
        .defaultValue(false).build());

    private final Setting<Integer> rejoinDelay = sg.add(new IntSetting.Builder()
        .name("rejoin-delay").description("Seconds to wait before rejoining.")
        .defaultValue(8).min(1).sliderMax(60).visible(rejoinRecheck::get).build());

    private final Setting<Integer> maxRounds = sg.add(new IntSetting.Builder()
        .name("max-recheck-rounds").description("Give up after this many leave/rejoin/recheck rounds.")
        .defaultValue(3).min(1).sliderMax(10).visible(rejoinRecheck::get).build());

    private enum State { IDLE, MINING, COLLECTING, VERIFY, DEPOSIT, LEAVE, REJOIN_WAIT, REJOINING, RECHECK, SHULKER_LOAD, SHULKER_BREAK, SHULKER_COLLECT }

    private State state = State.IDLE;
    private int ticksActive, waitTicks, actionTimer, noProgressTicks, verifyPasses;
    private String reason = "";
    private ServerInfo lastServer;
    private BlockPos lookTarget, mineTarget;
    private int lookTicks, mineTicks, openCooldown, chestLookTicks;
    private final Set<BlockPos> skipped = new HashSet<>();
    private boolean retriedSkipped, forceRejoin, chestOpenSeen;
    private String lastMsg = "";
    private State resumeState = State.MINING;
    private boolean shulkerDisabled, depositingShulkers, boxOpenSeen;
    private BlockPos shulkerPos;
    private int shulkerTicks, shulkersStored;
    private int rejoinTimer, connectTicks, connectAttempts, rounds;

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
        ticksActive = waitTicks = actionTimer = noProgressTicks = verifyPasses = 0;
        rejoinTimer = connectTicks = connectAttempts = rounds = 0;
        lastServer = null;
        lookTarget = mineTarget = null;
        lookTicks = mineTicks = openCooldown = chestLookTicks = 0;
        skipped.clear();
        retriedSkipped = forceRejoin = chestOpenSeen = false;
        lastMsg = "";
        resumeState = State.MINING;
        shulkerDisabled = depositingShulkers = boxOpenSeen = false;
        shulkerPos = null;
        shulkerTicks = shulkersStored = 0;
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
            if (timeout.get() > 0 && ticksActive > timeout.get() * 20 && state != State.LEAVE) {
                warning("Timed out, leaving.");
                state = State.LEAVE;
            }
        }

        switch (state) {
            case IDLE -> {
                PlayerEntity p = findIntruder();
                if (p != null) {
                    reason = p.getName().getString();
                    info("Player detected: " + reason + " - securing spawners.");
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

    // ---------------------------------------------------------------- detection

    private PlayerEntity findIntruder() {
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player || p.isSpectator()) continue;
            if (Friends.get().isFriend(p)) continue;
            String name = p.getName().getString();
            if (ignoredNames.get().stream().anyMatch(n -> n.equalsIgnoreCase(name))) continue;
            if (detectRange.get() > 0 && mc.player.distanceTo(p) > detectRange.get()) continue;
            return p;
        }
        return null;
    }

    // ---------------------------------------------------------------- mining

    private void mine() {
        if (maybeStartShulker(State.MINING)) return;

        List<BlockPos> all = findBlocks(spawnerRange.get(), true);
        List<BlockPos> spawners = new ArrayList<>();
        for (BlockPos p : all) if (!skipped.contains(p)) spawners.add(p);

        if (spawners.isEmpty()) {
            if (!all.isEmpty()) {
                // Only spawners that failed earlier are left.
                if (!retriedSkipped) {
                    retriedSkipped = true;
                    skipped.clear();
                    say("Everything else is mined - retrying the spawner(s) that failed.");
                    return;
                }
                forceRejoin = true;
                warning("Couldn't mine " + all.size() + " spawner(s) (anti-cheat?). Storing the rest, then leaving and rejoining.");
            }
            waitTicks = 0;
            state = State.COLLECTING;
            return;
        }

        FindItemResult pick = InvUtils.findInHotbar(s -> s.isIn(ItemTags.PICKAXES) && hasSilkTouch(s));
        if (!pick.found()) {
            error("No silk touch pickaxe in hotbar!");
            state = State.LEAVE;
            return;
        }
        InvUtils.swap(pick.slot(), false);

        BlockPos target = spawners.get(0);
        Vec3d center = Vec3d.ofCenter(target);

        if (mc.player.getEyePos().distanceTo(center) > 4.3) {
            walkTo(center);
            return;
        }

        mc.options.sneakKey.setPressed(true);

        if (!target.equals(mineTarget)) {
            mineTarget = target;
            mineTicks = 0;
        }

        if (!target.equals(lookTarget)) {
            lookTarget = target;
            lookTicks = 0;
        }
        lookAt(target);
        // Wait until the crosshair is on the spawner. If something is in the way
        // and it never lines up, stop waiting after 2 seconds and mine anyway.
        if (!isLookingAt(target) && ++lookTicks < 40) return;

        // Anti-cheat sometimes refuses one spawner: give up on it for now.
        if (++mineTicks > skipTimeout.get() * 20) {
            skipped.add(target);
            mineTarget = null;
            warning("Couldn't break the spawner at " + target.getX() + " " + target.getY() + " " + target.getZ() + " - skipping it for now.");
            return;
        }

        BlockUtils.breakBlock(target, true);
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
        ItemEntity nearest = nearestSpawnerDrop(spawnerRange.get() + 4);
        if (nearest != null) {
            if (maybeStartShulker(State.COLLECTING)) return;
            waitTicks = 0;
            walkTo(new Vec3d(nearest.getX(), nearest.getY(), nearest.getZ()));
            return;
        }

        // Drops can take a moment to appear / get picked up.
        if (++waitTicks < 15) return;

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

        if (hasUnskippedSpawner()) {
            verifyPasses++;
            warning("Double check: a spawner is still placed, mining it (" + verifyPasses + "/" + MAX_VERIFY_PASSES + ").");
            state = State.MINING;
            return;
        }

        if (nearestSpawnerDrop(spawnerRange.get() + 8) != null) {
            verifyPasses++;
            warning("Double check: a spawner is still on the ground, collecting it (" + verifyPasses + "/" + MAX_VERIFY_PASSES + ").");
            waitTicks = 0;
            state = State.COLLECTING;
            return;
        }

        info("Double check passed: no spawners left nearby.");
        finishPickup();
    }

    private void finishPickup() {
        if (countSpawners() > 0) {
            noProgressTicks = 0;
            depositingShulkers = false;
            state = State.DEPOSIT;
        } else if (shulkersStored > 0) {
            info("Everything is stored in shulker boxes in your ender chest.");
            state = State.LEAVE;
        } else {
            warning("No spawners found in inventory.");
            state = State.LEAVE;
        }
    }

    // ---------------------------------------------------------------- ender chest

    private void deposit() {
        if (countDeposit() == 0) {
            if (mc.player.currentScreenHandler instanceof GenericContainerScreenHandler) mc.player.closeHandledScreen();
            if (depositingShulkers) {
                depositingShulkers = false;
                shulkersStored++;
                info("Loaded shulker box stored in the ender chest - carrying on.");
                waitTicks = 0;
                state = resumeState;
                return;
            }
            info("All spawners stored.");
            state = State.LEAVE;
            return;
        }

        // Chest GUI is open -> shift-click spawners in.
        if (mc.player.currentScreenHandler instanceof GenericContainerScreenHandler handler) {
            if (!chestOpenSeen) {
                chestOpenSeen = true;
                actionTimer = 5; // short pause so the server and client agree on the contents
                say(depositingShulkers ? "Ender chest open - moving the loaded shulker in." : "Ender chest open - moving spawners in.");
            }
            if (actionTimer-- > 0) return;
            actionTimer = depositDelay.get();

            int chestSlots = handler.getRows() * 9;
            for (int i = chestSlots; i < handler.slots.size(); i++) {
                if (shouldDeposit(handler.getSlot(i).getStack())) {
                    InvUtils.shiftClick().slotId(i);
                    noProgressTicks = 0;
                    return;
                }
            }
            return;
        }
        chestOpenSeen = false;

        if (++noProgressTicks > 300) {
            warning("Couldn't deposit everything (couldn't open the ender chest, or it's full).");
            state = State.LEAVE;
            return;
        }

        List<BlockPos> chests = findBlocks(8, false);
        if (chests.isEmpty()) {
            FindItemResult ec = InvUtils.findInHotbar(Items.ENDER_CHEST);
            if (!ec.found()) {
                error("No ender chest nearby and none in hotbar.");
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

    // ---------------------------------------------------------------- leaving

    private void leave() {
        stopWalking();
        if (mc.player.currentScreenHandler instanceof GenericContainerScreenHandler) mc.player.closeHandledScreen();

        String msg = "[SpawnerProtect] Player detected (" + reason + ") - spawners secured.";

        ServerInfo server = mc.getCurrentServerEntry();
        boolean wantRejoin = rejoinRecheck.get() || forceRejoin;
        if (wantRejoin && server == null) {
            warning("Rejoining only works on multiplayer servers - skipping it.");
        }

        if (wantRejoin && server != null && mc.getNetworkHandler() != null) {
            lastServer = server;
            rejoinTimer = rejoinDelay.get() * 20;
            connectTicks = 0;
            connectAttempts = 0;
            state = State.REJOIN_WAIT;
            mc.getNetworkHandler().getConnection().disconnect(Text.literal(msg));
            return;
        }

        if (disconnect.get() && mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().getConnection().disconnect(Text.literal(msg));
        } else {
            info(msg);
        }
        toggle();
    }

    // ---------------------------------------------------------------- rejoin + recheck

    private void handleRejoin() {
        // Already back in the world -> start checking.
        if (mc.player != null && mc.world != null) {
            if (state == State.REJOINING) {
                info("Rejoined, checking for leftover spawners...");
                waitTicks = 0;
                ticksActive = 0;
                skipped.clear();
                retriedSkipped = false;
                forceRejoin = false;
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
                toggle();
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

        if (!findBlocks(spawnerRange.get(), true).isEmpty()) {
            if (!nextRound("A spawner is still placed")) return;
            state = State.MINING;
            return;
        }

        if (nearestSpawnerDrop(spawnerRange.get() + 8) != null) {
            if (!nextRound("A spawner is still on the ground")) return;
            waitTicks = 0;
            state = State.COLLECTING;
            return;
        }

        if (countSpawners() > 0) {
            if (!nextRound("Spawners are still in your inventory")) return;
            noProgressTicks = 0;
            state = State.DEPOSIT;
            return;
        }

        info("Recheck passed: no spawners left. All done.");
        toggle();
    }

    private boolean nextRound(String why) {
        rounds++;
        if (rounds > maxRounds.get()) {
            warning(why + ", but the max number of rounds was reached. Stopping.");
            toggle();
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

        resumeState = resume;
        noProgressTicks = 0;
        openCooldown = 0;
        chestLookTicks = 0;
        lookTicks = 0;
        shulkerTicks = 0;
        boxOpenSeen = false;
        shulkerPos = null;
        info("Inventory full - loading the spawners into a shulker box.");
        state = State.SHULKER_LOAD;
        return true;
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
                say("Shulker box open - loading spawners.");
            }
            if (actionTimer-- > 0) return;
            actionTimer = depositDelay.get();

            // Slots 0-26 are the shulker, the rest is your inventory.
            for (int i = 27; i < handler.slots.size(); i++) {
                if (handler.getSlot(i).getStack().isOf(Items.SPAWNER)) {
                    InvUtils.shiftClick().slotId(i);
                    noProgressTicks = 0;
                    return;
                }
            }

            // Nothing left to move in.
            mc.player.closeHandledScreen();
            boxOpenSeen = false;
            shulkerTicks = 0;
            lookTicks = 0;
            state = State.SHULKER_BREAK;
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
        BlockUtils.breakBlock(shulkerPos, true);
    }

    /** Pick the broken shulker box back up, then store it in the ender chest. */
    private void shulkerCollectStep() {
        ItemEntity drop = nearestShulkerDrop(8);
        if (drop != null) {
            waitTicks = 0;
            walkTo(new Vec3d(drop.getX(), drop.getY(), drop.getZ()));
            return;
        }

        if (++waitTicks < 15) return;

        if (countLoadedShulkers() > 0) {
            noProgressTicks = 0;
            openCooldown = 0;
            chestLookTicks = 0;
            depositingShulkers = true;
            state = State.DEPOSIT;
        } else {
            shulkerFail("Couldn't find the loaded shulker box after breaking it");
        }
    }

    // ---------------------------------------------------------------- helpers

    private void say(String message) {
        if (!message.equals(lastMsg)) {
            lastMsg = message;
            info(message);
        }
    }

    private boolean hasUnskippedSpawner() {
        for (BlockPos p : findBlocks(spawnerRange.get(), true)) if (!skipped.contains(p)) return true;
        return false;
    }

    private ItemEntity nearestSpawnerDrop(double radius) {
        ItemEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (ItemEntity e : mc.world.getEntitiesByClass(ItemEntity.class,
                mc.player.getBoundingBox().expand(radius),
                i -> i.getStack().isOf(Items.SPAWNER))) {
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

    private boolean inventoryFull() {
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) return false;
        }
        return true;
    }

    private boolean isShulker(ItemStack s) {
        return s.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock;
    }

    private boolean isLoadedShulker(ItemStack s) {
        if (!isShulker(s)) return false;
        ContainerComponent c = s.get(DataComponentTypes.CONTAINER);
        return c != null && c.streamNonEmpty().anyMatch(x -> x.isOf(Items.SPAWNER));
    }

    private int countLoadedShulkers() {
        int n = 0;
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            if (isLoadedShulker(mc.player.getInventory().getStack(i))) n++;
        }
        return n;
    }

    private boolean shouldDeposit(ItemStack s) {
        return depositingShulkers ? isLoadedShulker(s) : s.isOf(Items.SPAWNER);
    }

    private int countDeposit() {
        return depositingShulkers ? countLoadedShulkers() : countSpawners();
    }

    private BlockPos nearestShulker(int range) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        BlockPos c = mc.player.getBlockPos();
        for (int x = -range; x <= range; x++)
            for (int y = -range; y <= range; y++)
                for (int z = -range; z <= range; z++) {
                    BlockPos p = c.add(x, y, z);
                    if (!(mc.world.getBlockState(p).getBlock() instanceof ShulkerBoxBlock)) continue;
                    double d = mc.player.squaredDistanceTo(Vec3d.ofCenter(p));
                    if (d < bestDist) { bestDist = d; best = p; }
                }
        return best;
    }

    private ItemEntity nearestShulkerDrop(double radius) {
        ItemEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (ItemEntity e : mc.world.getEntitiesByClass(ItemEntity.class,
                mc.player.getBoundingBox().expand(radius),
                i -> isLoadedShulker(i.getStack()))) {
            double d = mc.player.squaredDistanceTo(e);
            if (d < best) { best = d; nearest = e; }
        }
        return nearest;
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
        }
    }
}
