package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.minecraft.client.network.PlayerListEntry;

import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Auto TPA (Meteor Client addon module, Fabric 1.21.11)
 *
 * You measure two things about a streamer:
 *   - how far behind real time their stream is (stream delay), and
 *   - how long they count down before their teleports turn on (countdown) and how long they stay on (window).
 *
 * Then, when YOU see the countdown start on their (delayed) stream, press the trigger key.
 * The module works out when their teleports really turn on and sends /tpa at that moment.
 *
 *   real time the window opens, measured from your key press = countdown - stream delay
 *
 * The built-in stopwatch key helps you measure the numbers: press it to start, press it again to stop.
 */
public class AutoTpa extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTiming = settings.createGroup("Timing");
    private final SettingGroup sgKeys = settings.createGroup("Keys");

    // ---- General
    private final Setting<String> target = sgGeneral.add(new StringSetting.Builder()
        .name("streamer").description("The streamer's in-game name.")
        .defaultValue("").build());

    private final Setting<String> command = sgGeneral.add(new StringSetting.Builder()
        .name("command").description("The teleport command to send, without the slash (the player name is added after it).")
        .defaultValue("tpa").build());

    // ---- Timing
    private final Setting<Double> countdown = sgTiming.add(new DoubleSetting.Builder()
        .name("countdown-seconds").description("How long the streamer counts down, in real time, from when you see it start to when their teleports turn on.")
        .defaultValue(10).min(0).sliderMax(60).decimalPlaces(2).build());

    private final Setting<Double> streamDelay = sgTiming.add(new DoubleSetting.Builder()
        .name("stream-delay-seconds").description("How many seconds your view of the stream is behind real time.")
        .defaultValue(8).min(0).sliderMax(60).decimalPlaces(2).build());

    private final Setting<Double> windowLength = sgTiming.add(new DoubleSetting.Builder()
        .name("window-seconds").description("How long the streamer keeps their teleports on. Extra sends stop once it's over.")
        .defaultValue(5).min(0.1).sliderMax(60).decimalPlaces(2).build());

    private final Setting<Boolean> pingCompensation = sgTiming.add(new BoolSetting.Builder()
        .name("ping-compensation").description("Send a little early to make up for your connection's travel time (half your ping).")
        .defaultValue(true).build());

    private final Setting<Integer> offsetMs = sgTiming.add(new IntSetting.Builder()
        .name("fine-tune-ms").description("Fine adjustment in milliseconds. Positive = send later, negative = send earlier.")
        .defaultValue(0).min(-2000).max(2000).sliderRange(-500, 500).build());

    private final Setting<Integer> resendCount = sgTiming.add(new IntSetting.Builder()
        .name("extra-sends").description("How many extra times to send the command after the first one, while the window is still open. 0 = send once.")
        .defaultValue(0).min(0).max(10).sliderMax(10).build());

    private final Setting<Integer> resendInterval = sgTiming.add(new IntSetting.Builder()
        .name("extra-send-gap-ms").description("Milliseconds between the extra sends.")
        .defaultValue(300).min(50).max(2000).sliderMax(1000).build());

    // ---- Keys
    private final Setting<Keybind> triggerKey = sgKeys.add(new KeybindSetting.Builder()
        .name("trigger-key").description("Press this the moment you see the streamer's countdown start on your stream.")
        .defaultValue(Keybind.none()).action(this::trigger).build());

    private final Setting<Keybind> cancelKey = sgKeys.add(new KeybindSetting.Builder()
        .name("cancel-key").description("Cancel a pending send.")
        .defaultValue(Keybind.none()).action(this::cancel).build());

    private final Setting<Keybind> stopwatchKey = sgKeys.add(new KeybindSetting.Builder()
        .name("stopwatch-key").description("Built-in timer: press to start, press again to stop and see the time in chat. Use it to measure the stream delay, countdown and window.")
        .defaultValue(Keybind.none()).action(this::stopwatch).build());

    private ScheduledExecutorService scheduler;
    private final AtomicInteger generation = new AtomicInteger();
    private long stopwatchStart = -1;

    public AutoTpa() {
        super(AddonTemplate.CATEGORY, "auto-tpa",
            "Sends /tpa to a streamer at the exact moment their teleports turn on, using your measured stream delay.");
    }

    @Override
    public void onActivate() {
        stopwatchStart = -1;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AutoTpa-timer");
            t.setDaemon(true);
            return t;
        });
        info("Ready. Set the streamer and your timings, then press the trigger key when you see their countdown start.");
    }

    @Override
    public void onDeactivate() {
        generation.incrementAndGet(); // cancels anything pending
        if (scheduler != null) scheduler.shutdownNow();
        scheduler = null;
    }

    // ---------------------------------------------------------------- trigger

    private void trigger() {
        if (scheduler == null || mc.player == null || mc.getNetworkHandler() == null) return;

        String name = target.get().trim();
        if (name.isEmpty()) {
            error("Set the streamer's name first.");
            return;
        }

        double pingMs = pingCompensation.get() ? latencyMs() / 2.0 : 0.0; // one-way trip
        double startSec = countdown.get() - streamDelay.get() - pingMs / 1000.0 + offsetMs.get() / 1000.0;
        double endSec = startSec + windowLength.get();

        if (endSec <= 0) {
            warning("Your stream delay is longer than the countdown plus the window - their teleports are already off.");
            return;
        }

        int myGeneration = generation.incrementAndGet(); // replaces any earlier pending send
        long firstMs = Math.max(0L, Math.round(startSec * 1000.0));
        long endMs = Math.round(endSec * 1000.0);

        int total = 1 + resendCount.get();
        int scheduled = 0;
        for (int i = 0; i < total; i++) {
            long at = firstMs + (long) i * resendInterval.get();
            if (i > 0 && at > endMs) break; // window is over by then
            scheduled++;
            final int number = i + 1;
            scheduler.schedule(() -> mc.execute(() -> send(name, myGeneration, number)), at, TimeUnit.MILLISECONDS);
        }

        if (startSec < 0) {
            warning("Their teleports should already be on - sending right now.");
        } else {
            info("Sending /" + command.get() + " " + name + " in " + fmt(startSec) + " s ("
                + "countdown " + fmt(countdown.get()) + " - delay " + fmt(streamDelay.get())
                + " - ping " + Math.round(pingMs) + " ms). Sends: " + scheduled + ".");
        }
    }

    private void send(String name, int myGeneration, int number) {
        if (generation.get() != myGeneration || !isActive() || mc.player == null) return;
        ChatUtils.sendPlayerMsg("/" + command.get() + " " + name);
        info("Sent /" + command.get() + " " + name + " (" + number + ").");
    }

    private void cancel() {
        generation.incrementAndGet();
        info("Cancelled.");
    }

    // ---------------------------------------------------------------- stopwatch

    private void stopwatch() {
        long now = System.nanoTime();
        if (stopwatchStart < 0) {
            stopwatchStart = now;
            info("Timer started.");
        } else {
            double seconds = (now - stopwatchStart) / 1_000_000_000.0;
            stopwatchStart = -1;
            info("Timer stopped: " + fmt(seconds) + " seconds.");
        }
    }

    // ---------------------------------------------------------------- helpers

    private int latencyMs() {
        try {
            PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
            return entry == null ? 0 : entry.getLatency();
        } catch (Exception e) {
            return 0;
        }
    }

    private String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
