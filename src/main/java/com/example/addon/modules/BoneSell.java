package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bone Sell (Meteor Client addon module, Fabric 1.21.11, Yarn mappings)
 *
 * You aim at a spawner yourself. When your crosshair is on a spawner you haven't sold recently,
 * the macro starts: open its menu, click the button whose name contains the sell text, then the one
 * that contains the confirm text, and close the menu. That spawner is then marked as sold for
 * `cooldown-seconds`, so you can move on to the next one.
 *
 * It never clicks anything whose name contains the "never click" text (drop).
 * If a spawner won't open, it leaves the server, rejoins, and carries on.
 */
public class BoneSell extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgButtons = settings.createGroup("Buttons");
    private final SettingGroup sgRejoin = settings.createGroup("Rejoin");

    // ---- General
    private final Setting<Integer> cooldownSeconds = sgGeneral.add(new IntSetting.Builder()
        .name("cooldown-seconds").description("After a spawner is sold, don't sell that same spawner again for this many seconds.")
        .defaultValue(30).min(1).max(3600).sliderRange(1, 300).build());

    private final Setting<Integer> clickDelay = sgGeneral.add(new IntSetting.Builder()
        .name("click-delay-ticks").description("Ticks to wait between steps (opening, sell, confirm, close). Lower = faster. 20 ticks = 1 second.")
        .defaultValue(4).min(1).max(40).sliderMax(20).build());

    private final Setting<Double> reach = sgGeneral.add(new DoubleSetting.Builder()
        .name("reach").description("How far away a spawner can be for the macro to start.")
        .defaultValue(4.5).min(1).max(5).sliderMax(5).decimalPlaces(1).build());

    private final Setting<Boolean> debugMenu = sgGeneral.add(new BoolSetting.Builder()
        .name("debug-menu").description("Print the name of every item in the menu to chat. Turn this on to find the right button words.")
        .defaultValue(false).build());

    // ---- Buttons
    private final Setting<String> sellText = sgButtons.add(new StringSetting.Builder()
        .name("sell-button-text").description("Click the menu item whose name contains this text (not case sensitive).")
        .defaultValue("sell").build());

    private final Setting<String> confirmText = sgButtons.add(new StringSetting.Builder()
        .name("confirm-button-text").description("Then click the item whose name contains this text. Leave empty if there is no confirm step.")
        .defaultValue("confirm").build());

    private final Setting<Boolean> greenPaneFallback = sgButtons.add(new BoolSetting.Builder()
        .name("green-pane-fallback").description("If no confirm button is found by its name or description, click the green glass pane in the menu.")
        .defaultValue(true).build());

    private final Setting<String> neverText = sgButtons.add(new StringSetting.Builder()
        .name("never-click-text").description("Never click an item whose name contains any of these words (separate with commas). Keeps it from dropping the bones.")
        .defaultValue("drop").build());

    // ---- Rejoin
    private final Setting<Double> openTimeout = sgRejoin.add(new DoubleSetting.Builder()
        .name("open-timeout-seconds").description("If the spawner menu hasn't opened after this long (while you keep looking at it), leave and rejoin.")
        .defaultValue(5).min(1).max(30).sliderMax(15).decimalPlaces(1).build());

    private final Setting<Integer> rejoinDelay = sgRejoin.add(new IntSetting.Builder()
        .name("rejoin-delay-seconds").description("Seconds to wait before rejoining.")
        .defaultValue(8).min(1).max(60).sliderMax(30).build());

    private final Setting<Integer> maxRejoins = sgRejoin.add(new IntSetting.Builder()
        .name("max-rejoins-in-a-row").description("Stop the module if this many rejoins happen in a row without a successful sell.")
        .defaultValue(3).min(1).max(10).sliderMax(10).build());

    private enum Step { WAITING, OPENING, SELL, CONFIRM, CLOSING, REJOIN_WAIT, REJOINING }

    private Step step = Step.WAITING;
    private BlockPos target;
    private final Map<BlockPos, Long> soldAt = new HashMap<>();

    private int cooldownTicks, timer, openTicks, openCooldown, tries;
    private int failedSells, rejoinStreak;
    private int rejoinTimer, connectTicks, connectAttempts;
    private boolean disconnected;
    private ServerInfo lastServer;
    private String lastMsg = "";

    public BoneSell() {
        super(AddonTemplate.CATEGORY, "bone-sell",
            "Look at a spawner and it opens it, presses sell, then confirm. Never drops the bones. Rejoins if a spawner won't open.");
    }

    @Override
    public void onActivate() {
        step = Step.WAITING;
        target = null;
        soldAt.clear();
        cooldownTicks = timer = openTicks = openCooldown = tries = 0;
        failedSells = rejoinStreak = 0;
        rejoinTimer = connectTicks = connectAttempts = 0;
        disconnected = false;
        lastServer = null;
        lastMsg = "";
    }

    @Override
    public void onDeactivate() {
        if (mc.player != null && menuOpen()) mc.player.closeHandledScreen();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (step == Step.REJOIN_WAIT || step == Step.REJOINING) {
            handleRejoin();
            return;
        }

        if (mc.player == null || mc.world == null) return;

        if (timer > 0) {
            timer--;
            return;
        }

        switch (step) {
            case WAITING -> waiting();
            case OPENING -> opening();
            case SELL -> sell();
            case CONFIRM -> confirm();
            case CLOSING -> closing();
            default -> { }
        }
    }

    // ---------------------------------------------------------------- steps

    private void waiting() {
        if (cooldownTicks > 0) {
            cooldownTicks--;
            return;
        }
        if (menuOpen()) return; // you're in some menu already, don't interfere

        BlockHitResult hit = lookedAtSpawner();
        if (hit == null) return;

        BlockPos pos = hit.getBlockPos();
        if (recentlySold(pos)) return; // already sold this one - look at another

        target = pos;
        openTicks = 0;
        openCooldown = 0;
        step = Step.OPENING;
    }

    private void opening() {
        if (menuOpen()) {
            say("Spawner menu open.");
            rejoinStreak = 0;
            logMenu();
            tries = 0;
            timer = clickDelay.get();
            step = Step.SELL;
            return;
        }

        // If you look away before it opens, just cancel - that's not a failure.
        BlockHitResult hit = lookedAtSpawner();
        if (hit == null || !hit.getBlockPos().equals(target)) {
            say("You looked away - cancelled.");
            target = null;
            step = Step.WAITING;
            return;
        }

        if (++openTicks > openTimeout.get() * 20) {
            failOpen();
            return;
        }

        if (openCooldown-- > 0) return;
        openCooldown = 20; // retry the click once a second

        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    private void sell() {
        if (!menuOpen()) {
            warning("The menu closed before I could press sell.");
            finishCycle(false);
            return;
        }

        int slot = findButton(sellText.get());
        if (slot < 0) {
            if (++tries > 20) {
                warning("Couldn't find a button containing \"" + sellText.get() + "\". Turn on debug-menu to see the names.");
                closeMenu();
                if (++failedSells >= 3) {
                    warning("Couldn't sell 3 times in a row - turning off.");
                    if (isActive()) toggle();
                    return;
                }
                finishCycle(false);
                return;
            }
            timer = clickDelay.get();
            return;
        }

        click(slot);
        say("Pressed sell.");
        tries = 0;
        timer = clickDelay.get();
        step = Step.CONFIRM;
    }

    private void confirm() {
        // The sell may have finished straight away and closed the menu.
        if (!menuOpen()) {
            finishCycle(true);
            return;
        }

        String text = confirmText.get().trim();
        if (text.isEmpty()) {
            step = Step.CLOSING;
            return;
        }

        int slot = findButton(text);
        // The confirm button may have a blank or unusual name: after a few tries, use the green pane.
        if (slot < 0 && greenPaneFallback.get() && tries >= 3) slot = findGreenPane();

        if (slot < 0) {
            if (++tries == 3) logMenu(); // show what's in the menu (if debug-menu is on)
            if (tries > 20) {
                warning("Couldn't find the confirm button. Turn on debug-menu to see the item names.");
                step = Step.CLOSING;
                return;
            }
            timer = clickDelay.get();
            return;
        }

        logMenu();
        click(slot);
        say("Pressed confirm.");
        timer = clickDelay.get();
        step = Step.CLOSING;
    }

    private void closing() {
        closeMenu();
        finishCycle(true);
    }

    private void finishCycle(boolean sold) {
        if (sold && target != null) {
            soldAt.put(target, System.currentTimeMillis());
            failedSells = 0;
            rejoinStreak = 0;
            info("Sold. Look at the next spawner.");
        }
        cooldownTicks = sold ? clickDelay.get() * 2 : 40;
        target = null;
        step = Step.WAITING;
    }

    private boolean recentlySold(BlockPos pos) {
        Long t = soldAt.get(pos);
        return t != null && System.currentTimeMillis() - t < cooldownSeconds.get() * 1000L;
    }

    // ---------------------------------------------------------------- leave + rejoin

    private void failOpen() {
        warning("The spawner won't open - leaving and rejoining.");

        if (++rejoinStreak > maxRejoins.get()) {
            warning("Too many failed opens in a row - turning off.");
            if (isActive()) toggle();
            return;
        }

        ServerInfo server = mc.getCurrentServerEntry();
        if (server == null || mc.getNetworkHandler() == null) {
            warning("Rejoining only works on multiplayer servers - skipping it.");
            target = null;
            cooldownTicks = 100;
            step = Step.WAITING;
            return;
        }

        lastServer = server;
        rejoinTimer = rejoinDelay.get() * 20;
        connectTicks = 0;
        connectAttempts = 0;
        disconnected = false;
        target = null;
        step = Step.REJOIN_WAIT;
        try {
            mc.getNetworkHandler().getConnection().disconnect(Text.literal("[BoneSell] Spawner wouldn't open - rejoining."));
        } catch (Exception ignored) { }
    }

    private void handleRejoin() {
        boolean inWorld = mc.player != null && mc.world != null;
        if (!inWorld) disconnected = true;

        if (inWorld) {
            if (disconnected) { // we left and are back (by our own reconnect, or another module's)
                info("Back in the game - continuing.");
                disconnected = false;
                cooldownTicks = 60; // let the area load
                step = Step.WAITING;
            }
            return;
        }

        if (step == Step.REJOIN_WAIT) {
            if (--rejoinTimer > 0) return;
            connect();
            step = Step.REJOINING;
            return;
        }

        // REJOINING: still connecting. Retry every 30 seconds if it hasn't worked.
        if (++connectTicks > 600) {
            if (connectAttempts >= 10) {
                warning("Couldn't rejoin after 10 tries - turning off.");
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
            if (isActive()) toggle();
            return;
        }
        ConnectScreen.connect(new TitleScreen(), mc, ServerAddress.parse(lastServer.address), lastServer, false, null);
    }

    // ---------------------------------------------------------------- helpers

    /** The spawner your crosshair is on (within reach), or null. */
    private BlockHitResult lookedAtSpawner() {
        HitResult hit = mc.player.raycast(reach.get(), 1f, false);
        if (hit instanceof BlockHitResult bhr
            && mc.world.getBlockState(bhr.getBlockPos()).getBlock() == Blocks.SPAWNER) return bhr;
        return null;
    }

    private boolean menuOpen() {
        return mc.player.currentScreenHandler != mc.player.playerScreenHandler;
    }

    private void closeMenu() {
        if (menuOpen()) mc.player.closeHandledScreen();
    }

    /** Slot id of the first menu item (not your own inventory) whose name matches. */
    private int findButton(String text) {
        String wanted = text.toLowerCase().trim();
        if (wanted.isEmpty()) return -1;
        List<String> never = neverWords();

        ScreenHandler handler = mc.player.currentScreenHandler;
        for (Slot slot : handler.slots) {
            if (slot.inventory == mc.player.getInventory()) continue; // skip your own items
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) continue;
            String name = stack.getName().getString().toLowerCase();
            if (!name.contains(wanted) && !loreText(stack).contains(wanted)) continue;
            boolean blocked = false;
            for (String n : never) if (name.contains(n)) { blocked = true; break; }
            if (!blocked) return slot.id;
        }
        return -1;
    }

    /** The first green / lime glass pane in the menu (not your own inventory). */
    private int findGreenPane() {
        ScreenHandler handler = mc.player.currentScreenHandler;
        for (Slot slot : handler.slots) {
            if (slot.inventory == mc.player.getInventory()) continue;
            ItemStack stack = slot.getStack();
            if (stack.isOf(Items.LIME_STAINED_GLASS_PANE) || stack.isOf(Items.GREEN_STAINED_GLASS_PANE)) return slot.id;
        }
        return -1;
    }

    /** The item's description lines, lower-cased, joined into one string. */
    private String loreText(ItemStack stack) {
        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (lore == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Text line : lore.lines()) sb.append(line.getString().toLowerCase()).append(' ');
        return sb.toString();
    }

    private List<String> neverWords() {
        List<String> out = new ArrayList<>();
        for (String part : neverText.get().split(",")) {
            String w = part.toLowerCase().trim();
            if (!w.isEmpty()) out.add(w);
        }
        return out;
    }

    private void click(int slotId) {
        ScreenHandler handler = mc.player.currentScreenHandler;
        mc.interactionManager.clickSlot(handler.syncId, slotId, 0, SlotActionType.PICKUP, mc.player);
    }

    private void logMenu() {
        if (!debugMenu.get() || !menuOpen()) return;
        ScreenHandler handler = mc.player.currentScreenHandler;
        info("--- menu contents ---");
        for (Slot slot : handler.slots) {
            if (slot.inventory == mc.player.getInventory()) continue;
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) continue;
            info("slot " + slot.id + ": \"" + stack.getName().getString() + "\" [" + Registries.ITEM.getId(stack.getItem()).getPath() + "]");
        }
    }

    private void say(String message) {
        if (!message.equals(lastMsg)) {
            lastMsg = message;
            info(message);
        }
    }
}
