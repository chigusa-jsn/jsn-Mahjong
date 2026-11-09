package com.riichimahjong.mahjongtable.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import com.riichimahjong.cuterenderer.InteractKey;
import com.riichimahjong.cuterenderer.net.C2SCuteClickPacket;
import com.riichimahjong.mahjongcore.MahjongWinEffects;
import com.riichimahjong.mahjongtable.MahjongTableBlockEntity;
import com.riichimahjong.mahjongtable.MahjongTableBlockEntity.ResultAnimStage;
import com.riichimahjong.mahjongtable.MahjongTableHumanPlayer;
import com.themahjong.TheMahjongDiscard;
import com.themahjong.TheMahjongMeld;
import com.themahjong.TheMahjongPlayer;
import com.themahjong.TheMahjongRound;
import com.themahjong.TheMahjongTile;
import com.themahjong.driver.MatchPhase;
import com.themahjong.driver.PlayerAction;
import com.themahjong.driver.TheMahjongDriver;
import com.themahjong.yaku.WinResult;
import dev.architectury.networking.NetworkManager;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Mahjong Soul style 2D game screen for a {@link MahjongTableBlockEntity}.
 *
 * <p>Pure view + input layer: every number on screen is read from the
 * table's synced driver state, and every click is sent through the same
 * {@link C2SCuteClickPacket} the 3D table uses ({@code SeatSlot} for hand
 * tiles and action buttons). The server-side rules, hints and item handling
 * are therefore exactly the same as when clicking the table in the world.
 *
 * <p>Layout (relative seats): 0 = you (bottom), 1 = shimocha (right),
 * 2 = toimen (top), 3 = kamicha (left). Each opponent's river, hand and melds
 * are drawn in a rotated frame so the river "faces" that player, like the
 * official clients.
 */
@Environment(EnvType.CLIENT)
public class MahjongGameScreen extends Screen {

    // ---- colours -----------------------------------------------------------
    private static final int COL_TABLE        = 0xFF1F4A3A;
    private static final int COL_TABLE_INNER  = 0xFF24563F;
    private static final int COL_BOX          = 0xE0141C22;
    private static final int COL_BOX_EDGE     = 0xFF3B4A52;
    private static final int COL_TILE_EDGE    = 0xFF6E6656;
    private static final int COL_TILE_FACE    = 0xFFF4F0E4;
    private static final int COL_TILE_BACK    = 0xFF1E9A6A;
    private static final int COL_TILE_BACK_HI = 0xFF33B886;
    private static final int COL_DIM          = 0x90000000;
    private static final int COL_HIGHLIGHT    = 0xFFFF8C1A;
    private static final int COL_TURN         = 0xFFFFD34D;
    private static final int COL_TEXT         = 0xFFE8E8E8;
    private static final int COL_TEXT_DIM     = 0xFF9AA4AA;
    private static final int COL_GOLD         = 0xFFFFD34D;

    // ---- layout constants --------------------------------------------------
    private static final int RIVER_COLS = 6;
    private static final int RIVER_ROWS = 3;
    private static final int GAP = 6;

    private static final Map<String, ResourceLocation> TEXTURES = new HashMap<>();

    private final BlockPos tablePos;

    // Layout, recomputed every frame from the window size.
    private int u;        // river / meld tile width
    private int rh;       // river / meld tile height
    private int hw;       // own hand tile width
    private int hh;       // own hand tile height
    private int ow;       // opponent hidden-hand tile width
    private int oh;       // opponent hidden-hand tile height
    private int dw;       // dora indicator tile width
    private int dh;
    private int box;      // centre box size
    private int cx;
    private int cy;
    private int handY;

    /** Clickable regions collected during the last render. */
    private final List<Hit> hits = new ArrayList<>();

    private record Hit(int x0, int y0, int x1, int y1, InteractKey key) {
        boolean contains(double x, double y) {
            return x >= x0 && x < x1 && y >= y0 && y < y1;
        }
    }

    // Last-discard tracking (client-side: we watch discard counts grow).
    private int[] lastDiscardCounts = new int[0];
    private int lastDiscardSeat = -1;

    public MahjongGameScreen(BlockPos tablePos) {
        super(Component.translatable("riichi_mahjong.screen.game.title"));
        this.tablePos = tablePos.immutable();
    }

    public BlockPos tablePos() {
        return tablePos;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ======================================================================
    //  State helpers
    // ======================================================================

    @Nullable
    private MahjongTableBlockEntity table() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        return mc.level.getBlockEntity(tablePos) instanceof MahjongTableBlockEntity t ? t : null;
    }

