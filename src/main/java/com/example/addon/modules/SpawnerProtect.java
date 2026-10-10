package com.example.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Spawner Protect (Meteor Client addon module, Fabric 1.21.11, Mojang mappings)
 *
 * Flow: detect player -> mine every spawner nearby (silk touch pickaxe in hotbar)
 *       -> walk over and collect the drops -> put them in your ender chest -> disconnect.
 *
 * Requirements: silk touch pickaxe in hotbar. An ender chest either already placed
 * nearby, or an ender chest item in your hotbar (the module will place one).
 */
public class SpawnerProtect extends Module {
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

    private enum State { IDLE, MINING, COLLECTING, DEPOSIT, LEAVE }

    private State state = State.IDLE;
    private int ticksActive, waitTicks, actionTimer, noProgressTicks;
    private String reason = "";

    public SpawnerProtect() {
        super(Categories.World, "spawner-protect",
            "Picks up spawners when a player shows up, stashes them in your ender chest, then logs you out.");
    }

    @Override
    public void onActivate() { reset(); }

    @Override
    public void onDeactivate() { stopWalking(); reset(); }

    private void reset() {
        state = State.IDLE;
        ticksActive = waitTicks = actionTimer = noProgressTicks = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;
        stopWalking();

        if (state != State.IDLE) {
            ticksActive++;
            if (timeout.get() > 0 && ticksActive > timeout.get() * 20 && state != State.LEAVE) {
                warning("Timed out, leaving.");
                state = State.LEAVE;
            }
        }

        switch (state) {
            case IDLE -> {
                Player p = findIntruder();
                if (p != null) {
                    reason = p.getName().getString();
                    info("Player detected: " + reason + " - securing spawners.");
                    state = State.MINING;
                }
            }
            case MINING -> mine();
            case COLLECTING -> collect();
            case DEPOSIT -> deposit();
            case LEAVE -> leave();
        }
    }

    // ---------------------------------------------------------------- detection

    private Player findIntruder() {
        for (Player p : mc.level.players()) {
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

        FindItemResult pick = InvUtils.findInHotbar(s -> s.is(ItemTags.PICKAXES) && hasSilkTouch(s));
        if (!pick.found()) {
            error("No silk touch pickaxe in hotbar!");
            state = State.LEAVE;
            return;
        }
        InvUtils.swap(pick.slot(), false);

        BlockPos target = spawners.get(0);
        Vec3 center = Vec3.atCenterOf(target);

        if (mc.player.getEyePosition().distanceTo(center) > 4.3) {
            walkTo(center);
            return;
        }

        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(target), Rotations.getPitch(target),
                () -> BlockUtils.breakBlock(target, true));
        } else {
            BlockUtils.breakBlock(target, true);
        }
    }

    // ---------------------------------------------------------------- collecting

    private void collect() {
        ItemEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (ItemEntity e : mc.level.getEntitiesOfClass(ItemEntity.class,
                mc.player.getBoundingBox().inflate(spawnerRange.get() + 4),
                i -> i.getItem().is(Items.SPAWNER))) {
            double d = mc.player.distanceToSqr(e);
            if (d < best) { best = d; nearest = e; }
        }

        if (nearest != null) {
            waitTicks = 0;
            walkTo(new Vec3(nearest.getX(), nearest.getY(), nearest.getZ()));
            return;
        }

        // Drops can take a moment to appear / get picked up.
        if (++waitTicks < 15) return;

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
            if (mc.player.containerMenu instanceof ChestMenu) mc.player.closeContainer();
            info("All spawners stored.");
            state = State.LEAVE;
            return;
        }

        // Chest GUI is open -> shift-click spawners in.
        if (mc.player.containerMenu instanceof ChestMenu menu) {
            if (actionTimer-- > 0) return;
            actionTimer = depositDelay.get();

            int chestSlots = menu.getRowCount() * 9;
            for (int i = chestSlots; i < menu.slots.size(); i++) {
                if (menu.getSlot(i).getItem().is(Items.SPAWNER)) {
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
        Vec3 center = Vec3.atCenterOf(chest);
        if (mc.player.getEyePosition().distanceTo(center) > 4.3) {
            walkTo(center);
            return;
        }

        if (noProgressTicks % 10 == 0) {
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(center, Direction.UP, chest, false));
        }
    }

    private BlockPos findPlaceSpot() {
        BlockPos base = mc.player.blockPosition();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos p = base.relative(d);
            if (mc.level.getBlockState(p).canBeReplaced()
                && mc.level.getBlockState(p.above()).isAir()
                && !mc.level.getBlockState(p.below()).canBeReplaced()) return p;
        }
        return null;
    }

    // ---------------------------------------------------------------- leaving

    private void leave() {
        stopWalking();
        if (mc.player.containerMenu instanceof ChestMenu) mc.player.closeContainer();

        String msg = "[SpawnerProtect] Player detected (" + reason + ") - spawners secured.";
        if (disconnect.get() && mc.getConnection() != null) {
            mc.getConnection().getConnection().disconnect(Component.literal(msg));
        } else {
            info(msg);
        }
        toggle();
    }

    // ---------------------------------------------------------------- helpers

    private List<BlockPos> findBlocks(int range, boolean spawner) {
        List<BlockPos> list = new ArrayList<>();
        BlockPos c = mc.player.blockPosition();
        for (int x = -range; x <= range; x++)
            for (int y = -range; y <= range; y++)
                for (int z = -range; z <= range; z++) {
                    BlockPos p = c.offset(x, y, z);
                    var state = mc.level.getBlockState(p);
                    if (spawner ? state.is(Blocks.SPAWNER) : state.is(Blocks.ENDER_CHEST)) list.add(p);
                }
        list.sort(Comparator.comparingDouble(p -> mc.player.distanceToSqr(Vec3.atCenterOf(p))));
        return list;
    }

    private int countSpawners() {
        int n = 0;
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(Items.SPAWNER)) n += s.getCount();
        }
        return n;
    }

    private boolean hasSilkTouch(ItemStack stack) {
        for (Holder<Enchantment> e : stack.getEnchantments().keySet())
            if (e.is(Enchantments.SILK_TOUCH)) return true;
        return false;
    }

    private void walkTo(Vec3 target) {
        double dx = target.x - mc.player.getX();
        double dz = target.z - mc.player.getZ();
        mc.player.setYRot((float) Math.toDegrees(Math.atan2(dz, dx)) - 90f);
        mc.options.keyUp.setDown(true);
        if (mc.player.horizontalCollision) mc.options.keyJump.setDown(true);
    }

    private void stopWalking() {
        if (mc.options != null) {
            mc.options.keyUp.setDown(false);
            mc.options.keyJump.setDown(false);
        }
    }
}
