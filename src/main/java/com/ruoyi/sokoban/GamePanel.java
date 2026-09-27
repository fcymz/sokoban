package com.ruoyi.sokoban;

import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.util.Arrays;
import java.util.List;

/**
 * 棋盘绘制面板：只负责“画”，不负责规则，也不直接处理按键。
 *
 * <p>棋盘会按面板大小自动缩放并居中，因此窗口拉大拉小都不需要改动关卡数据。</p>
 */
public final class GamePanel extends JPanel {

    private static final long serialVersionUID = 1L;

    /* ---------------- 配色 ---------------- */
    private static final Color BG           = new Color(0x18, 0x1C, 0x2F);
    private static final Color FLOOR_A      = new Color(0x1D, 0x23, 0x37);
    private static final Color FLOOR_B      = new Color(0x20, 0x26, 0x3C);
    private static final Color GRID_LINE    = new Color(255, 255, 255, 10);
    private static final Color WALL_TOP     = new Color(0x4B, 0x56, 0x80);
    private static final Color WALL_BOTTOM  = new Color(0x33, 0x3B, 0x5E);
    private static final Color WALL_EDGE    = new Color(255, 255, 255, 26);
    private static final Color GOAL_RING    = new Color(255, 207, 107, 217);
    private static final Color GOAL_DOT     = new Color(255, 207, 107, 77);
    private static final Color BOX_TOP      = new Color(0xE0, 0xA4, 0x63);
    private static final Color BOX_BOTTOM   = new Color(0xB0, 0x76, 0x3A);
    private static final Color BOX_ON_TOP   = new Color(0x5F, 0xE0, 0xB4);
    private static final Color BOX_ON_BOTTOM = new Color(0x2F, 0x9E, 0x7A);
    private static final Color BOX_HALO     = new Color(79, 209, 165, 60);
    private static final Color PLAYER_LIGHT = new Color(0x9D, 0xBC, 0xFF);
    private static final Color PLAYER_DARK  = new Color(0x3F, 0x6A, 0xE0);
    private static final Color EYE          = new Color(0x0D, 0x13, 0x30);
    private static final Color TEXT_MAIN    = new Color(0xE8, 0xEC, 0xFF);
    private static final Color TEXT_DIM     = new Color(0xB6, 0xBF, 0xE6);
    private static final Color TEXT_WARN    = new Color(0xFF, 0xB4, 0x54);
    private static final Color OVERLAY_BG   = new Color(10, 12, 24, 218);

    /** 面板四周留白（像素）。 */
    private static final int MARGIN = 14;

    /** 配对编号使用的颜色，按编号取模。 */
    private static final Color[] PAIR_COLORS = {
        new Color(0x6F, 0xB7, 0xFF),
        new Color(0xFF, 0x8F, 0xB0),
        new Color(0x9B, 0x8C, 0xFF),
        new Color(0x4D, 0xD4, 0xC4),
        new Color(0xFF, 0xC8, 0x4D),
        new Color(0xA8, 0xE0, 0x5A),
        new Color(0xFF, 0x9E, 0x64),
        new Color(0x7F, 0xD1, 0xFF)
    };

    private static Color pairColor(int label) {
        return PAIR_COLORS[(label - 1 + PAIR_COLORS.length) % PAIR_COLORS.length];
    }

    private static final String FONT_FAMILY = pickFontFamily();

    private final SokobanGame game;

    /** 状态变化后的回调（用于刷新窗口上的步数等文字）。 */
    private Runnable onStateChanged;

    /**
     * @param game 要展示的游戏
     */
    public GamePanel(SokobanGame game) {
        if (game == null) {
            throw new IllegalArgumentException("game 不能为 null");
        }
        this.game = game;
        setBackground(BG);
        setOpaque(true);
        setPreferredSize(new Dimension(760, 520));
        setFocusable(true);
    }

    /**
     * 设置状态变化回调，每次成功操作后触发。
     *
     * @param callback 回调，可为 {@code null}
     */
    public void setOnStateChanged(Runnable callback) {
        this.onStateChanged = callback;
    }

