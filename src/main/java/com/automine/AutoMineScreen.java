package com.automine;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/** GUI: pilih ore, mulai/stop bot, atur opsi. Bisa dibuka lewat RShift atau Mod Menu. */
public class AutoMineScreen extends Screen {
    private final Screen parent;
    private int page = 0;

    public AutoMineScreen(Screen parent) {
        super(Component.literal("AutoMine Bot"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        addRenderableWidget(Button.builder(Component.literal(page == 0 ? "> Ore & Kontrol <" : "Ore & Kontrol"),
                b -> { page = 0; rebuildWidgets(); }).bounds(cx - 155, 8, 150, 20).build());
        addRenderableWidget(Button.builder(Component.literal(page == 1 ? "> Pengaturan <" : "Pengaturan"),
                b -> { page = 1; rebuildWidgets(); }).bounds(cx + 5, 8, 150, 20).build());

        if (page == 0) initMain(cx, 34); else initSettings(cx, 34);

        addRenderableWidget(Button.builder(Component.literal("Selesai"), b -> onClose())
                .bounds(cx - 100, this.height - 26, 200, 20).build());
    }

    private void initMain(int cx, int top) {
        AutoMineConfig cfg = AutoMineConfig.get();
        OreType[] types = OreType.values();
        for (int i = 0; i < types.length; i++) {
            OreType t = types[i];
            boolean on = cfg.isOreEnabled(t);
            MutableComponent label = Component.literal(on ? "ON  " : "OFF ")
                    .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED)
                    .append(Component.literal(t.label).withStyle(s -> s.withColor(t.color)));
            int x = cx - 155 + (i % 2) * 160;
            int y = top + (i / 2) * 22;
            addRenderableWidget(Button.builder(label, b -> {
                cfg.toggleOre(t);
                AutoMineConfig.save();
                AutoBot.rebuildWanted();
                rebuildWidgets();
            }).bounds(x, y, 150, 20).build());
        }

        int y2 = top + 5 * 22 + 6;
        addRenderableWidget(Button.builder(Component.literal("Pilih semua"), b -> {
            for (OreType t : types) cfg.ores.add(t.name());
            AutoMineConfig.save(); AutoBot.rebuildWanted(); rebuildWidgets();
        }).bounds(cx - 155, y2, 150, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Kosongkan"), b -> {
            cfg.ores.clear();
            AutoMineConfig.save(); AutoBot.rebuildWanted(); rebuildWidgets();
        }).bounds(cx + 5, y2, 150, 20).build());

        int y3 = y2 + 26;
        boolean mining = AutoBot.mode == AutoBot.Mode.MINE;
        boolean wood = AutoBot.mode == AutoBot.Mode.WOOD;
        addRenderableWidget(Button.builder(Component.literal(mining ? "Mining: JALAN" : "Mulai Mining"), b -> {
            AutoBot.start(AutoBot.Mode.MINE); onClose();
        }).bounds(cx - 155, y3, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal(wood ? "Tebang: JALAN" : "Mulai Tebang"), b -> {
            AutoBot.start(AutoBot.Mode.WOOD); onClose();
        }).bounds(cx - 50, y3, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("STOP").withStyle(ChatFormatting.RED), b -> {
            AutoBot.stop("Dihentikan dari menu"); rebuildWidgets();
        }).bounds(cx + 55, y3, 100, 20).build());
    }

    private void initSettings(int cx, int top) {
        AutoMineConfig cfg = AutoMineConfig.get();
        int i = 0;
        addRenderableWidget(new IntSlider(slotX(cx, i), slotY(top, i++), 150, 20, "Radius scan", 8, 48,
                cfg.scanRadius, v -> cfg.scanRadius = v, v -> String.valueOf(v)));
        addRenderableWidget(new IntSlider(slotX(cx, i), slotY(top, i++), 150, 20, "Jangkauan", 30, 45,
                (int) Math.round(cfg.reach * 10), v -> cfg.reach = v / 10.0, v -> String.format("%.1f", v / 10.0)));
        addRenderableWidget(new IntSlider(slotX(cx, i), slotY(top, i++), 150, 20, "Stop jika HP <=", 1, 19,
                cfg.minHealth, v -> cfg.minHealth = v, v -> String.valueOf(v)));
        addRenderableWidget(toggle(slotX(cx, i), slotY(top, i++), "Block ESP", cfg.esp, () -> cfg.esp = !cfg.esp));
        addRenderableWidget(toggle(slotX(cx, i), slotY(top, i++), "ESP selalu tampil", cfg.espAlways, () -> cfg.espAlways = !cfg.espAlways));
        addRenderableWidget(toggle(slotX(cx, i), slotY(top, i++), "Tampilkan jalur", cfg.showPath, () -> cfg.showPath = !cfg.showPath));
        addRenderableWidget(toggle(slotX(cx, i), slotY(top, i++), "Gali menembus blok", cfg.tunneling, () -> cfg.tunneling = !cfg.tunneling));
        addRenderableWidget(toggle(slotX(cx, i), slotY(top, i++), "Auto ganti tool", cfg.autoTool, () -> cfg.autoTool = !cfg.autoTool));
        addRenderableWidget(toggle(slotX(cx, i), slotY(top, i++), "Stop jika HP rendah", cfg.stopLowHealth, () -> cfg.stopLowHealth = !cfg.stopLowHealth));
        addRenderableWidget(toggle(slotX(cx, i), slotY(top, i++), "Stop jika inv penuh", cfg.stopInvFull, () -> cfg.stopInvFull = !cfg.stopInvFull));
    }

    private static int slotX(int cx, int i) { return cx - 155 + (i % 2) * 160; }
    private static int slotY(int top, int i) { return top + (i / 2) * 24; }

    private Button toggle(int x, int y, String name, boolean on, Runnable flip) {
        MutableComponent label = Component.literal(name + ": ")
                .append(Component.literal(on ? "ON" : "OFF").withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED));
        return Button.builder(label, b -> {
            flip.run();
            AutoMineConfig.save();
            rebuildWidgets();
        }).bounds(x, y, 150, 20).build();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);
        String status = switch (AutoBot.mode) {
            case OFF -> "Status: berhenti";
            case MINE -> "Status: Auto Mine berjalan";
            case WOOD -> "Status: Auto Tebang berjalan";
        };
        g.drawCenteredString(this.font, Component.literal(status), this.width / 2, this.height - 40, 0xFFFFFFFF);
    }

    @Override
    public void removed() {
        AutoMineConfig.save();
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    private static final class IntSlider extends AbstractSliderButton {
        private final String label;
        private final int min, max;
        private final IntConsumer set;
        private final IntFunction<String> fmt;

        IntSlider(int x, int y, int w, int h, String label, int min, int max, int value,
                  IntConsumer set, IntFunction<String> fmt) {
            super(x, y, w, h, Component.empty(), (double) (value - min) / (max - min));
            this.label = label; this.min = min; this.max = max; this.set = set; this.fmt = fmt;
            updateMessage();
        }

        private int cur() { return min + (int) Math.round(this.value * (max - min)); }

        @Override
        protected void updateMessage() {
            if (fmt == null) return;
            setMessage(Component.literal(label + ": " + fmt.apply(cur())));
        }

        @Override
        protected void applyValue() { set.accept(cur()); }
    }
}
