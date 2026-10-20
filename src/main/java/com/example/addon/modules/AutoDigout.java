package com.example.addon.modules; // change to your addon's package

import com.example.addon.AddonTemplate; // change to your addon's main class (for CATEGORY)
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.pathing.PathManagers;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.FluidBlock;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Auto Digout
 *  - AREA mode:   select two corners, it digs everything inside the box and walks to whatever is left.
 *  - TUNNEL mode: locks onto the direction you face and digs a 1x1 or 9x9 tunnel.
 *
 * It re-scans the world every tick instead of tracking "the last block mined", so it works the same
 * whether YOU break each block or your pickaxe breaks a whole 1x1 / 9x9 chunk around it.
 */
public class AutoDigout extends Module {

    public enum Mode {
        Area("Area"),
        Tunnel("Tunnel");

        private final String title;
        Mode(String title) { this.title = title; }
        @Override public String toString() { return title; }
    }

    public enum Size {
        OneByOne("1x1", 1, 2),     // 1 wide, 2 tall so you can walk through it
        NineByNine("9x9", 9, 9);   // 9 wide, 9 tall, starting at feet level

        private final String title;
        public final int width;
        public final int height;

        Size(String title, int width, int height) {
            this.title = title;
            this.width = width;
            this.height = height;
        }

        @Override public String toString() { return title; }
    }

    public enum ToolMode {
        Held("Held item"),         // use whatever you're holding (best for custom pickaxes)
        Slot("Hotbar slot"),       // always switch to a specific hotbar slot
        Fastest("Fastest tool");   // vanilla-style best tool for the block

        private final String title;
        ToolMode(String title) { this.title = title; }
        @Override public String toString() { return title; }
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgArea = settings.createGroup("Area");
    private final SettingGroup sgTunnel = settings.createGroup("Tunnel");
    private final SettingGroup sgRender = settings.createGroup("Render");

    // ---- General ----
    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Dig a selected area or a tunnel.")
        .defaultValue(Mode.Area)
        .build()
    );

    private final Setting<ToolMode> toolMode = sgGeneral.add(new EnumSetting.Builder<ToolMode>()
        .name("tool")
        .description("Which item to mine with.")
        .defaultValue(ToolMode.Held)
        .build()
    );