    /** 触发一次重绘并通知外部刷新文字。 */
    public void refresh() {
        if (onStateChanged != null) {
            onStateChanged.run();
        }
        repaint();
    }

    /* ---------------- 绘制 ---------------- */

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();
            g2.setColor(BG);
            g2.fillRect(0, 0, w, h);

            Level level = game.getLevel();
            int cols = level.getWidth();
            int rows = level.getHeight();
            int tile = Math.max(8, Math.min((w - MARGIN * 2) / cols, (h - MARGIN * 2) / rows));
            int boardW = tile * cols;
            int boardH = tile * rows;
            int ox = (w - boardW) / 2;
            int oy = (h - boardH) / 2;

            drawBoard(g2, level, ox, oy, tile);
            drawPieces(g2, level, ox, oy, tile);

            if (game.isWon()) {
                drawWinOverlay(g2, w, h);
            } else if (game.isDeadlocked()) {
                drawBanner(g2, w, h, "箱子被卡死了：按 U 撤销，或按 R 重来", TEXT_WARN);
            }
        } finally {
            g2.dispose();
        }
    }

    private void drawBoard(Graphics2D g, Level level, int ox, int oy, int tile) {
        for (int y = 0; y < level.getHeight(); y++) {
            for (int x = 0; x < level.getWidth(); x++) {
                int px = ox + x * tile;
                int py = oy + y * tile;
                if (level.isWall(x, y)) {
                    drawWall(g, px, py, tile);
                } else {
                    drawFloor(g, px, py, tile, (x + y) % 2 == 0);
                }
            }
        }
    }

    private void drawFloor(Graphics2D g, int px, int py, int tile, boolean alt) {
        g.setColor(alt ? FLOOR_A : FLOOR_B);
        g.fillRect(px, py, tile, tile);
        g.setColor(GRID_LINE);
        g.drawRect(px, py, tile - 1, tile - 1);
    }

    private void drawWall(Graphics2D g, int px, int py, int tile) {
        float pad = Math.max(1f, tile * 0.045f);
        float r = Math.max(2f, tile * 0.14f);
        float size = tile - pad * 2f;
        g.setPaint(new GradientPaint(px, py, WALL_TOP, px, py + tile, WALL_BOTTOM));
        g.fill(new RoundRectangle2D.Float(px + pad, py + pad, size, size, r * 2f, r * 2f));
        g.setColor(WALL_EDGE);
        g.setStroke(new BasicStroke(Math.max(1f, tile * 0.03f)));
        g.draw(new RoundRectangle2D.Float(px + pad, py + pad, size, size, r * 2f, r * 2f));
    }

    private void drawGoal(Graphics2D g, int px, int py, int tile, int label) {
        int cx = px + tile / 2;
        int cy = py + tile / 2;
        int ring = Math.round(tile * 0.24f);
        Color color = label > 0 ? pairColor(label) : GOAL_RING;
        g.setColor(color);
        g.setStroke(new BasicStroke(Math.max(2f, tile * 0.07f)));
        g.draw(new Ellipse2D.Float(cx - ring, cy - ring, ring * 2f, ring * 2f));
        if (label > 0) {
            // 配对模式下，目标点里写上它对应的箱子编号
            g.setFont(uiFont(Math.max(9, Math.round(tile * 0.34f)), Font.BOLD));
            FontMetrics metrics = g.getFontMetrics();
            String text = String.valueOf(label);
            g.drawString(text, cx - metrics.stringWidth(text) / 2f,
                    cy + (metrics.getAscent() - metrics.getDescent()) / 2f);
            return;
        }
        int dot = Math.max(1, Math.round(tile * 0.10f));
        g.setColor(GOAL_DOT);
        g.fill(new Ellipse2D.Float(cx - dot, cy - dot, dot * 2f, dot * 2f));
    }

    private void drawBox(Graphics2D g, int px, int py, int tile, boolean onGoal, int label) {
        float pad = tile * 0.10f;
        float size = tile - pad * 2f;
        float r = Math.max(3f, tile * 0.16f);
        float x = px + pad;
        float y = py + pad;

        if (onGoal) {
            g.setColor(BOX_HALO);
            float e = tile * 0.06f;
            g.fill(new RoundRectangle2D.Float(x - e, y - e, size + e * 2f, size + e * 2f,
                    (r + e) * 2f, (r + e) * 2f));
        }

        g.setPaint(new GradientPaint(x, y, onGoal ? BOX_ON_TOP : BOX_TOP,
                x, y + size, onGoal ? BOX_ON_BOTTOM : BOX_BOTTOM));
        g.fill(new RoundRectangle2D.Float(x, y, size, size, r * 2f, r * 2f));

        g.setColor(onGoal ? new Color(10, 45, 32, 140) : new Color(90, 50, 10, 140));
        g.setStroke(new BasicStroke(Math.max(1f, tile * 0.05f)));
        g.draw(new RoundRectangle2D.Float(x, y, size, size, r * 2f, r * 2f));

        if (label > 0) {
            // 配对模式：箱子正中画一个编号徽章，颜色和它对应的目标点一致
            float badge = size * 0.62f;
            float bx = x + (size - badge) / 2f;
            float by = y + (size - badge) / 2f;
            g.setColor(pairColor(label));
            g.fill(new RoundRectangle2D.Float(bx, by, badge, badge,
                    badge * 0.5f, badge * 0.5f));

            g.setFont(uiFont(Math.max(9, Math.round(tile * 0.34f)), Font.BOLD));
            FontMetrics metrics = g.getFontMetrics();
            String text = String.valueOf(label);
            g.setColor(new Color(0x10, 0x16, 0x2A));
            g.drawString(text, bx + (badge - metrics.stringWidth(text)) / 2f,
                    by + (badge + metrics.getAscent() - metrics.getDescent()) / 2f);
            return;
        }

        float q = size * 0.22f;
        g.setColor(onGoal ? new Color(255, 255, 255, 140) : new Color(255, 236, 208, 140));
        g.setStroke(new BasicStroke(Math.max(1.2f, tile * 0.055f)));
        g.drawLine(Math.round(x + q), Math.round(y + q),
                Math.round(x + size - q), Math.round(y + size - q));
        g.drawLine(Math.round(x + size - q), Math.round(y + q),
                Math.round(x + q), Math.round(y + size - q));
    }

    private void drawPlayer(Graphics2D g, int px, int py, int tile) {
        float cx = px + tile / 2f;
        float cy = py + tile / 2f;
        float radius = tile * 0.32f;
        if (radius <= 0.5f) {
            return;
        }

        g.setPaint(new RadialGradientPaint(
                new Point2D.Float(cx - radius * 0.3f, cy - radius * 0.4f),
                radius, new float[] {0f, 1f},
                new Color[] {PLAYER_LIGHT, PLAYER_DARK}));
        g.fill(new Ellipse2D.Float(cx - radius, cy - radius, radius * 2f, radius * 2f));

        SokobanGame.Dir facing = game.getFacing();
        float offsetX = facing.dx * radius * 0.26f;
        float offsetY = facing.dy * radius * 0.26f;
        float perpX = -facing.dy;
        float perpY = facing.dx;
        float eye = Math.max(1f, radius * 0.135f);
        g.setColor(EYE);
        for (int sign = -1; sign <= 1; sign += 2) {
            float ex = cx + offsetX + perpX * radius * 0.30f * sign;
            float ey = cy + offsetY + perpY * radius * 0.30f * sign;
            g.fill(new Ellipse2D.Float(ex - eye, ey - eye, eye * 2f, eye * 2f));
        }
    }

    private void drawPieces(Graphics2D g, Level level, int ox, int oy, int tile) {
        int[] boxes = game.getBoxes();
        boolean[] boxHere = new boolean[level.getWidth() * level.getHeight()];
        for (int box : boxes) {
            boxHere[box] = true;
        }

        for (int y = 0; y < level.getHeight(); y++) {
            for (int x = 0; x < level.getWidth(); x++) {
                int index = y * level.getWidth() + x;
                if (level.isGoal(index) && !boxHere[index]) {
                    drawGoal(g, ox + x * tile, oy + y * tile, tile, level.getGoalLabel(index));
                }
            }
        }
        for (int i = 0; i < boxes.length; i++) {
            int box = boxes[i];
            int x = box % level.getWidth();
            int y = box / level.getWidth();
            // 配对模式下只有进了“自己的”目标点才算就位
            drawBox(g, ox + x * tile, oy + y * tile, tile,
                    level.isBoxPlaced(i, box), level.getBoxLabel(i));
        }

        int player = game.getPlayer();
        drawPlayer(g, ox + (player % level.getWidth()) * tile,
                oy + (player / level.getWidth()) * tile, tile);
    }

    private void drawWinOverlay(Graphics2D g, int width, int height) {
        g.setColor(OVERLAY_BG);
        g.fillRect(0, 0, width, height);

        boolean allBuiltIn = !game.isEndless()
                && game.getLevelIndex() == game.getBuiltInCount() - 1;
        int titleSize = clamp(Math.min(width, height) / 12, 20, 40);
        int bodySize = clamp(Math.min(width, height) / 34, 12, 18);
        float baseY = height * 0.36f;

        g.setFont(uiFont(titleSize, Font.BOLD));
        g.setColor(TEXT_MAIN);
        drawCentered(g, allBuiltIn ? "内置关卡全部通关！" : "过关！", width, baseY);

        g.setFont(uiFont(bodySize, Font.PLAIN));
        g.setColor(TEXT_DIM);
        drawCentered(g, game.getLevelTitle()
                        + "　用了 " + game.getSteps() + " 步，推动 " + game.getPushes() + " 次",
                width, baseY + titleSize * 1.1f);

        g.setColor(TEXT_MAIN);
        drawCentered(g, allBuiltIn ? "按 N 进入无尽模式 · 按 R 重玩本关"
                        : "按 N 进入下一关 · 按 R 重玩本关 · 按 H 自动演示",
                width, baseY + titleSize * 1.1f + bodySize * 2.0f);
    }

    private void drawBanner(Graphics2D g, int width, int height, String text, Color color) {
        int size = clamp(Math.min(width, height) / 38, 12, 17);
        g.setFont(uiFont(size, Font.BOLD));
        int textWidth = g.getFontMetrics().stringWidth(text);
        int padX = size;
        int barHeight = size * 2;
        int x = (width - textWidth) / 2 - padX;
        int y = height - barHeight - 12;

        g.setColor(new Color(20, 16, 8, 220));
        g.fill(new RoundRectangle2D.Float(x, y, textWidth + padX * 2f, barHeight,
                barHeight, barHeight));
        g.setColor(color);
        drawCentered(g, text, width, y + barHeight * 0.68f);
    }

    private void drawCentered(Graphics2D g, String text, int width, float baselineY) {
        int textWidth = g.getFontMetrics().stringWidth(text);
        g.drawString(text, (width - textWidth) / 2, baselineY);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /* ---------------- 字体 ---------------- */

    /**
     * 返回支持中文的界面字体。
     *
     * @param size  字号
     * @param style {@link Font} 的样式常量
     * @return 字体对象
     */
    static Font uiFont(int size, int style) {
        return new Font(FONT_FAMILY, style, size);
    }

    private static String pickFontFamily() {
        String[] candidates = {
            "Microsoft YaHei UI", "Microsoft YaHei", "PingFang SC",
            "Noto Sans CJK SC", "Source Han Sans SC", "WenQuanYi Micro Hei", "SimHei"
        };
        try {
            List<String> available = Arrays.asList(GraphicsEnvironment
                    .getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
            for (String family : candidates) {
                if (available.contains(family)) {
                    return family;
                }
            }
        } catch (Throwable ignored) {
            // 字体枚举失败时退回逻辑字体
        }
        return Font.SANS_SERIF;
    }
}
