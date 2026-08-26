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
 * Entry point. Wires up:
 *  - random-moment trigger (checked every N seconds)
 *  - on-damage trigger (health decreased since last tick)
 *  - on-attack trigger (player hits an entity)
 *  - on-action trigger (break block / use block / use item)
 *  - the "frozen ad" state: cancels all those same actions and zeroes
 *    movement input while active, without pausing the world/game.
 *
 * Ported to Minecraft 1.21.11 / Yarn 1.21.11+build.4:
 *  - TypedActionResult was removed in 1.21.2 -> plain ActionResult constants
 *  - PositionedSoundInstance.master(...) -> ui(...) (UI sound category)
 *  - DrawContext.drawTexture now requires a RenderPipeline argument
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
    private int randomCheckTickCounter = 0;
    private float lastHealth = -1f;

    @Override
    public void onInitializeClient() {
        config = AdGifConfig.load();

        // --- rendering ---
        HudRenderCallback.EVENT.register(this::renderIfActive);

        // --- per-tick logic: random trigger, damage detection, freeze enforcement ---
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);

        // --- attack trigger + cancel while active ---
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (active) {
                return ActionResult.FAIL;
            }
            tryTrigger(config.chanceOnAttack);
            return ActionResult.PASS;
        });

        // --- "any action" triggers + cancel while active ---
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (active) {
                return ActionResult.FAIL;
            }
            tryTrigger(config.chanceOnAction);
            return ActionResult.PASS;
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (active) {
                return ActionResult.FAIL;
            }
            tryTrigger(config.chanceOnAction);
            return ActionResult.PASS;
        });

        // Since MC 1.21.2, UseItemCallback returns a plain ActionResult
        // (TypedActionResult<ItemStack> no longer exists).
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (active) {
                return ActionResult.FAIL;
            }
            tryTrigger(config.chanceOnAction);
            return ActionResult.PASS;
        });

        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (active) {
                return false; // cancel breaking while the ad is showing
            }
            tryTrigger(config.chanceOnAction);
            return true;
        });
    }

    private void onClientTick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null) {
            return;
        }

        if (active) {
            long elapsed = System.currentTimeMillis() - adStartMs;
            long durationMs = (long) (config.durationSeconds * 1000);
            if (elapsed >= durationMs) {
                active = false;
            } else {
                freezeInputThisTick(client);
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

    /** Zeroes out movement/action key state for this tick, without pausing the world. */
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
        }
    }

    private void tryTrigger(double chance) {
        if (active) {
            return; // ignore any trigger while the ad is already showing
        }
        if (random.nextDouble() < chance) {
            startAd();
        }
    }

    private void startAd() {
        ensureGifLoaded();
        if (gif == null || gif.isEmpty()) {
            return; // nothing to show, don't "freeze" the player for no reason
        }
        active = true;
        adStartMs = System.currentTimeMillis();

        if (config.playSound) {
            try {
                // master(...) was renamed to ui(...): UI sounds now play in the
                // "UI" category instead of "Master". Order: (sound, pitch, volume).
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

        // Since 1.21.6 drawTexture requires a RenderPipeline as the first
        // argument. GUI_TEXTURED is the standard pipeline for textured blits
        // (supports alpha/transparency, which the gif overlay needs).
        drawContext.drawTexture(RenderPipelines.GUI_TEXTURED, frame, x, y, 0.0F, 0.0F, w, h, gif.width, gif.height);
    }
}