    private final Setting<Integer> toolSlot = sgGeneral.add(new IntSetting.Builder()
        .name("tool-slot")
        .description("Hotbar slot (1-9) to switch to when tool is 'Hotbar slot'.")
        .defaultValue(1)
        .range(1, 9)
        .sliderRange(1, 9)
        .visible(() -> toolMode.get() == ToolMode.Slot)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Look at each block before breaking it (needed on some servers).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> multiBreak = sgGeneral.add(new BoolSetting.Builder()
        .name("multi-break")
        .description("Break several blocks per tick. Only turn on for instant-mine tools or creative; in survival it cancels block progress.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> blocksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("blocks-per-tick")
        .description("Max blocks per tick when multi-break is on (or you're in creative).")
        .defaultValue(4)
        .min(1)
        .sliderMax(10)
        .build()
    );

    private final Setting<Double> reach = sgGeneral.add(new DoubleSetting.Builder()
        .name("reach")
        .description("Max distance to a block for it to be broken.")
        .defaultValue(4.5)
        .min(1)
        .sliderMax(6)
        .build()
    );

    private final Setting<Boolean> swing = sgGeneral.add(new BoolSetting.Builder()
        .name("swing")
        .description("Swing your hand when breaking blocks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug")
        .description("Prints what the module is doing to chat once a second.")
        .defaultValue(false)
        .build()
    );

    // ---- Area ----
    private final Setting<Keybind> pos1Key = sgArea.add(new KeybindSetting.Builder()
        .name("set-corner-1")
        .description("Sets corner 1 to the block you're looking at.")
        .defaultValue(Keybind.none())
        .action(this::setCorner1)
        .build()
    );

    private final Setting<Keybind> pos2Key = sgArea.add(new KeybindSetting.Builder()
        .name("set-corner-2")
        .description("Sets corner 2 to the block you're looking at.")
        .defaultValue(Keybind.none())
        .action(this::setCorner2)
        .build()
    );

    private final Setting<Keybind> clearKey = sgArea.add(new KeybindSetting.Builder()
        .name("clear-selection")
        .description("Clears both corners.")
        .defaultValue(Keybind.none())
        .action(this::clearSelection)
        .build()
    );

    private final Setting<Double> selectRange = sgArea.add(new DoubleSetting.Builder()
        .name("select-range")
        .description("How far away you can pick a corner by looking at a block.")
        .defaultValue(128)
        .min(5)
        .sliderMax(256)
        .build()
    );

    private final Setting<Boolean> walkToArea = sgArea.add(new BoolSetting.Builder()
        .name("walk-to-area")
        .description("Pathfinds to the next block that's out of reach (uses Meteor's pathing / Baritone).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> topDown = sgArea.add(new BoolSetting.Builder()
        .name("top-down")
        .description("Dig the highest layer first (safer, avoids falling into the pit). Off = closest first.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> maxVolume = sgArea.add(new IntSetting.Builder()
        .name("max-volume")
        .description("Largest selection (in blocks) you're allowed to set.")
        .defaultValue(500_000)
        .min(1)
        .sliderMax(2_000_000)
        .build()
    );

    private final Setting<Boolean> disableWhenDone = sgArea.add(new BoolSetting.Builder()
        .name("disable-when-done")
        .description("Turns the module off once the area is empty.")
        .defaultValue(true)
        .build()
    );

    // ---- Tunnel ----
    private final Setting<Size> size = sgTunnel.add(new EnumSetting.Builder<Size>()
        .name("size")
        .description("Cross-section of the tunnel.")
        .defaultValue(Size.NineByNine)
        .build()
    );

    private final Setting<Integer> length = sgTunnel.add(new IntSetting.Builder()
        .name("length")
        .description("Tunnel length before the module turns itself off.")
        .defaultValue(32)
        .min(1)
        .sliderMax(256)
        .build()
    );

    private final Setting<Boolean> autoWalk = sgTunnel.add(new BoolSetting.Builder()
        .name("auto-walk")
        .description("Walks forward once the blocks in front of you are cleared.")
        .defaultValue(true)
        .build()
    );

    // ---- Render ----
    private final Setting<Boolean> renderSelection = sgRender.add(new BoolSetting.Builder()
        .name("render-selection")
        .description("Draws the selected area.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .defaultValue(new SettingColor(255, 170, 0, 35))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .defaultValue(new SettingColor(255, 170, 0, 255))
        .build()
    );

    // ---- State ----
    private BlockPos corner1, corner2;
    private Direction dir;
    private BlockPos origin;
    private BlockPos current;   // block we're currently mining (survival mines one block at a time)
    private BlockPos walkGoal;  // where we last asked the pathfinder to go
    private int ticks;
    private int blocked;        // blocks Meteor says it can't break (debug)

    public AutoDigout() {
        super(AddonTemplate.CATEGORY, "auto-digout", "Automatically digs out a selected area, or a 1x1 / 9x9 tunnel.");
    }

    // ------------------------------------------------------------------ selection

    private BlockPos lookedAtBlock() {
        if (mc.player == null) return null;
        HitResult hit = mc.player.raycast(selectRange.get(), 1f, false);
        if (hit instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) return bhr.getBlockPos();
        return null;
    }

    private void setCorner1() {
        BlockPos pos = lookedAtBlock();
        if (pos == null) { warning("Not looking at a block."); return; }
        corner1 = pos;
        info("Corner 1 set to %d, %d, %d.", pos.getX(), pos.getY(), pos.getZ());
        reportVolume();
    }

    private void setCorner2() {
        BlockPos pos = lookedAtBlock();
        if (pos == null) { warning("Not looking at a block."); return; }
        corner2 = pos;
        info("Corner 2 set to %d, %d, %d.", pos.getX(), pos.getY(), pos.getZ());
        reportVolume();
    }

    private void clearSelection() {
        corner1 = null;
        corner2 = null;
        info("Selection cleared.");
    }

    private void reportVolume() {
        if (corner1 == null || corner2 == null) return;
        long volume = volume();
        if (volume > maxVolume.get()) {
            warning("Selection is %d blocks, over the max-volume of %d. Pick a smaller area.", volume, maxVolume.get());
        } else {
            info("Selection is %d blocks.", volume);
        }
    }

    private BlockPos min() {
        return new BlockPos(
            Math.min(corner1.getX(), corner2.getX()),
            Math.min(corner1.getY(), corner2.getY()),
            Math.min(corner1.getZ(), corner2.getZ()));
    }

    private BlockPos max() {
        return new BlockPos(
            Math.max(corner1.getX(), corner2.getX()),
            Math.max(corner1.getY(), corner2.getY()),
            Math.max(corner1.getZ(), corner2.getZ()));
    }

    private long volume() {
        BlockPos a = min(), b = max();
        return (long) (b.getX() - a.getX() + 1) * (b.getY() - a.getY() + 1) * (b.getZ() - a.getZ() + 1);
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onActivate() {
        ticks = 0;
        current = null;
        walkGoal = null;
        if (mc.player == null) { toggle(); return; }

        if (mode.get() == Mode.Area) {
            if (corner1 == null || corner2 == null) {
                error("Set both corners first (see the Area settings).");
                toggle();
                return;
            }
            if (volume() > maxVolume.get()) {
                error("Selection is over the max-volume setting.");
                toggle();
            }
        } else {
            dir = mc.player.getHorizontalFacing();
            origin = mc.player.getBlockPos();
        }
    }

    @Override
    public void onDeactivate() {
        if (mc.options != null) mc.options.forwardKey.setPressed(false);
        if (walkGoal != null && PathManagers.get().isPathing()) PathManagers.get().stop();
        walkGoal = null;
        current = null;
    }

    // ------------------------------------------------------------------ rendering

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!renderSelection.get() || mode.get() != Mode.Area) return;
        if (corner1 == null && corner2 == null) return;

        BlockPos a = corner1 != null ? corner1 : corner2;
        BlockPos b = corner2 != null ? corner2 : corner1;

        event.renderer.box(
            Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
            Math.max(a.getX(), b.getX()) + 1, Math.max(a.getY(), b.getY()) + 1, Math.max(a.getZ(), b.getZ()) + 1,
            sideColor.get(), lineColor.get(), shapeMode.get(), 0);
    }

    // ------------------------------------------------------------------ ticking

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        ticks++;
        blocked = 0;

        if (mode.get() == Mode.Area) digArea();
        else digTunnel();
    }

    private boolean isDiggable(BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        if (state.isAir() || state.getBlock() instanceof FluidBlock) return false;
        if (!BlockUtils.canBreak(pos, state)) {
            blocked++;
            return false;
        }
        return true;
    }

    private boolean inReach(BlockPos pos, Vec3d eyes) {
        double r = reach.get();
        return eyes.squaredDistanceTo(Vec3d.ofCenter(pos)) <= r * r;
    }

    private void equipTool(BlockPos pos) {
        switch (toolMode.get()) {
            case Slot -> InvUtils.swap(toolSlot.get() - 1, false);
            case Fastest -> {
                FindItemResult tool = InvUtils.findFastestTool(mc.world.getBlockState(pos));
                if (tool.found() && tool.isHotbar()) InvUtils.swap(tool.slot(), false);
            }
            case Held -> { /* use whatever is in hand */ }
        }
    }

    private void breakOne(BlockPos pos) {
        equipTool(pos);
        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), () -> BlockUtils.breakBlock(pos, swing.get()));
        } else {
            BlockUtils.breakBlock(pos, swing.get());
        }
    }

