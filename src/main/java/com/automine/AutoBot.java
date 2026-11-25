package com.automine;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** Otak bot: scan -> pilih target -> pathfinding -> jalan -> hancurkan. */
public final class AutoBot {
    public enum Mode { OFF, MINE, WOOD }
    public record Found(BlockPos pos, int color) {}

    public static Mode mode = Mode.OFF;
    public static List<Found> found = List.of();
    public static BlockPos target = null;
    public static List<BlockPos> path = List.of();
    public static int pathIdx = 0;

    private static final Map<Block, Integer> wanted = new HashMap<>();
    private static final Map<Long, Long> blacklist = new HashMap<>();
    private static long ticks = 0;
    private static int scanTimer, stuck, offPath, repaths, noTarget, statusTimer, invTimer;
    private static Vec3 lastPos;

    private AutoBot() {}

    // ---------------------------------------------------------------- kontrol

    public static void toggle(Mode m) {
        if (mode == m) stop("Dihentikan"); else start(m);
    }

    public static void start(Mode m) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        rebuildWanted();
        if (m == Mode.MINE && wanted.isEmpty()) {
            msg(mc, "Pilih minimal 1 ore di menu dulu (RShift).");
            return;
        }
        mode = m;
        target = null; path = List.of(); pathIdx = 0;
        stuck = offPath = repaths = noTarget = 0;
        scanTimer = 0; invTimer = 0; statusTimer = 0;
        lastPos = null;
        blacklist.clear();
        msg(mc, m == Mode.MINE ? "Auto Mine MULAI" : "Auto Tebang Kayu MULAI");
    }

    public static void stop(String why) {
        Minecraft mc = Minecraft.getInstance();
        boolean was = mode != Mode.OFF;
        mode = Mode.OFF;
        target = null; path = List.of(); pathIdx = 0;
        if (mc.player != null && mc.options != null) releaseMove(mc);
        if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        if (was) msg(mc, "STOP: " + why);
    }

    public static void rebuildWanted() {
        wanted.clear();
        for (String n : AutoMineConfig.get().ores) {
            try {
                OreType t = OreType.valueOf(n);
                for (Block b : t.blocks) wanted.put(b, t.color);
            } catch (IllegalArgumentException ignored) {}
        }
    }

    private static void msg(Minecraft mc, String s) {
        if (mc.player != null) mc.player.displayClientMessage(Component.literal("[AutoMine] " + s), false);
    }

    // ---------------------------------------------------------------- tick

    public static void tick(Minecraft mc) {
        ticks++;
        LocalPlayer p = mc.player;
        ClientLevel lvl = mc.level;
        AutoMineConfig cfg = AutoMineConfig.get();

        if (p == null || lvl == null || mc.gameMode == null) {
            mode = Mode.OFF; found = List.of(); target = null; path = List.of();
            return;
        }

        boolean scanning = mode != Mode.OFF || (cfg.esp && cfg.espAlways);
        if (!scanning) {
            if (!found.isEmpty()) found = List.of();
            return;
        }
        if (--scanTimer <= 0) { scanTimer = 20; scan(mc); }
        if (mode == Mode.OFF) return;

        if (mc.screen != null) { releaseMove(mc); return; } // pause saat GUI terbuka

        // ---- pengaman
        if (p.isDeadOrDying()) { stop("Player mati"); return; }
        if (cfg.stopLowHealth && p.getHealth() <= cfg.minHealth) { stop("HP rendah"); return; }
        if (p.isInLava() || p.isOnFire()) { stop("Terbakar / kena lava"); return; }
        if (cfg.stopInvFull && --invTimer <= 0) {
            invTimer = 40;
            if (p.getInventory().getFreeSlot() < 0) { stop("Inventory penuh"); return; }
        }

        if (--statusTimer <= 0) {
            statusTimer = 20;
            String m = mode == Mode.MINE ? "Mining" : "Tebang kayu";
            String t = target == null ? "mencari target" : "target " + target.toShortString();
            p.displayClientMessage(Component.literal("AutoMine | " + m + " | " + t + " | " + found.size() + " blok"), true);
        }

        // ---- target
        if (target != null && !validTarget(lvl, target)) {
            mc.gameMode.stopDestroyBlock();
            target = null; path = List.of(); pathIdx = 0;
        }
        if (target == null) {
            pickTarget(mc);
            if (target == null) {
                releaseMove(mc);
                if (++noTarget > 100) stop(mode == Mode.MINE ? "Ore tidak ditemukan di radius" : "Pohon tidak ditemukan di radius");
                return;
            }
            noTarget = 0;
        }

        // ---- sudah dalam jangkauan? hancurkan
        double reach = cfg.reach;
        if (p.getEyePosition().distanceToSqr(Vec3.atCenterOf(target)) <= reach * reach) {
            releaseMove(mc);
            if (!mineBlock(mc, target)) {
                blacklist(target, 2400);
                mc.gameMode.stopDestroyBlock();
                target = null; path = List.of(); pathIdx = 0;
            }
            return;
        }

        followPath(mc);
    }

    // ---------------------------------------------------------------- scan & target

    private static void scan(Minecraft mc) {
        ClientLevel lvl = mc.level;
        AutoMineConfig cfg = AutoMineConfig.get();
        boolean wood = mode == Mode.WOOD;
        if (!wood) rebuildWanted();
        BlockPos c = mc.player.blockPosition();
        int r = cfg.scanRadius;
        long r2 = (long) r * r;
        int minY = Math.max(lvl.getMinY(), c.getY() - r);
        int maxY = Math.min(lvl.getMaxY(), c.getY() + r);
        List<Found> out = new ArrayList<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

        for (int x = c.getX() - r; x <= c.getX() + r; x++) {
            for (int z = c.getZ() - r; z <= c.getZ() + r; z++) {
                m.set(x, c.getY(), z);
                if (!lvl.hasChunkAt(m)) continue;
                for (int y = minY; y <= maxY; y++) {
                    long dx = x - c.getX(), dy = y - c.getY(), dz = z - c.getZ();
                    if (dx * dx + dy * dy + dz * dz > r2) continue;
                    m.set(x, y, z);
                    BlockState st = lvl.getBlockState(m);
                    if (st.isAir()) continue;
                    if (wood) {
                        if (st.is(BlockTags.LOGS)) out.add(new Found(m.immutable(), OreType.WOOD_COLOR));
                    } else {
                        Integer col = wanted.get(st.getBlock());
                        if (col != null) out.add(new Found(m.immutable(), col));
                    }
                }
            }
        }
        found = out;
    }

    private static boolean validTarget(ClientLevel lvl, BlockPos pos) {
        BlockState st = lvl.getBlockState(pos);
        return mode == Mode.WOOD ? st.is(BlockTags.LOGS) : wanted.containsKey(st.getBlock());
    }

    private static double score(BlockPos pos, BlockPos me) {
        double s = Vec3.atCenterOf(pos).distanceToSqr(Vec3.atCenterOf(me));
        int dy = pos.getY() - me.getY();
        if (mode == Mode.WOOD && dy > 1) s += dy * dy * 8.0; // utamakan batang bawah
        return s;
    }

    private static void pickTarget(Minecraft mc) {
        ClientLevel lvl = mc.level;
        AutoMineConfig cfg = AutoMineConfig.get();
        BlockPos me = mc.player.blockPosition();
        List<Found> cands = new ArrayList<>();
        for (Found f : found) if (!isBlacklisted(f.pos())) cands.add(f);
        cands.sort(Comparator.comparingDouble(f -> score(f.pos(), me)));

        int tries = 0;
        for (Found f : cands) {
            if (tries++ >= 4) break;
            BlockState st = lvl.getBlockState(f.pos());
            if (!canBreak(lvl, f.pos(), st)) { blacklist(f.pos(), 6000); continue; }
            List<BlockPos> np = Pathfinder.find(lvl, me, f.pos(), cfg.reach, cfg.maxNodes, cfg.scanRadius + 10);
            if (np != null) {
                target = f.pos(); path = np; pathIdx = 0;
                stuck = offPath = repaths = 0; lastPos = null;
                return;
            }
            blacklist(f.pos(), 1200);
        }
    }

    private static void blacklist(BlockPos p, int forTicks) {
        if (blacklist.size() > 2000) blacklist.values().removeIf(t -> t < ticks);
        blacklist.put(p.asLong(), ticks + forTicks);
    }

    private static boolean isBlacklisted(BlockPos p) {
        Long t = blacklist.get(p.asLong());
        return t != null && t > ticks;
    }

    // ---------------------------------------------------------------- bergerak

    private static void repath(Minecraft mc) {
        stuck = 0; offPath = 0;
        if (target == null) return;
        AutoMineConfig cfg = AutoMineConfig.get();
        if (++repaths > 3) {
            blacklist(target, 1200);
            mc.gameMode.stopDestroyBlock();
            target = null; path = List.of(); pathIdx = 0;
            return;
        }
        List<BlockPos> np = Pathfinder.find(mc.level, mc.player.blockPosition(), target, cfg.reach, cfg.maxNodes, cfg.scanRadius + 10);
        if (np == null) {
            blacklist(target, 1200);
            target = null; path = List.of(); pathIdx = 0;
        } else {
            path = np; pathIdx = 0;
        }
    }

    private static void followPath(Minecraft mc) {
        LocalPlayer p = mc.player;
        ClientLevel lvl = mc.level;
        BlockPos cur = p.blockPosition();

        boolean on = false;
        for (int i = pathIdx; i < path.size(); i++) {
            if (path.get(i).equals(cur)) {
                if (i > pathIdx) repaths = 0;
                pathIdx = i; on = true;
            }
        }
        if (!on) { if (++offPath > 30) { repath(mc); return; } } else offPath = 0;

        // node terakhir tapi belum dalam jangkauan: jalan ke tengahnya
        if (pathIdx >= path.size() - 1) {
            BlockPos last = path.get(path.size() - 1);
            Vec3 c = new Vec3(last.getX() + 0.5, last.getY(), last.getZ() + 0.5);
            double hd = Math.hypot(p.getX() - c.x, p.getZ() - c.z);
            if (hd < 0.35 && p.onGround()) {
                releaseMove(mc);
                if (++stuck > 10) repath(mc);
                return;
            }
            walkToward(mc, c, false);
            return;
        }

        BlockPos next = path.get(pathIdx + 1);

        BlockPos blocker = firstBlocker(lvl, cur, next);
        if (blocker != null) {
            releaseMove(mc);
            stuck = 0; lastPos = null;
            if (!mineBlock(mc, blocker)) {
                if (target != null) blacklist(target, 1200);
                mc.gameMode.stopDestroyBlock();
                target = null; path = List.of(); pathIdx = 0;
            }
            return;
        }

        Vec3 nc = new Vec3(next.getX() + 0.5, next.getY(), next.getZ() + 0.5);
        if (cur.getX() == next.getX() && cur.getZ() == next.getZ() && cur.getY() > next.getY()) {
            // sedang di atas lubang: dorong ke tengah kolom lalu tunggu jatuh
            double hd = Math.hypot(p.getX() - nc.x, p.getZ() - nc.z);
            if (hd < 0.2) { releaseMove(mc); return; }
        }
        walkToward(mc, nc, next.getY() > cur.getY());

        Vec3 pos = p.position();
        if (lastPos != null && pos.distanceToSqr(lastPos) < 0.001) stuck++; else stuck = 0;
        lastPos = pos;
        if (stuck > 30) repath(mc);
    }

    private static BlockPos firstBlocker(ClientLevel lvl, BlockPos cur, BlockPos next) {
        List<BlockPos> cells = new ArrayList<>(4);
        if (next.getY() > cur.getY()) cells.add(cur.above(2));
        int top = Math.max(cur.getY(), next.getY()) + 1;
        for (int y = top; y >= next.getY(); y--) cells.add(new BlockPos(next.getX(), y, next.getZ()));
        for (BlockPos c : cells) {
            BlockState st = lvl.getBlockState(c);
            if (!st.getCollisionShape(lvl, c).isEmpty()) return c;
        }
        return null;
    }

    private static void walkToward(Minecraft mc, Vec3 t, boolean jumpHint) {
        LocalPlayer p = mc.player;
        double dx = t.x - p.getX(), dz = t.z - p.getZ();
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        p.setYRot(yaw);
        p.setXRot(0f);
        mc.options.keyUp.setDown(true);
        mc.options.keyJump.setDown(p.onGround() && (jumpHint || p.horizontalCollision));
    }

    private static void releaseMove(Minecraft mc) {
        mc.options.keyUp.setDown(false);
        mc.options.keyJump.setDown(false);
    }

    // ---------------------------------------------------------------- menghancurkan

    private static void lookAt(LocalPlayer p, Vec3 t) {
        Vec3 e = p.getEyePosition();
        double dx = t.x - e.x, dy = t.y - e.y, dz = t.z - e.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, h));
        p.setYRot(yaw);
        p.setXRot(Mth.clamp(pitch, -90f, 90f));
    }

    /** Hancurkan blok yang menghalangi pandangan ke {@code pos} (atau pos itu sendiri). false = mustahil. */
    private static boolean mineBlock(Minecraft mc, BlockPos pos) {
        LocalPlayer p = mc.player;
        ClientLevel lvl = mc.level;
        Vec3 eye = p.getEyePosition();
        Vec3 c = Vec3.atCenterOf(pos);
        lookAt(p, c);

        BlockHitResult hit = lvl.clip(new ClipContext(eye, c, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
        boolean isBlock = hit.getType() == HitResult.Type.BLOCK;
        BlockPos hp = isBlock ? hit.getBlockPos() : pos;
        Direction dir = isBlock ? hit.getDirection() : Direction.UP;

        BlockState hs = lvl.getBlockState(hp);
        if (hs.isAir()) return true;
        if (!hp.equals(pos) && !(allowDig(hs) && canBreak(lvl, hp, hs))) return false;
        if (hp.equals(pos) && !canBreak(lvl, hp, hs)) return false;

        if (AutoMineConfig.get().autoTool) selectTool(p, hs);
        mc.gameMode.continueDestroyBlock(hp, dir);
        p.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    private static void selectTool(LocalPlayer p, BlockState st) {
        Inventory inv = p.getInventory();
        int bestSlot = inv.getSelectedSlot();
        float bestScore = toolScore(inv.getItem(bestSlot), st);
        for (int i = 0; i < 9; i++) {
            float s = toolScore(inv.getItem(i), st);
            if (s > bestScore) { bestScore = s; bestSlot = i; }
        }
        if (bestSlot != inv.getSelectedSlot()) inv.setSelectedSlot(bestSlot);
    }

    private static float toolScore(ItemStack s, BlockState st) {
        return s.getDestroySpeed(st) + (s.isCorrectToolForDrops(st) ? 1000f : 0f);
    }

    // ---------------------------------------------------------------- aturan dunia

    static boolean isDanger(BlockState st) {
        Block b = st.getBlock();
        return b == Blocks.LAVA || b == Blocks.FIRE || b == Blocks.SOUL_FIRE
                || b == Blocks.CAMPFIRE || b == Blocks.SOUL_CAMPFIRE || b == Blocks.CACTUS
                || b == Blocks.MAGMA_BLOCK || b == Blocks.SWEET_BERRY_BUSH
                || b == Blocks.POWDER_SNOW || b == Blocks.COBWEB || b == Blocks.WITHER_ROSE;
    }

    /** Apakah blok penghalang (bukan target) boleh digali pada mode saat ini. */
    static boolean allowDig(BlockState st) {
        if (mode == Mode.WOOD) return st.is(BlockTags.LOGS) || st.is(BlockTags.LEAVES);
        return AutoMineConfig.get().tunneling;
    }

    /** Aman dihancurkan: bukan bedrock, bukan block entity (chest dll), tidak keras, tidak bersebelahan cairan. */
    static boolean canBreak(ClientLevel lvl, BlockPos pos, BlockState st) {
        float h = st.getDestroySpeed(lvl, pos);
        if (h < 0 || h > 25) return false;
        if (!st.getFluidState().isEmpty()) return false;
        if (st.hasBlockEntity()) return false;
        for (Direction d : Direction.values()) {
            if (!lvl.getFluidState(pos.relative(d)).isEmpty()) return false;
        }
        return true;
    }
}
