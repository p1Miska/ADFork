package com.trollmods.adgif;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.DoubleSupplier;

/**
 * Settings screen opened from Mod Menu. Edits the SHARED config instance,
 * so every change applies live (even while dragging). Saved to disk on close.
 */
public class AdGifConfigScreen extends Screen {

    private static final int SLIDER_W = 150;

    private final Screen parent;
    private final AdGifConfig config;

    public AdGifConfigScreen(Screen parent) {
        super(Text.literal("Настройки AdGif"));
        this.parent = parent;
        this.config = AdGifConfig.get(); // shared instance, NOT a fresh copy
    }

    @Override
    protected void init() {
        int leftX = this.width / 2 - 155;
        int rightX = this.width / 2 + 5;
        int rowH = 24;
        int y0 = 45;

        // --- left column: chances ---
        addSlider(leftX, y0,             "Случайный шанс",        0, 1, 0.01,
                () -> config.chanceRandom,               v -> config.chanceRandom = v, AdGifConfigScreen::pct);
        addSlider(leftX, y0 + rowH,      "Шанс при уроне",        0, 1, 0.01,
                () -> config.chanceOnDamage,             v -> config.chanceOnDamage = v, AdGifConfigScreen::pct);
        addSlider(leftX, y0 + rowH * 2,  "Шанс при ударе",        0, 1, 0.01,
                () -> config.chanceOnAttack,             v -> config.chanceOnAttack = v, AdGifConfigScreen::pct);
        addSlider(leftX, y0 + rowH * 3,  "Шанс при действии",     0, 1, 0.01,
                () -> config.chanceOnAction,             v -> config.chanceOnAction = v, AdGifConfigScreen::pct);
        addSlider(leftX, y0 + rowH * 4,  "Проверка каждые",       5, 120, 1,
                () -> config.randomCheckIntervalSeconds, v -> config.randomCheckIntervalSeconds = v, AdGifConfigScreen::sec);

        // --- right column: size / timing / sound / cooldown ---
        addSlider(rightX, y0,            "Размер гифки",          0.25, 4, 0.05,
                () -> config.scale,                      v -> config.scale = v, v -> "\u00D7" + num(v));
        addSlider(rightX, y0 + rowH,     "Длительность",          1, 30, 0.5,
                () -> config.durationSeconds,            v -> config.durationSeconds = v, AdGifConfigScreen::sec);
        addSlider(rightX, y0 + rowH * 2, "Громкость звука",       0, 1, 0.05,
                () -> config.soundVolume,                v -> config.soundVolume = v, AdGifConfigScreen::pct);
        addSlider(rightX, y0 + rowH * 3, "Пауза между показами",  0, 60, 0.5,
                () -> config.cooldownSeconds,            v -> config.cooldownSeconds = v, AdGifConfigScreen::sec);

        addDrawableChild(ButtonWidget.builder(Text.literal("Готово"), b -> close())
                .dimensions(this.width / 2 - 100, this.height - 28, 200, 20)
                .build());
    }

    /** Builds one labelled slider bound to a config field. */
    private void addSlider(int x, int y, String label, double min, double max, double step,
                           DoubleSupplier getter, DoubleConsumer setter, DoubleFunction<String> format) {
        double range = max - min;
        double initial = Math.min(1.0, Math.max(0.0, (getter.getAsDouble() - min) / range));

        SliderWidget slider = new SliderWidget(x, y, SLIDER_W, 20, Text.literal(label), initial) {
            private double snapped() {
                double v = min + this.value * range;
                v = Math.round(v / step) * step;
                return Math.min(max, Math.max(min, v));
            }

            {
                updateMessage();
            }

            @Override
            protected void updateMessage() {
                setMessage(Text.literal(label + ": " + format.apply(snapped())));
            }

            @Override
            protected void applyValue() {
                setter.accept(snapped());
            }
        };
        addDrawableChild(slider);
    }

    @Override
    public void close() {
        config.save();
        this.client.setScreen(parent);
    }

    // ---- formatters ----

    private static String pct(double v) {
        return Math.round(v * 100) + "%";
    }

    private static String sec(double v) {
        return num(v) + " с";
    }

    private static String num(double v) {
        double rounded = Math.round(v * 100.0) / 100.0;
        return (rounded == Math.floor(rounded)) ? String.valueOf((long) rounded) : String.valueOf(rounded);
    }
}