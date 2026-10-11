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
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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

    private final Setting<Boolean> ignoreFriends = sg.add(new BoolSetting.Builder()
        .name("ignore-friends").description("Friends never trigger the module.")
        .defaultValue(true).build());

    private final Setting<List<String>> ignoredNames = sg.add(new StringListSetting.Builder()
        .name("ignored-players").description("Player names that never trigger the module.")
        .defaultValue(new ArrayList<>()).build());

    private final Setting<Integer> spawnerRange = sg.add(new IntSetting.Builder()
        .name("spawner-range").description("How far to look for spawners.")
        .defaultValue(12).min(1).sliderMax(16).build());

    private final Setting<Boolean> crouchWhileMining = sg.add(new BoolSetting.Builder()
        .name("crouch-while-mining").description("Hold sneak while breaking spawners.")
        .defaultValue(true).build());

    private final Setting<Boolean> doubleCheck = sg.add(new BoolSetting.Builder()
        .name("double-check").description("Before storing, re-scan for spawners still placed or still on the ground.")
        .defaultValue(true).build());

    private final Setting<Integer> depositDelay = sg.add(new IntSetting.Builder()
        .name("deposit-delay").description("Ticks between each shift-click into the ender chest.")
        .defaultValue(2).min(0).sliderMax(10).build());

    private final Setting<Boolean> rotate = sg.add(new BoolSetting.Builder()
        .name("rotate").description("Look at blocks when breaking/placing.").defaultValue(true).build());

    private final Setting<Integer> timeout = sg.add(new IntSetting.Builder()
        .name("timeout-seconds").description("Safety: disconnect after this long even if not finished. 0 = off.")
        .defaultValue(90).min(0).sliderMax(300).build());

    private final Setting<Boolean> disconnect = sg.add(new BoolSetting.Builder()
        .name("disconnect-when-done").description("Leave the server when finished.")
        .defaultValue(true).build());

    private enum State { IDLE, MINING, COLLECTING, VERIFY, DEPOSIT, LEAVE }

    private State state = State.IDLE;
    private int ticksActive, waitTicks, actionTimer, noProgressTicks, verifyPasses;
    private String reason = "";

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
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
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
        }
    }

    // ---------------------------------------------------------------- detection

    private PlayerEntity findIntruder() {
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player || p.isSpectator()) continue;
            if (ignoreFriends.get() && Friends.get().isFriend(p)) continue;
            String name = p.getName().getString();
            if (ignoredNames.get().stream().anyMatch(n -> n.equalsIgnoreCase(name))) continue;
            if (detectRange.get() > 0 && mc.player.distanceTo(p) > detectRange.get()) continue;
            return p;
        }
        return null;
    }

    // ---------------------------------------------------------------- mining

    private void mine() {
        List<BlockPos> spawners = findBlocks(spawnerRange.get(), true);
        if (spawners.isEmpty()) {
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

        if (crouchWhileMining.get()) mc.options.sneakKey.setPressed(true);

        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(target), Rotations.getPitch(target),
                () -> BlockUtils.breakBlock(target, true));
        } else {
            BlockUtils.breakBlock(target, true);
        }
    }

    // ---------------------------------------------------------------- collecting

    private void collect() {
        ItemEntity nearest = nearestSpawnerDrop(spawnerRange.get() + 4);
        if (nearest != null) {
            waitTicks = 0;
            walkTo(new Vec3d(nearest.getX(), nearest.getY(), nearest.getZ()));
            return;
        }

        // Drops can take a moment to appear / get picked up.
        if (++waitTicks < 15) return;

        if (doubleCheck.get() && verifyPasses < MAX_VERIFY_PASSES) {
            waitTicks = 0;
            state = State.VERIFY;
            return;
        }
        finishPickup();
    }

    // ---------------------------------------------------------------- double check

    private void verify() {
        if (++waitTicks < 20) return; // give the server a second to settle

        if (!findBlocks(spawnerRange.get(), true).isEmpty()) {
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
            state = State.DEPOSIT;
        } else {
            warning("No spawners found in inventory.");
            state = State.LEAVE;
        }
    }

    // ---------------------------------------------------------------- ender chest

    private void deposit() {
        if (countSpawners() == 0) {
            if (mc.player.currentScreenHandler instanceof GenericContainerScreenHandler) mc.player.closeHandledScreen();
            info("All spawners stored.");
            state = State.LEAVE;
            return;
        }

        // Chest GUI is open -> shift-click spawners in.
        if (mc.player.currentScreenHandler instanceof GenericContainerScreenHandler handler) {
            if (actionTimer-- > 0) return;
            actionTimer = depositDelay.get();

            int chestSlots = handler.getRows() * 9;
            for (int i = chestSlots; i < handler.slots.size(); i++) {
                if (handler.getSlot(i).getStack().isOf(Items.SPAWNER)) {
                    InvUtils.shiftClick().slotId(i);
                    noProgressTicks = 0;
                    return;
                }
            }
            return;
        }

        // Chest is full / nothing moving.
        if (++noProgressTicks > 200) {
            warning("Couldn't deposit everything (ender chest full?).");
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
            BlockPos spot = findPlaceSpot();
            if (spot != null) BlockUtils.place(spot, ec, rotate.get(), 50);
            return;
        }

        BlockPos chest = chests.get(0);
        Vec3d center = Vec3d.ofCenter(chest);
        if (mc.player.getEyePos().distanceTo(center) > 4.3) {
            walkTo(center);
            return;
        }

        if (noProgressTicks % 10 == 0) {
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
                new BlockHitResult(center, Direction.UP, chest, false));
        }
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
        if (disconnect.get() && mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().getConnection().disconnect(Text.literal(msg));
        } else {
            info(msg);
        }
        toggle();
    }

    // ---------------------------------------------------------------- helpers

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
