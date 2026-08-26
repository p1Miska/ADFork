package com.trollmods.adgif;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;

import java.util.Random;

/**
 * Entry point.
 *
 * Uses the SHARED config instance (AdGifConfig.get()), so changes made in the
 * Mod Menu settings screen apply immediately without a game restart.
 *
 * Input freeze lives in START_CLIENT_TICK: that hook fires at the very head
 * of the client tick, BEFORE vanilla processes key clicks and movement input.
 *
 * A cooldown after each ad prevents any trigger from instantly restarting it.
 *
 * The ad ends when the GIF finishes playing (or when the configured max
 * duration is reached, whichever comes first) - so the overlay disappears
 * together with the sound instead of freezing on the last frame.
 */
public class AdGifClient implements ClientModInitializer {

    private final Random random = new Random();
    private AdGifConfig config;
    private GifAnimation gif;
    private boolean gifLoadAttempted = false;

    private static final SoundEvent AD_SOUND =
            SoundEvent.of(Identifier.of("adgif", "ad"));

    private boolean active = false;
    private long adStartMs = 0L;
    /** No trigger may fire until this timestamp (ms). */
    private long cooldownUntilMs = 0L;
    private int randomCheckTickCounter = 0;
    private float lastHealth = -1f;

    @Override
    public void onInitializeClient() {
        config = AdGifConfig.get(); // shared with the settings screen

        // --- rendering ---
        HudRenderCallback.EVENT.register(this::renderIfActive);

        // --- FREEZE: very start of the tick, before input handling/sampling ---
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (active && client.player != null) {
                freezeInputThisTick(client);
            }
        });

        // --- per-tick logic: random trigger, damage detection, ad duration ---
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);

        // --- attack trigger + cancel while active ---
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (active || onCooldown()) {
                return active ? ActionResult.FAIL : ActionResult.PASS;
            }
            tryTrigger(config.chanceOnAttack);
            return ActionResult.PASS;
        });

        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (active || onCooldown()) {
                return active ? ActionResult.FAIL : ActionResult.PASS;
            }
            tryTrigger(config.chanceOnAction);
            return ActionResult.PASS;
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (active || onCooldown()) {
                return active ? ActionResult.FAIL : ActionResult.PASS;
            }
            tryTrigger(config.chanceOnAction);
            return ActionResult.PASS;
        });

        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (active || onCooldown()) {
                return active ? ActionResult.FAIL : ActionResult.PASS;
            }
            tryTrigger(config.chanceOnAction);
            return ActionResult.PASS;
        });

        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (active) {
                return false;
            }
            if (!onCooldown()) {
                tryTrigger(config.chanceOnAction);
            }
            return true;
        });
    }

    private boolean onCooldown() {
        return System.currentTimeMillis() < cooldownUntilMs;
    }

    /**
     * How long the ad should actually last, in ms:
     * the configured duration acts as an UPPER LIMIT, but a multi-frame gif
     * cuts the ad off as soon as it finishes playing - so the picture never
     * sits frozen on its last frame while the (equally long) sound is done.
     */
    private long effectiveAdDurationMs() {
        long cap = (long) (config.durationSeconds * 1000);
        if (gif != null && !gif.isEmpty() && gif.frameCount() > 1) {
            cap = Math.min(cap, gif.totalDurationMs());
        }
        return Math.max(1, cap);
    }

    private void onClientTick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null) {
            return;
        }

        if (active) {
            long elapsed = System.currentTimeMillis() - adStartMs;
            if (elapsed >= effectiveAdDurationMs()) {
                active = false;
                cooldownUntilMs = System.currentTimeMillis()
                        + (long) (config.cooldownSeconds * 1000);
            }
            lastHealth = player.getHealth();
            return;
        }

        float health = player.getHealth();
        if (lastHealth >= 0f && health < lastHealth) {
            tryTrigger(config.chanceOnDamage);
        }
        lastHealth = health;

        randomCheckTickCounter++;
        int intervalTicks = (int) Math.max(20, config.randomCheckIntervalSeconds * 20);
        if (randomCheckTickCounter >= intervalTicks) {
            randomCheckTickCounter = 0;
            tryTrigger(config.chanceRandom);
        }
    }

    /**
     * Called at the START of every tick while the ad is showing:
     * clears held keys AND drains queued clicks so vanilla finds nothing to act on.
     */
    private void freezeInputThisTick(MinecraftClient client) {
        var options = client.options;
        KeyBinding[] toBlock = new KeyBinding[]{
                options.forwardKey, options.backKey, options.leftKey, options.rightKey,
                options.jumpKey, options.sneakKey, options.sprintKey,
                options.attackKey, options.useKey,
                options.inventoryKey, options.dropKey,
                options.swapHandsKey, options.pickItemKey
        };
        for (KeyBinding key : toBlock) {
            key.setPressed(false);
            while (key.wasPressed()) {
                // discard queued presses
            }
        }
    }

    private void tryTrigger(double chance) {
        if (active || onCooldown()) {
            return;
        }
        if (random.nextDouble() < chance) {
            startAd();
        }
    }

    private void startAd() {
        ensureGifLoaded();
        if (gif == null || gif.isEmpty()) {
            return;
        }
        active = true;
        adStartMs = System.currentTimeMillis();

        if (config.playSound) {
            try {
                MinecraftClient.getInstance().getSoundManager().play(
                        PositionedSoundInstance.ui(AD_SOUND, 1.0F, (float) config.soundVolume)
                );
            } catch (Exception e) {
                System.err.println("[adgif] Could not play ad sound (did you add assets/adgif/sounds/ad.ogg "
                        + "and sounds.json?): " + e);
            }
        }
    }

    private void ensureGifLoaded() {
        if (gifLoadAttempted) {
            return;
        }
        gifLoadAttempted = true;
        MinecraftClient client = MinecraftClient.getInstance();
        Identifier location = Identifier.of(config.gifPath.split(":", 2)[0], config.gifPath.split(":", 2)[1]);
        gif = GifAnimation.load(client.getResourceManager(), location);
    }

    private void renderIfActive(DrawContext drawContext, net.minecraft.client.render.RenderTickCounter tickCounter) {
        if (!active || gif == null || gif.isEmpty()) {
            return;
        }
        long elapsed = System.currentTimeMillis() - adStartMs;
        Identifier frame = gif.frameAt(elapsed);
        if (frame == null) {
            return;
        }

        int w = (int) Math.max(1, gif.width * config.scale);
        int h = (int) Math.max(1, gif.height * config.scale);
        int x = (drawContext.getScaledWindowWidth() - w) / 2;
        int y = (drawContext.getScaledWindowHeight() - h) / 2;

        // IMPORTANT: overload with an explicit REGION size, so the WHOLE gif
        // is scaled into the w x h rectangle (the short overload would crop
        // a corner of the texture when scale < 1).
        drawContext.drawTexture(RenderPipelines.GUI_TEXTURED, frame, x, y,
                0.0F, 0.0F,
                w, h,
                gif.width, gif.height,
                gif.width, gif.height);
    }
}