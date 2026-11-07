package com.riichimahjong.mahjongtable.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.riichimahjong.mahjongtable.MahjongTableBlockEntity;
import dev.architectury.event.events.client.ClientTickEvent;
import dev.architectury.registry.client.keymappings.KeyMappingRegistry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * Opens {@link MahjongGameScreen}:
 * <ul>
 *   <li>manually with the {@link #OPEN} key (default {@code M}), and</li>
 *   <li>automatically once when the local player is seated at a table whose
 *       match has just started.</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public final class MahjongGameScreenKeys {

    public static final KeyMapping OPEN = new KeyMapping(
            "key.riichi_mahjong.game_screen",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_M,
            "key.categories.riichi_mahjong");

    private static final int SEARCH_RADIUS = 8;
    private static final int AUTO_OPEN_INTERVAL_TICKS = 10;

    /** Table we were last seen seated at with an active match (for auto-open edge detection). */
    @Nullable private static BlockPos seatedActiveTable;
    private static int tickCounter;

    private MahjongGameScreenKeys() {}

    /** Call once from client init. */
    public static void register() {
        KeyMappingRegistry.register(OPEN);
        ClientTickEvent.CLIENT_POST.register(mc -> {
            while (OPEN.consumeClick()) {
                if (mc.screen != null || mc.player == null) continue;
                BlockPos pos = findTable(mc, false);
                if (pos != null) {
                    mc.setScreen(new MahjongGameScreen(pos));
                } else {
                    mc.player.displayClientMessage(
                            Component.translatable("riichi_mahjong.screen.game.no_table"), true);
                }
            }
            if (++tickCounter % AUTO_OPEN_INTERVAL_TICKS == 0) autoOpen(mc);
        });
    }

    private static void autoOpen(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            seatedActiveTable = null;
            return;
        }
        BlockPos seated = findTable(mc, true);
        if (seated != null && !seated.equals(seatedActiveTable) && mc.screen == null) {
            mc.setScreen(new MahjongGameScreen(seated));
        }
        seatedActiveTable = seated;
    }

    /**
     * Nearest table with a running match. Tables where the local player is
     * seated always win; with {@code seatedOnly} the others are ignored.
     */
    @Nullable
    private static BlockPos findTable(Minecraft mc, boolean seatedOnly) {
        if (mc.player == null || mc.level == null) return null;
        BlockPos origin = mc.player.blockPosition();
        BlockPos bestSeated = null;
        BlockPos bestAny = null;
        double bestSeatedDist = Double.MAX_VALUE;
        double bestAnyDist = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(
                origin.offset(-SEARCH_RADIUS, -4, -SEARCH_RADIUS),
                origin.offset(SEARCH_RADIUS, 4, SEARCH_RADIUS))) {
            if (!(mc.level.getBlockEntity(p) instanceof MahjongTableBlockEntity table)) continue;
            if (table.driver() == null) continue;
            double d = p.distSqr(origin);
            if (MahjongGameScreen.localSeat(table) >= 0) {
                if (d < bestSeatedDist) {
                    bestSeatedDist = d;
                    bestSeated = p.immutable();
                }
            } else if (d < bestAnyDist) {
                bestAnyDist = d;
                bestAny = p.immutable();
            }
        }
        if (bestSeated != null) return bestSeated;
        return seatedOnly ? null : bestAny;
    }
}