    /** Local player's seat at {@code table}, or -1 when spectating. */
    static int localSeat(MahjongTableBlockEntity table) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return -1;
        UUID uuid = mc.player.getUUID();
        List<MahjongTableBlockEntity.SeatInfo> seats = table.seats();
        for (int i = 0; i < seats.size(); i++) {
            var info = seats.get(i);
            if (info.enabled() && info.occupant().isPresent() && info.occupant().get().equals(uuid)) {
                return i;
            }
        }
        return -1;
    }

    /** Seat index → on-screen position (0 bottom, 1 right, 2 top, 3 left). */
    private static int relativePos(int seat, int viewSeat, int playerCount) {
        int rel = Math.floorMod(seat - viewSeat, playerCount);
        // Sanma: the third seat is kamicha, i.e. on the left.
        if (playerCount == 3 && rel == 2) return 3;
        return rel;
    }

    @Override
    public void tick() {
        super.tick();
        MahjongTableBlockEntity table = table();
        Minecraft mc = Minecraft.getInstance();
        boolean tooFar = mc.player != null
                && mc.player.distanceToSqr(tablePos.getX() + 0.5, tablePos.getY() + 0.5, tablePos.getZ() + 0.5) > 32 * 32;
        if (table == null || table.driver() == null || tooFar) {
            onClose();
        }
    }

    // ======================================================================
    //  Layout
    // ======================================================================

    private static int thickness(int w) {
        return Math.max(1, w / 7);
    }

    private static int tileHeight(int w) {
        return Math.round(w * 14f / 11f) + thickness(w);
    }

    /** Height of the band along each box edge that holds a seat's wind + points. */
    private int labelBand() {
        return font.lineHeight + 4;
    }

    private void computeLayout() {
        // Pick the biggest river tile that fits: vertically we need toimen's
        // row, two 3-row rivers, the centre box, the button row and our hand;
        // horizontally our hand + melds (≈ 26 river-tile widths).
        int maxByWidth = Math.max(8, (width - 20) / 26);
        u = 8;
        for (int cand = Math.min(40, maxByWidth); cand >= 8; cand--) {
            int candRh = tileHeight(cand);
            int candHh = tileHeight(Math.max(12, Math.round(cand * 1.7f)));
            int need = (candRh + 6)                       // toimen hand / melds
                    + 2 * (RIVER_ROWS * candRh + GAP)      // two rivers
                    + cand * RIVER_COLS + 2 * labelBand()  // centre box
                    + 22                                   // button row overlap
                    + candHh + 8;                          // own hand
            if (need <= height) {
                u = cand;
                break;
            }
        }
        rh = tileHeight(u);
        hw = Math.max(12, Math.round(u * 1.7f));
        hh = tileHeight(hw);
        ow = Math.max(6, Math.round(u * 0.75f));
        oh = tileHeight(ow);
        dw = Math.max(7, Math.round(u * 0.8f));
        dh = tileHeight(dw);
        box = u * RIVER_COLS + 2 * labelBand();

        handY = height - hh - 8;
        int top = rh + 6;                        // room for toimen's hand/melds
        int riverDepth = RIVER_ROWS * rh + GAP;  // rivers + gap to the box
        int bottomLimit = handY - 22;            // room for the action-button row overlap
        // Centre the box between toimen's hand and our own hand.
        int minCy = top + riverDepth + box / 2;
        int maxCy = bottomLimit - riverDepth - box / 2;
        cy = maxCy >= minCy ? (minCy + maxCy) / 2 : minCy;
        cx = width / 2;
    }

    // ======================================================================
    //  Rendering
    // ======================================================================

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, COL_TABLE);
        int r = (int) (Math.min(width, height) * 0.48f);
        g.fill(cx - r, cy - r, cx + r, cy + r, COL_TABLE_INNER);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        computeLayout();
        super.render(g, mouseX, mouseY, partialTick);
        hits.clear();

        MahjongTableBlockEntity table = table();
        TheMahjongDriver driver = table != null ? table.driver() : null;
        if (table == null || driver == null) return;

        TheMahjongRound round = driver.match().currentRound().orElse(null);
        if (round == null) {
            g.drawCenteredString(font, Component.translatable("riichi_mahjong.screen.game.waiting"),
                    cx, cy, COL_TEXT);
            return;
        }

        int mySeat = localSeat(table);
        int viewSeat = mySeat >= 0 ? mySeat : 0;
        int playerCount = round.players().size();
        MatchPhase phase = driver.currentPhase();
        boolean dealing = phase instanceof MatchPhase.Dealing;

        trackLastDiscard(round);
        Set<Long> claimedDiscards = claimedDiscards(round);

        drawCentreBox(g, table, round, viewSeat, playerCount);

        for (int s = 0; s < playerCount; s++) {
            int rel = relativePos(s, viewSeat, playerCount);
            drawRiver(g, round, s, rel, claimedDiscards);
            if (rel != 0) drawOpponent(g, table, round, s, rel);
        }

        // Own hand + melds + buttons.
        List<PlayerAction> legal = List.of();
        boolean riichiPending = false;
        if (mySeat >= 0 && !dealing
                && (phase instanceof MatchPhase.AwaitingDiscard || phase instanceof MatchPhase.AwaitingClaims)) {
            try {
                legal = driver.legalActions(mySeat);
            } catch (RuntimeException ignored) {
                legal = List.of();
            }
            riichiPending = table.riichiPendingForSeat(mySeat);
        }
        boolean myDiscardTurn = mySeat >= 0 && phase instanceof MatchPhase.AwaitingDiscard ad && ad.seat() == mySeat;

        drawOwnHand(g, round, viewSeat, mySeat >= 0, myDiscardTurn, legal, riichiPending, mouseX, mouseY);

        if (mySeat >= 0 && !dealing) {
            drawButtons(g, driver, round, mySeat, riichiPending, mouseX, mouseY);
            drawStatusLine(g, phase, mySeat, myDiscardTurn, riichiPending);
        } else if (mySeat < 0) {
            g.drawString(font, Component.translatable("riichi_mahjong.screen.game.spectating"),
                    6, 6, COL_TEXT_DIM, true);
        }

        // Panels sit on a higher z layer: GUI text is batched and would
        // otherwise show through the panel background.
        boolean resultPanel = table.isInResultPhase() && phase instanceof MatchPhase.RoundEnded;
        boolean finalPanel = !resultPanel && phase instanceof MatchPhase.MatchEnded;
        if (resultPanel || finalPanel) {
            g.flush();
            g.pose().pushPose();
            g.pose().translate(0, 0, 400);
            if (resultPanel) {
                drawResultPanel(g, table, round, (MatchPhase.RoundEnded) phase, mySeat >= 0, mouseX, mouseY);
            } else {
                drawFinalPanel(g, table, round);
            }
            g.flush();
            g.pose().popPose();
        }
    }

    // ---- tiles -------------------------------------------------------------

    private static ResourceLocation textureFor(TheMahjongTile tile) {
        String name = switch (tile.suit()) {
            case MANZU -> "tile_man_" + tile.rank();
            case PINZU -> "tile_pin_" + tile.rank();
            case SOUZU -> "tile_sou_" + tile.rank();
            case WIND -> switch (TheMahjongTile.Wind.fromTileRank(tile.rank())) {
                case EAST -> "tile_wind_east";
                case SOUTH -> "tile_wind_south";
                case WEST -> "tile_wind_west";
                case NORTH -> "tile_wind_north";
            };
            case DRAGON -> switch (TheMahjongTile.Dragon.fromTileRank(tile.rank())) {
                case HAKU -> "tile_dragon_white";
                case HATSU -> "tile_dragon_green";
                case CHUN -> "tile_dragon_red";
            };
        };
        if (tile.redDora() && tile.rank() == 5 && tile.suit().isNumber()) name += "_aka";
        return TEXTURES.computeIfAbsent(name, n ->
                ResourceLocation.fromNamespaceAndPath("riichi_mahjong", "textures/block/tile/" + n + ".png"));
    }

    /** Face-up tile with its top-left corner at (x, y). */
    private static void drawTile(GuiGraphics g, int x, int y, int w, int h, TheMahjongTile tile) {
        int t = thickness(w);
        g.fill(x, y, x + w, y + h, COL_TILE_EDGE);
        g.fill(x + 1, y + h - t - 1, x + w - 1, y + h - 1, COL_TILE_BACK);
        g.fill(x + 1, y + 1, x + w - 1, y + h - t - 1, COL_TILE_FACE);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        // Face art lives at u 5..27, v 2..30 of the 32×32 block texture
        // (same region the 3D tile model maps onto its front face).
        g.blit(textureFor(tile), x + 1, y + 1, w - 2, h - t - 2, 5f, 2f, 22, 28, 32, 32);
        RenderSystem.disableBlend();
    }

    /** Face-down tile (opponent hands, hidden dora). */
    private static void drawTileBack(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, COL_TILE_EDGE);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, COL_TILE_BACK);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, COL_TILE_BACK_HI);
    }

    /**
     * Tile turned 90° (riichi declaration, called tile in a meld). Occupies a
     * box {@code h} wide and {@code w} tall with its top-left corner at (x, y).
     */
    private static void drawTileSideways(GuiGraphics g, int x, int y, int w, int h, TheMahjongTile tile) {
        g.pose().pushPose();
        g.pose().translate(x, y + w, 0);
        g.pose().mulPose(Axis.ZP.rotationDegrees(-90f));
        drawTile(g, 0, 0, w, h, tile);
        g.pose().popPose();
    }

    // ---- centre box ----------------------------------------------------------

    private void drawCentreBox(GuiGraphics g, MahjongTableBlockEntity table, TheMahjongRound round,
                               int viewSeat, int playerCount) {
        int half = box / 2;
        g.fill(cx - half - 1, cy - half - 1, cx + half + 1, cy + half + 1, COL_BOX_EDGE);
        g.fill(cx - half, cy - half, cx + half, cy + half, COL_BOX);

        // Round label, honba / riichi sticks, dora, wall count.
        Component wind = Component.translatable(
                "riichi_mahjong.result.wind." + round.roundWind().name().toLowerCase(Locale.ROOT));
        Component label = Component.translatable("riichi_mahjong.result.round_label",
                wind, Component.literal(String.valueOf(round.handNumber())));
        int contentH = font.lineHeight + 2 + dh + 2 + font.lineHeight + 1 + font.lineHeight;
        int y = cy - contentH / 2;
        g.drawCenteredString(font, label, cx, y, COL_GOLD);
        y += font.lineHeight + 2;

        int slots = TheMahjongRound.MAX_DORA_INDICATORS;
        int doraRowW = slots * dw + (slots - 1);
        int dx = cx - doraRowW / 2;
        List<TheMahjongTile> indicators = round.doraIndicators();
        int revealed = round.revealedDoraCount();
        for (int i = 0; i < slots; i++) {
            if (i < revealed && i < indicators.size()) {
                drawTile(g, dx, y, dw, dh, indicators.get(i));
            } else {
                drawTileBack(g, dx, y, dw, dh);
            }
            dx += dw + 1;
        }
        y += dh + 2;
        g.drawCenteredString(font, Component.translatable("riichi_mahjong.screen.game.wall",
                round.liveWall().size()), cx, y, COL_TEXT);
        y += font.lineHeight + 1;
        drawStickCounters(g, cx, y, round.honba(), round.riichiSticks());

        // Per-seat wind + points along each edge, rotated to face the seat.
        for (int s = 0; s < playerCount; s++) {
            int rel = relativePos(s, viewSeat, playerCount);
            TheMahjongPlayer p = round.players().get(s);
            boolean turn = round.currentTurnSeat() == s;
            beginSeatFrame(g, rel);
            String windChar = Component.translatable("riichi_mahjong.result.wind."
                    + round.seatWind(s).name().toLowerCase(Locale.ROOT)).getString();
            Component line = Component.literal(windChar + " " + p.points());
            int tw = font.width(line);
            int ty = half - font.lineHeight - 2;
            if (turn) {
                g.fill(-half, half - 2, half, half, COL_TURN);
            }
            int color = turn ? COL_TURN : (round.dealer(s) ? 0xFFFF9A7A : COL_TEXT);
            if (rel == 2) {
                // Toimen: keep the text readable instead of upside down.
                g.pose().pushPose();
                g.pose().translate(0, ty + font.lineHeight / 2f, 0);
                g.pose().mulPose(Axis.ZP.rotationDegrees(180f));
                g.drawString(font, line, -tw / 2, -font.lineHeight / 2, color, false);
                g.pose().popPose();
            } else {
                g.drawString(font, line, -tw / 2, ty, color, false);
            }
            // Riichi stick in front of the river.
            if (p.riichi()) {
                int sw = u * 3;
                g.fill(-sw / 2, half + 2, sw / 2, half + 4, 0xFFF4F4F4);
                g.fill(-1, half + 2, 1, half + 4, 0xFFE03030);
            }
            g.pose().popPose();
        }
    }

    /**
     * Honba (100-point stick, black dots) and riichi deposit (1000-point
     * stick, red dot) counters, centred on {@code centerX}.
     */
    private void drawStickCounters(GuiGraphics g, int centerX, int y, int honba, int riichi) {
        String hText = "×" + honba;
        String rText = "×" + riichi;
        int stickW = 12;
        int groupW = stickW + 2 + Math.max(font.width(hText), font.width(rText));
        int x = centerX - (groupW * 2 + 6) / 2;
        int sy = y + font.lineHeight / 2 - 1;
        for (int k = 0; k < 2; k++) {
            g.fill(x, sy, x + stickW, sy + 3, 0xFFF4F4F4);
            if (k == 0) {
                for (int d = 2; d < stickW - 1; d += 3) g.fill(x + d, sy + 1, x + d + 1, sy + 2, 0xFF202020);
            } else {
                g.fill(x + stickW / 2 - 1, sy, x + stickW / 2 + 1, sy + 3, 0xFFE03030);
            }
            g.drawString(font, k == 0 ? hText : rText, x + stickW + 2, y, COL_TEXT_DIM, false);
            x += groupW + 6;
        }
    }

    /** Push a pose so that local +y points from the centre towards seat {@code rel}. */
    private void beginSeatFrame(GuiGraphics g, int rel) {
        g.pose().pushPose();
        g.pose().translate(cx, cy, 0);
        if (rel != 0) g.pose().mulPose(Axis.ZP.rotationDegrees(-90f * rel));
    }

    // ---- rivers --------------------------------------------------------------

    private static long discardKey(int seat, int index) {
        return ((long) seat << 32) | (index & 0xFFFFFFFFL);
    }

    /** Discards that were called by someone (shown dimmed, like the official client). */
    private static Set<Long> claimedDiscards(TheMahjongRound round) {
        Set<Long> out = new HashSet<>();
        for (TheMahjongPlayer p : round.players()) {
            for (TheMahjongMeld m : p.melds()) {
                if (m instanceof TheMahjongMeld.Chi c) out.add(discardKey(c.sourceSeat(), c.sourceDiscardIndex()));
                else if (m instanceof TheMahjongMeld.Pon pon) out.add(discardKey(pon.sourceSeat(), pon.sourceDiscardIndex()));
                else if (m instanceof TheMahjongMeld.Daiminkan dk) out.add(discardKey(dk.sourceSeat(), dk.sourceDiscardIndex()));
                else if (m instanceof TheMahjongMeld.Kakan kk) {
                    out.add(discardKey(kk.upgradedFrom().sourceSeat(), kk.upgradedFrom().sourceDiscardIndex()));
                }
            }
        }
        return out;
    }

    private void trackLastDiscard(TheMahjongRound round) {
        int n = round.players().size();
        if (lastDiscardCounts.length != n) {
            lastDiscardCounts = new int[n];
            for (int s = 0; s < n; s++) lastDiscardCounts[s] = round.players().get(s).discards().size();
            lastDiscardSeat = -1;
            return;
        }
        for (int s = 0; s < n; s++) {
            int c = round.players().get(s).discards().size();
            if (c > lastDiscardCounts[s]) lastDiscardSeat = s;
            else if (c < lastDiscardCounts[s] && lastDiscardSeat == s) lastDiscardSeat = -1; // new round
            lastDiscardCounts[s] = c;
        }
    }

    private void drawRiver(GuiGraphics g, TheMahjongRound round, int seat, int rel, Set<Long> claimed) {
        List<TheMahjongDiscard> discards = round.players().get(seat).discards();
        beginSeatFrame(g, rel);
        int x0 = -(u * RIVER_COLS) / 2;
        int y0 = box / 2 + GAP;
        int x = x0;
        int row = 0;
        int inRow = 0;
        for (int i = 0; i < discards.size(); i++) {
            if (inRow == RIVER_COLS && row < RIVER_ROWS - 1) {
                row++;
                inRow = 0;
                x = x0;
            }
            TheMahjongDiscard d = discards.get(i);
            int y = y0 + row * rh;
            int w;
            if (d.riichiDeclared()) {
                w = rh;
                drawTileSideways(g, x, y + (rh - u), u, rh, d.tile());
            } else {
                w = u;
                drawTile(g, x, y, u, rh, d.tile());
            }
            if (claimed.contains(discardKey(seat, i))) {
                g.fill(x, y, x + w, y + rh, COL_DIM);
            }
            if (seat == lastDiscardSeat && i == discards.size() - 1) {
                g.renderOutline(x - 1, y - 1, w + 2, rh + 2, COL_HIGHLIGHT);
                // Small marker pointing at the tile from the centre side.
                g.fill(x + w / 2 - 2, y - 4, x + w / 2 + 2, y - 2, COL_HIGHLIGHT);
            }
            x += w;
            inRow++;
        }
        g.pose().popPose();
    }

    // ---- opponents -----------------------------------------------------------

    /** Distance from the centre to where an opponent's hand row ends, kept close to their river. */
    private int edgeDistance(int rel) {
        int screenEdge = switch (rel) {
            case 1 -> width - cx;
            case 2 -> cy;
            case 3 -> cx;
            default -> height - cy;
        };
        int nearRiver = box / 2 + GAP + RIVER_ROWS * rh + 2 * rh + 12;
        return Math.min(screenEdge, nearRiver);
    }

    private void drawOpponent(GuiGraphics g, MahjongTableBlockEntity table, TheMahjongRound round, int seat, int rel) {
        TheMahjongPlayer p = round.players().get(seat);
        int handCount = p.currentHand().size();
        int meldW = meldsWidth(p.melds(), u, rh);
        int handW = handCount * ow;
        int total = handW + (meldW > 0 ? GAP + meldW : 0);

        beginSeatFrame(g, rel);
        int rowBottom = edgeDistance(rel) - 4;
        int x = -total / 2;
        for (int i = 0; i < handCount; i++) {
            drawTileBack(g, x, rowBottom - oh, ow, oh);
            x += ow;
        }
        if (meldW > 0) {
            drawMelds(g, p.melds(), x + GAP, rowBottom, u, rh);
        }
        // Name next to the hand (the frame is rotated, so text reads along their edge).
        Component name = seatName(table, seat);
        int nw = font.width(name);
        g.drawString(font, name, -total / 2 - nw - 6, rowBottom - oh / 2 - font.lineHeight / 2,
                round.currentTurnSeat() == seat ? COL_TURN : COL_TEXT, true);
        g.pose().popPose();
    }

    // ---- melds ---------------------------------------------------------------

    private static int meldsWidth(List<TheMahjongMeld> melds, int w, int h) {
        int total = 0;
        for (TheMahjongMeld m : melds) {
            total += meldWidth(m, w, h) + 2;
        }
        return total;
    }

    private static int meldWidth(TheMahjongMeld m, int w, int h) {
        int count = m.tiles().size();
        if (m instanceof TheMahjongMeld.Kakan) count = 3; // added tile stacks on the sideways one
        return m.sidewaysTileIndex().isPresent() ? (count - 1) * w + h : count * w;
    }

    /** Draws melds left→right starting at x, bottom-aligned on {@code bottom}. */
    private static void drawMelds(GuiGraphics g, List<TheMahjongMeld> melds, int x, int bottom, int w, int h) {
        for (TheMahjongMeld m : melds) {
            x = drawMeld(g, m, x, bottom, w, h) + 2;
        }
    }

    private static int drawMeld(GuiGraphics g, TheMahjongMeld m, int x, int bottom, int w, int h) {
        List<TheMahjongTile> tiles = m.tiles();
        if (m instanceof TheMahjongMeld.Ankan) {
            for (int i = 0; i < tiles.size(); i++) {
                if (i == 0 || i == 3) drawTileBack(g, x, bottom - h, w, h);
                else drawTile(g, x, bottom - h, w, h, tiles.get(i));
                x += w;
            }
            return x;
        }
        int side = m.sidewaysTileIndex().orElse(-1);
        int base = (m instanceof TheMahjongMeld.Kakan) ? 3 : tiles.size();
        for (int i = 0; i < base; i++) {
            if (i == side) {
                drawTileSideways(g, x, bottom - w, w, h, tiles.get(i));
                if (m instanceof TheMahjongMeld.Kakan k) {
                    drawTileSideways(g, x, bottom - 2 * w, w, h, k.addedTile());
                }
                x += h;
            } else {
                drawTile(g, x, bottom - h, w, h, tiles.get(i));
                x += w;
            }
        }
        return x;
    }

    // ---- own hand ------------------------------------------------------------

    private void drawOwnHand(GuiGraphics g, TheMahjongRound round, int viewSeat, boolean seated,
                             boolean myTurn, List<PlayerAction> legal, boolean riichiPending,
                             int mouseX, int mouseY) {
        TheMahjongPlayer me = round.players().get(viewSeat);
        List<TheMahjongTile> hand = round.handDisplayOrder(viewSeat);
        boolean drawnSeparate = round.currentTurnSeat() == viewSeat
                && round.activeTile() instanceof TheMahjongRound.ActiveTile.Drawn;

        Set<TheMahjongTile> discardable = new HashSet<>();
        for (PlayerAction a : legal) {
            if (riichiPending) {
                if (a instanceof PlayerAction.DiscardWithRiichi r) discardable.add(r.tile());
            } else if (a instanceof PlayerAction.Discard d) {
                discardable.add(d.tile());
            }
        }

        int meldW = meldsWidth(me.melds(), u, rh);
        int handW = hand.size() * hw + (drawnSeparate ? GAP : 0);
        int total = handW + (meldW > 0 ? GAP * 3 + meldW : 0);
        int x0 = Math.max(4, (width - total) / 2);

        int x = x0;
        for (int i = 0; i < hand.size(); i++) {
            if (drawnSeparate && i == hand.size() - 1) x += GAP;
            TheMahjongTile tile = hand.get(i);
            int y = handY;
            boolean hovered = mouseX >= x && mouseX < x + hw && mouseY >= y - hh / 5 && mouseY < y + hh;
            boolean canDiscard = myTurn && discardable.contains(tile);
            if (!seated) {
                drawTileBack(g, x, y, hw, hh);
            } else {
                if (hovered && canDiscard) y -= hh / 5;
                drawTile(g, x, y, hw, hh, tile);
                if (myTurn && !discardable.isEmpty() && !canDiscard) {
                    g.fill(x, y, x + hw, y + hh, COL_DIM);
                }
                if (hovered && canDiscard) {
                    g.renderOutline(x - 1, y - 1, hw + 2, hh + 2, COL_HIGHLIGHT);
                }
                if (myTurn) {
                    hits.add(new Hit(x, handY - hh / 5, x + hw, handY + hh,
                            new InteractKey.SeatSlot((byte) viewSeat, InteractKey.SeatSlot.AREA_HAND, (short) i)));
                }
            }
            x += hw;
        }
        if (meldW > 0) {
            int mx = x0 + total - meldW;
            drawMelds(g, me.melds(), mx, handY + hh, u, rh);
        }
    }

    // ---- action buttons ------------------------------------------------------

    private void drawButtons(GuiGraphics g, TheMahjongDriver driver, TheMahjongRound round, int seat,
                             boolean riichiPending, int mouseX, int mouseY) {
        MatchPhase phase = driver.currentPhase();
        if (!(phase instanceof MatchPhase.AwaitingDiscard) && !(phase instanceof MatchPhase.AwaitingClaims)) return;
        List<MahjongTableHumanPlayer.TableButton> buttons;
        try {
            buttons = MahjongTableHumanPlayer.tableButtons(driver, seat, riichiPending);
        } catch (RuntimeException e) {
            return;
        }
        if (buttons.isEmpty()) return;

        int mw = Math.max(6, Math.round(u * 0.7f));
        int mh = tileHeight(mw);
        int bh = Math.max(20, mh + 8);
        int y = handY - bh - Math.max(10, hh / 4);

        // Measure right→left so the row hugs the right side like the official client.
        int[] widths = new int[buttons.size()];
        int totalW = 0;
        for (int i = 0; i < buttons.size(); i++) {
            var b = buttons.get(i);
            List<TheMahjongTile> ghosts = ghostTiles(b);
            widths[i] = Math.max(52, font.width(buttonLabel(b)) + 20 + (ghosts.isEmpty() ? 0 : ghosts.size() * mw + 6));
            totalW += widths[i] + 4;
        }
        int x = Math.min(width - 8, cx + totalW / 2 + box) - totalW;
        x = Math.max(4, x);

        for (int i = 0; i < buttons.size(); i++) {
            var b = buttons.get(i);
            int w = widths[i];
            boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + bh;
            int bg = buttonColor(b);
            g.fill(x - 1, y - 1, x + w + 1, y + bh + 1, 0x60FFFFFF);
            g.fill(x, y, x + w, y + bh, bg);
            if (hovered) g.fill(x, y, x + w, y + bh, 0x30FFFFFF);

            Component label = buttonLabel(b);
            List<TheMahjongTile> ghosts = ghostTiles(b);
            int contentW = font.width(label) + (ghosts.isEmpty() ? 0 : 6 + ghosts.size() * mw);
            int tx = x + (w - contentW) / 2;
            g.drawString(font, label, tx, y + (bh - font.lineHeight) / 2 + 1, buttonTextColor(b), true);
            int gx = tx + font.width(label) + 6;
            for (TheMahjongTile t : ghosts) {
                drawTile(g, gx, y + (bh - mh) / 2, mw, mh, t);
                gx += mw;
            }
            hits.add(new Hit(x, y, x + w, y + bh,
                    new InteractKey.SeatSlot((byte) seat, InteractKey.SeatSlot.AREA_BUTTON, (short) i)));
            x += w + 4;
        }
    }

    private static Component buttonLabel(MahjongTableHumanPlayer.TableButton b) {
        if (b instanceof MahjongTableHumanPlayer.TableButton.RiichiToggle) return action("riichi");
        if (!(b instanceof MahjongTableHumanPlayer.TableButton.SubmitAction sa)) return Component.literal("?");
        PlayerAction a = sa.action();
        if (a instanceof PlayerAction.DeclareRon) return action("ron");
        if (a instanceof PlayerAction.DeclareChankan) return action("chankan");
        if (a instanceof PlayerAction.DeclareTsumo) return action("tsumo");
        if (a instanceof PlayerAction.Pon) return action("pon");
        if (a instanceof PlayerAction.Daiminkan) return action("kan");
        if (a instanceof PlayerAction.Ankan) return action("ankan");
        if (a instanceof PlayerAction.Kakan) return action("kan");
        if (a instanceof PlayerAction.Chi) return action("chi");
        if (a instanceof PlayerAction.DeclareKita) return action("kita");
        if (a instanceof PlayerAction.KyuushuAbort) return action("abort");
        if (a instanceof PlayerAction.Pass) return action("pass");
        return Component.literal("?");
    }

    private static Component action(String key) {
        return Component.translatable("riichi_mahjong.button.action." + key);
    }

    private static List<TheMahjongTile> ghostTiles(MahjongTableHumanPlayer.TableButton b) {
        if (!(b instanceof MahjongTableHumanPlayer.TableButton.SubmitAction sa)) return List.of();
        PlayerAction a = sa.action();
        if (a instanceof PlayerAction.Chi c) return c.handTiles();
        if (a instanceof PlayerAction.Pon p) return p.handTiles();
        if (a instanceof PlayerAction.Daiminkan k) return k.handTiles();
        if (a instanceof PlayerAction.Ankan k) return k.handTiles().isEmpty() ? List.of() : List.of(k.handTiles().get(0));
        if (a instanceof PlayerAction.Kakan k) return List.of(k.addedTile());
        return List.of();
    }

    private static int buttonColor(MahjongTableHumanPlayer.TableButton b) {
        if (b instanceof MahjongTableHumanPlayer.TableButton.RiichiToggle r) {
            return r.active() ? 0xF0A07A1A : 0xE05A4218;
        }
        if (b instanceof MahjongTableHumanPlayer.TableButton.SubmitAction sa) {
            PlayerAction a = sa.action();
            if (a instanceof PlayerAction.DeclareRon || a instanceof PlayerAction.DeclareTsumo
                    || a instanceof PlayerAction.DeclareChankan) return 0xF0B02828;
            if (a instanceof PlayerAction.Pass) return 0xE0393D48;
        }
        return 0xE02E5A86;
    }

    private static int buttonTextColor(MahjongTableHumanPlayer.TableButton b) {
        if (b instanceof MahjongTableHumanPlayer.TableButton.RiichiToggle) return 0xFFFFE066;
        if (b instanceof MahjongTableHumanPlayer.TableButton.SubmitAction sa
                && sa.action() instanceof PlayerAction.Pass) return 0xFFBFC6D2;
        return 0xFFFFFFFF;
    }

    private void drawStatusLine(GuiGraphics g, MatchPhase phase, int seat, boolean myTurn, boolean riichiPending) {
        Component msg = null;
        if (myTurn) {
            msg = Component.translatable(riichiPending
                    ? "riichi_mahjong.screen.game.status.riichi"
                    : "riichi_mahjong.screen.game.status.discard");
        } else if (phase instanceof MatchPhase.AwaitingClaims ac && ac.pendingSeats().contains(seat)) {
            msg = Component.translatable("riichi_mahjong.screen.game.status.claim");
        }
        if (msg != null) {
            g.drawString(font, msg, 8, handY - font.lineHeight - 6, COL_GOLD, true);
        }
    }

    // ---- result / final panels -------------------------------------------------

    private void drawResultPanel(GuiGraphics g, MahjongTableBlockEntity table, TheMahjongRound round,
                                 MatchPhase.RoundEnded re, boolean seated, int mouseX, int mouseY) {
        ResultAnimStage stage = table.resultAnimStage();
        int yakuRevealed = table.resultAnimYakuIdx();
        boolean showFinal = stage == ResultAnimStage.SHOW_FINAL || stage == ResultAnimStage.AWAITING_ADVANCE;

        List<Component> lines = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        Component wind = Component.translatable(
                "riichi_mahjong.result.wind." + round.roundWind().name().toLowerCase(Locale.ROOT));
        lines.add(Component.translatable("riichi_mahjong.result.round_label",
                wind, Component.literal(String.valueOf(round.handNumber()))));
        colors.add(COL_GOLD);

        if (re.winResults().isEmpty()) {
            lines.add(Component.translatable("riichi_mahjong.result.draw.no_winner"));
            colors.add(COL_TEXT);
        } else {
            WinResult win = re.winResults().get(0);
            int winner = winnerSeat(win);
            boolean tsumo = isTsumo(win, winner);
            if (winner >= 0) {
                lines.add(Component.translatable(tsumo ? "riichi_mahjong.result.header.tsumo"
                        : "riichi_mahjong.result.header.ron", seatName(table, winner)));
                colors.add(0xFFFF7070);
            }
            boolean yakuman = !win.yakuman().isEmpty();
            int idx = 0;
            for (var y : win.yakuman()) {
                if (idx++ >= yakuRevealed) break;
                lines.add(yakuName(y.name()));
                colors.add(COL_TEXT);
            }
            for (var y : win.yaku()) {
                if (idx++ >= yakuRevealed) break;
                lines.add(Component.translatable("riichi_mahjong.result.yaku_line",
                        yakuName(y.name()), Component.literal(String.valueOf(y.closedHan()))));
                colors.add(COL_TEXT);
            }
            if (!yakuman && win.doraCount() > 0 && idx++ < yakuRevealed) {
                lines.add(Component.translatable("riichi_mahjong.result.dora_line",
                        Component.translatable("riichi_mahjong.yaku.dora"),
                        Component.literal(String.valueOf(win.doraCount()))));
                colors.add(COL_TEXT);
            }
            if (showFinal) {
                lines.add(Component.translatable("riichi_mahjong.screen.game.han_fu", win.han(), win.fu()));
                colors.add(COL_GOLD);
                for (int i = 0; i < win.pointDeltas().size(); i++) {
                    int d = win.pointDeltas().get(i);
                    if (d == 0) continue;
                    lines.add(Component.translatable("riichi_mahjong.result.delta_line",
                            seatName(table, i), Component.literal(d > 0 ? "+" : ""),
                            Component.literal(String.valueOf(d))));
                    colors.add(d > 0 ? 0xFF7CE07C : 0xFFFF8A8A);
                }
            }
        }

        boolean canAdvance = stage == ResultAnimStage.AWAITING_ADVANCE && seated;
        int lineH = font.lineHeight + 3;
        int pw = 200;
        for (Component c : lines) pw = Math.max(pw, font.width(c) + 24);
        int ph = lines.size() * lineH + 16 + (canAdvance ? 26 : 0);
        int px = cx - pw / 2;
        int py = Math.max(4, cy - ph / 2);
        g.fill(px - 1, py - 1, px + pw + 1, py + ph + 1, 0xFFB08A3A);
        g.fill(px, py, px + pw, py + ph, 0xF0101418);
        int y = py + 8;
        for (int i = 0; i < lines.size(); i++) {
            g.drawCenteredString(font, lines.get(i), cx, y, colors.get(i));
            y += lineH;
        }
        if (canAdvance) {
            int bw = 110;
            int bh = 18;
            int bx = cx - bw / 2;
            int by = py + ph - bh - 6;
            boolean hovered = mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + bh;
            g.fill(bx, by, bx + bw, by + bh, hovered ? 0xFF3C78B0 : 0xFF2E5A86);
            g.drawCenteredString(font, Component.translatable("riichi_mahjong.screen.game.next_round"),
                    cx, by + (bh - font.lineHeight) / 2 + 1, 0xFFFFFFFF);
            hits.add(new Hit(bx, by, bx + bw, by + bh, new InteractKey.Named(MahjongTableBlockEntity.NEXT_ROUND_KEY)));
        }
    }

    private void drawFinalPanel(GuiGraphics g, MahjongTableBlockEntity table, TheMahjongRound round) {
        int n = round.players().size();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < n; i++) order.add(i);
        order.sort((a, b) -> Integer.compare(round.players().get(b).points(), round.players().get(a).points()));

        int lineH = font.lineHeight + 4;
        int pw = 200;
        int ph = (n + 1) * lineH + 16;
        int px = cx - pw / 2;
        int py = cy - ph / 2;
        g.fill(px - 1, py - 1, px + pw + 1, py + ph + 1, 0xFFB08A3A);
        g.fill(px, py, px + pw, py + ph, 0xF0101418);
        int y = py + 8;
        g.drawCenteredString(font, Component.translatable("riichi_mahjong.screen.game.match_end"), cx, y, COL_GOLD);
        y += lineH;
        for (int rank = 0; rank < n; rank++) {
            int s = order.get(rank);
            Component line = Component.literal((rank + 1) + ". ")
                    .append(seatName(table, s))
                    .append(Component.literal("  " + round.players().get(s).points()));
            g.drawCenteredString(font, line, cx, y, rank == 0 ? COL_GOLD : COL_TEXT);
            y += lineH;
        }
    }

    private static int winnerSeat(WinResult win) {
        int best = -1;
        int bestDelta = 0;
        for (int i = 0; i < win.pointDeltas().size(); i++) {
            int d = win.pointDeltas().get(i);
            if (d > bestDelta) {
                bestDelta = d;
                best = i;
            }
        }
        return best;
    }

    private static boolean isTsumo(WinResult win, int winner) {
        int losers = 0;
        for (int i = 0; i < win.pointDeltas().size(); i++) {
            if (i != winner && win.pointDeltas().get(i) < 0) losers++;
        }
        return losers > 1;
    }

    private static Component yakuName(Object rawName) {
        String canonical = MahjongWinEffects.canonicalYakuKey(rawName);
        String human = MahjongWinEffects.humanizeYakuName(rawName);
        if (canonical == null || canonical.isEmpty()) return Component.literal(human);
        return Component.translatableWithFallback("riichi_mahjong.yaku." + canonical, human);
    }

    private static Component seatName(MahjongTableBlockEntity table, int seat) {
        var seats = table.seats();
        if (seat >= 0 && seat < seats.size()) {
            var occ = seats.get(seat).occupant();
            if (occ.isPresent()) {
                var conn = Minecraft.getInstance().getConnection();
                PlayerInfo info = conn != null ? conn.getPlayerInfo(occ.get()) : null;
                if (info != null) return Component.literal(info.getProfile().getName());
                return Component.literal("?");
            }
        }
        return Component.translatable("riichi_mahjong.screen.game.bot", seat + 1);
    }

    // ======================================================================
    //  Input
    // ======================================================================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        MahjongTableBlockEntity table = table();
        if (table == null || table.driver() == null) return super.mouseClicked(mouseX, mouseY, button);

        if (button == 0) {
            // Iterate newest-first so overlays (result panel) win over what's under them.
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit h = hits.get(i);
                if (h.contains(mouseX, mouseY)) {
                    send(h.key());
                    return true;
                }
            }
        } else if (button == 1) {
            // Right click anywhere = tsumogiri (discard the drawn tile), same as
            // right-clicking the table in the world.
            int seat = localSeat(table);
            TheMahjongDriver driver = table.driver();
            TheMahjongRound round = driver.match().currentRound().orElse(null);
            if (seat >= 0 && round != null
                    && driver.currentPhase() instanceof MatchPhase.AwaitingDiscard ad && ad.seat() == seat
                    && round.activeTile() instanceof TheMahjongRound.ActiveTile.Drawn) {
                int last = round.handDisplayOrder(seat).size() - 1;
                send(new InteractKey.SeatSlot((byte) seat, InteractKey.SeatSlot.AREA_HAND, (short) last));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void send(InteractKey key) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        NetworkManager.sendToServer(new C2SCuteClickPacket(mc.level.dimension(), tablePos, key));
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (MahjongGameScreenKeys.OPEN.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