    /**
     * Survival block breaking is progress-based and only tracks ONE block at a time, so we stick with a single
     * target until it's gone. Switching targets every tick (or hitting several blocks per tick) resets the
     * progress and nothing ever breaks. multi-break / creative skip that and hit several per tick.
     */
    private void mine(List<BlockPos> sorted) {
        if (sorted.isEmpty()) {
            current = null;
            return;
        }

        if (mc.player.isCreative() || multiBreak.get()) {
            int n = 0;
            for (BlockPos pos : sorted) {
                if (n++ >= blocksPerTick.get()) break;
                breakOne(pos);
            }
            return;
        }

        if (current == null || !sorted.contains(current)) current = sorted.get(0);
        breakOne(current);
    }

    // ---- Area mode ----

    private void digArea() {
        if (corner1 == null || corner2 == null) return;

        BlockPos min = min(), max = max();
        BlockPos p = mc.player.getBlockPos();
        int r = (int) Math.ceil(reach.get());
        Vec3d eyes = mc.player.getEyePos();

        // Only scan the part of the box that's actually near the player
        int x0 = Math.max(min.getX(), p.getX() - r), x1 = Math.min(max.getX(), p.getX() + r);
        int y0 = Math.max(min.getY(), p.getY() - r), y1 = Math.min(max.getY(), p.getY() + r + 1);
        int z0 = Math.max(min.getZ(), p.getZ() - r), z1 = Math.min(max.getZ(), p.getZ() + r);

        List<BlockPos> targets = new ArrayList<>();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!inReach(pos, eyes)) continue;
                    if (isDiggable(pos)) targets.add(pos);
                }
            }
        }

        Comparator<BlockPos> byDistance = Comparator.comparingDouble(pos -> eyes.squaredDistanceTo(Vec3d.ofCenter(pos)));
        if (topDown.get()) targets.sort(Comparator.<BlockPos>comparingInt(pos -> -pos.getY()).thenComparing(byDistance));
        else targets.sort(byDistance);

        if (debug.get() && ticks % 20 == 0) {
            info("[area] in reach: %d, not breakable: %d, mode: %s, creative: %b, pathing: %b",
                targets.size(), blocked, toolMode.get(), mc.player.isCreative(), PathManagers.get().isPathing());
        }

        if (!targets.isEmpty()) {
            // Something to mine: stop walking and mine it
            if (walkGoal != null && PathManagers.get().isPathing()) PathManagers.get().stop();
            walkGoal = null;
            mine(targets);
            return;
        }

        current = null;

        // Nothing in reach. Look for what's left in the box (once a second, it's a bigger scan).
        if (ticks % 20 != 0) return;

        BlockPos next = findNextRemaining(min, max, p);
        if (next == null) {
            info("Area cleared.");
            if (disableWhenDone.get()) toggle();
            return;
        }

        if (walkToArea.get()) {
            BlockPos stand = standingSpot(next);
            if (stand.equals(walkGoal) && PathManagers.get().isPathing()) return;
            walkGoal = stand;
            if (debug.get()) info("[area] walking to %d, %d, %d (next block %d, %d, %d)",
                stand.getX(), stand.getY(), stand.getZ(), next.getX(), next.getY(), next.getZ());
            PathManagers.get().moveTo(stand, false);
        } else if (ticks % 100 == 0) {
            info("Blocks remain in the area but are out of reach. Move closer.");
        }
    }

    /** Nearest remaining block; with top-down it only looks at the highest layer that still has blocks. */
    private BlockPos findNextRemaining(BlockPos min, BlockPos max, BlockPos from) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        for (int y = max.getY(); y >= min.getY(); y--) {
            for (int x = min.getX(); x <= max.getX(); x++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!isDiggable(pos)) continue;
                    double d = pos.getSquaredDistance(from);
                    if (d < bestDist) {
                        bestDist = d;
                        best = pos;
                    }
                }
            }
            if (topDown.get() && best != null) break;
        }
        return best;
    }

    /** A spot next to the block where the player can stand (air, air above, solid below). */
    private BlockPos standingSpot(BlockPos target) {
        BlockPos[] candidates = {
            target.up(), target.north(), target.south(), target.east(), target.west()
        };
        for (BlockPos c : candidates) {
            boolean feetFree = mc.world.getBlockState(c).isAir();
            boolean headFree = mc.world.getBlockState(c.up()).isAir();
            boolean floorSolid = mc.world.getBlockState(c.down()).isSolidBlock(mc.world, c.down());
            if (feetFree && headFree && floorSolid) return c;
        }
        return target.up();
    }

    // ---- Tunnel mode ----

    private void digTunnel() {
        if (dir == null || origin == null) return;

        int progress = dir.getAxis() == Direction.Axis.X
            ? (mc.player.getBlockX() - origin.getX()) * dir.getOffsetX()
            : (mc.player.getBlockZ() - origin.getZ()) * dir.getOffsetZ();

        if (progress >= length.get()) {
            info("Finished digging %d blocks.", length.get());
            toggle();
            return;
        }

        BlockPos base = dir.getAxis() == Direction.Axis.X
            ? new BlockPos(mc.player.getBlockX(), origin.getY(), origin.getZ())
            : new BlockPos(origin.getX(), origin.getY(), mc.player.getBlockZ());

        Direction side = dir.rotateYClockwise();
        int half = size.get().width / 2;
        int height = size.get().height;
        int startDepth = size.get() == Size.OneByOne ? 1 : 0;
        int maxDepth = Math.min((int) Math.ceil(reach.get()), length.get() - progress);

        Vec3d eyes = mc.player.getEyePos();

        List<BlockPos> targets = new ArrayList<>();
        boolean frontClear = true; // the 2 slices directly ahead are empty

        for (int d = startDepth; d <= maxDepth; d++) {
            for (int w = -half; w <= half; w++) {
                for (int h = 0; h < height; h++) {
                    BlockPos pos = base.offset(dir, d).offset(side, w).up(h);
                    if (!isDiggable(pos)) continue;

                    if (d <= 2) frontClear = false;
                    if (inReach(pos, eyes)) targets.add(pos);
                }
            }
        }

        targets.sort(Comparator.comparingDouble(pos -> eyes.squaredDistanceTo(Vec3d.ofCenter(pos))));

        if (debug.get() && ticks % 20 == 0) {
            info("[tunnel] progress: %d/%d, in reach: %d, not breakable: %d, front clear: %b",
                progress, length.get(), targets.size(), blocked, frontClear);
        }

        mine(targets);

        mc.options.forwardKey.setPressed(autoWalk.get() && frontClear);
    }
}
