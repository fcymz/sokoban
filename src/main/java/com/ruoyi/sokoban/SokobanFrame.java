package com.ruoyi.sokoban;

import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.BorderFactory;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 游戏主窗口：顶部信息栏、中间棋盘、底部按钮与状态提示，并负责快捷键与“提示”自动演示。
 *
 * <p>关卡进度（已解锁到第几关）与最佳步数通过 {@link SaveData} 持久化；
 * “没通过当前关卡就不能进入下一关”由 {@link SokobanGame#canAdvance()} 把关，
 * 下一关按钮和 N 键都会受它限制。</p>
 */
public final class SokobanFrame extends JFrame {

    private static final long serialVersionUID = 1L;

    private static final Color BG        = new Color(0x18, 0x1C, 0x2F);
    private static final Color TEXT_MAIN = new Color(0xE8, 0xEC, 0xFF);
    private static final Color TEXT_DIM  = new Color(0x8F, 0x98, 0xC4);
    private static final Color TEXT_WARN = new Color(0xFF, 0xB4, 0x54);
    private static final Color TEXT_OK   = new Color(0x3D, 0xDC, 0x97);
    private static final Color LINE      = new Color(0x2A, 0x30, 0x50);
    private static final Color BTN_BG    = new Color(0x20, 0x26, 0x3F);

    /** 自动演示每一步之间的间隔上限（毫秒）。 */
    private static final int HINT_MAX_DELAY_MS = 130;
    /** 自动演示每一步之间的间隔下限（毫秒）。 */
    private static final int HINT_MIN_DELAY_MS = 35;
    /** 自动演示希望占用的总时长（毫秒），用于反推单步间隔。 */
    private static final long HINT_TARGET_TOTAL_MS = 5000L;
    /** 提示求解器的状态上限：算不动就退回关卡自带的解法。 */
    private static final int HINT_SOLVER_LIMIT = 400000;

    private final Campaign campaign;
    private final SokobanGame game;
    private final GamePanel board;
    private final SaveData save;

    private final JLabel levelLabel = chip();
    private final JLabel mapLabel = chip();
    private final JLabel stepsLabel = chip();
    private final JLabel pushesLabel = chip();
    private final JLabel bestLabel = chip();
    private final JLabel statusLabel = new JLabel(" ", JLabel.CENTER);

    private final JButton hintButton;
    private final JButton nextButton;

    /** 内置关卡的最短解缓存（无尽关卡用生成时自带的解法）。 */
    private final Map<Integer, List<SokobanGame.Dir>> solvedCache =
            new HashMap<Integer, List<SokobanGame.Dir>>();

    /** 防止同一关反复写入成绩。 */
    private boolean winRecorded;

    private Timer hintTimer;
    private List<SokobanGame.Dir> hintPlan;
    private int hintCursor;

    /** 作弊码识别（上 下 左 右 左 右 上 下）。 */
    private final CheatCode cheat = new CheatCode();

    /** 使用默认战役创建一个游戏窗口。 */
    public SokobanFrame() {
        this(Campaign.createDefault(), new SaveData());
    }

    /**
     * 使用指定战役与存档创建窗口。
     *
     * @param campaign 关卡来源
     * @param saveData 存档
     */
    public SokobanFrame(Campaign campaign, SaveData saveData) {
        super("推箱子 · Sokoban");
        this.campaign = campaign;
        this.save = saveData;
        this.game = new SokobanGame(campaign);
        this.board = new GamePanel(game);

        // 从存档恢复解锁进度
        game.setMaxUnlockedLevel(save.getMaxUnlockedLevel());

        board.setOnStateChanged(new Runnable() {
            @Override
            public void run() {
                updateHud();
            }
        });

        this.hintButton = button("提示 (H)", new Runnable() {
            @Override
            public void run() {
                doHint();
            }
        });
        this.nextButton = button("下一关 (N)", new Runnable() {
            @Override
            public void run() {
                doChangeLevel(1);
            }
        });

        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());
        getContentPane().setBackground(BG);
        add(buildHeader(), BorderLayout.NORTH);
        add(board, BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);

        bindKeys();
        updateHud();

        setSize(920, 800);
        setMinimumSize(new Dimension(620, 520));
        setLocationRelativeTo(null);
    }

    /* ---------------- 界面搭建 ---------------- */

    private JComponent buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(12, 16, 6, 16));

        JLabel title = new JLabel("推箱子");
        title.setFont(GamePanel.uiFont(18, Font.BOLD));
        title.setForeground(TEXT_MAIN);

        JLabel sub = new JLabel("  SOKOBAN");
        sub.setFont(GamePanel.uiFont(11, Font.PLAIN));
        sub.setForeground(TEXT_DIM);

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        left.setOpaque(false);
        left.add(title);
        left.add(sub);

        JPanel stats = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        stats.setOpaque(false);
        stats.add(levelLabel);
        stats.add(mapLabel);
        stats.add(stepsLabel);
        stats.add(pushesLabel);
        stats.add(bestLabel);

        header.add(left, BorderLayout.WEST);
        header.add(stats, BorderLayout.EAST);
        return header;
    }

    private JComponent buildFooter() {
        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createEmptyBorder(4, 16, 12, 16));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 6));
        buttons.setOpaque(false);
        buttons.add(button("撤销 (U)", new Runnable() {
            @Override
            public void run() {
                doUndo();
            }
        }));
        buttons.add(button("重来 (R)", new Runnable() {
            @Override
            public void run() {
                doReset();
            }
        }));
        buttons.add(hintButton);
        buttons.add(button("上一关 (P)", new Runnable() {
            @Override
            public void run() {
                doChangeLevel(-1);
            }
        }));
        buttons.add(nextButton);
        buttons.add(button("清除记录", new Runnable() {
            @Override
            public void run() {
                doClearScores();
            }
        }));

        statusLabel.setFont(GamePanel.uiFont(12, Font.PLAIN));
        statusLabel.setForeground(TEXT_DIM);

        JLabel hint = new JLabel(
                "方向键 / WASD 移动 · U 撤销 · R 重来 · H 提示 · N 下一关 · P 上一关"
                        + "　（过关后才能进入下一关）", JLabel.CENTER);
        hint.setFont(GamePanel.uiFont(11, Font.PLAIN));
        hint.setForeground(TEXT_DIM);

        footer.add(buttons, BorderLayout.NORTH);
        footer.add(statusLabel, BorderLayout.CENTER);
        footer.add(hint, BorderLayout.SOUTH);
        return footer;
    }

    private static JLabel chip() {
        JLabel label = new JLabel();
        label.setFont(GamePanel.uiFont(12, Font.PLAIN));
        label.setForeground(TEXT_DIM);
        label.setOpaque(true);
        label.setBackground(BG);
        label.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(LINE, 1),
                BorderFactory.createEmptyBorder(4, 10, 4, 10)));
        return label;
    }

    private static JButton button(String text, final Runnable action) {
        final JButton b = new JButton(text);
        b.setFont(GamePanel.uiFont(13, Font.PLAIN));
        b.setForeground(TEXT_MAIN);
        b.setBackground(BTN_BG);
        b.setFocusable(false);
        b.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(LINE, 1),
                BorderFactory.createEmptyBorder(6, 14, 6, 14)));
        b.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
        return b;
    }

    /* ---------------- 快捷键 ---------------- */

    private void bindKeys() {
        JComponent root = getRootPane();

        bind(root, "move-up", KeyEvent.VK_UP, 0, SokobanGame.Dir.UP);
        bind(root, "move-down", KeyEvent.VK_DOWN, 0, SokobanGame.Dir.DOWN);
        bind(root, "move-left", KeyEvent.VK_LEFT, 0, SokobanGame.Dir.LEFT);
        bind(root, "move-right", KeyEvent.VK_RIGHT, 0, SokobanGame.Dir.RIGHT);

        bind(root, "move-up-w", KeyEvent.VK_W, 0, SokobanGame.Dir.UP);
        bind(root, "move-down-s", KeyEvent.VK_S, 0, SokobanGame.Dir.DOWN);
        bind(root, "move-left-a", KeyEvent.VK_A, 0, SokobanGame.Dir.LEFT);
        bind(root, "move-right-d", KeyEvent.VK_D, 0, SokobanGame.Dir.RIGHT);

        bind(root, "move-up-ws", KeyEvent.VK_W, InputEvent.SHIFT_DOWN_MASK, SokobanGame.Dir.UP);
        bind(root, "move-down-ss", KeyEvent.VK_S, InputEvent.SHIFT_DOWN_MASK, SokobanGame.Dir.DOWN);
        bind(root, "move-left-as", KeyEvent.VK_A, InputEvent.SHIFT_DOWN_MASK, SokobanGame.Dir.LEFT);
        bind(root, "move-right-ds", KeyEvent.VK_D, InputEvent.SHIFT_DOWN_MASK, SokobanGame.Dir.RIGHT);

        action(root, "undo", KeyStroke.getKeyStroke(KeyEvent.VK_U, 0), new Runnable() {
            @Override
            public void run() {
                doUndo();
            }
        });
        action(root, "reset", KeyStroke.getKeyStroke(KeyEvent.VK_R, 0), new Runnable() {
            @Override
            public void run() {
                doReset();
            }
        });
        action(root, "hint", KeyStroke.getKeyStroke(KeyEvent.VK_H, 0), new Runnable() {
            @Override
            public void run() {
                doHint();
            }
        });
        action(root, "next-level", KeyStroke.getKeyStroke(KeyEvent.VK_N, 0), new Runnable() {
            @Override
            public void run() {
                doChangeLevel(1);
            }
        });
        action(root, "prev-level", KeyStroke.getKeyStroke(KeyEvent.VK_P, 0), new Runnable() {
            @Override
            public void run() {
                doChangeLevel(-1);
            }
        });
        action(root, "next-enter", KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), new Runnable() {
            @Override
            public void run() {
                doChangeLevel(1);
            }
        });
    }

    private void bind(JComponent root, String name, int keyCode, int modifiers,
                      final SokobanGame.Dir dir) {
        action(root, name, KeyStroke.getKeyStroke(keyCode, modifiers), new Runnable() {
            @Override
            public void run() {
                doMove(dir);
            }
        });
    }

    private static void action(JComponent root, String name, KeyStroke stroke,
                               final Runnable runnable) {
        InputMap im = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = root.getActionMap();
        im.put(stroke, name);
        am.put(name, new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                runnable.run();
            }
        });
    }

    /* ---------------- 操作 ---------------- */

    private void doMove(SokobanGame.Dir dir) {
        cancelHint();
        checkCheatCode(dir);
        if (game.move(dir)) {
            afterAction();
        } else {
            // 没走动也要重绘：玩家朝向可能已经变了
            board.repaint();
        }
    }

    /**
     * 检查玩家刚才按的这一下是不是凑齐了作弊码（上 下 左 右 左 右 上 下）。
     *
     * <p>只有玩家主动按方向键才会走到这里，提示的自动演示不会误触发。</p>
     */
    private void checkCheatCode(SokobanGame.Dir dir) {
        if (!cheat.input(dir)) {
            return;
        }
        if (game.setEndlessSkipUnlocked(true)) {
            updateHud();
            setStatus("★ 作弊生效：无尽模式已解锁跳关，按 N 可直接跳下一层", TEXT_OK);
        } else {
            setStatus("★ 无尽模式跳关已经是开启状态", TEXT_OK);
        }
    }

    private void doUndo() {
        cancelHint();
        if (game.undo()) {
            afterAction();
        } else {
            setStatus("没有可以撤销的步骤", TEXT_DIM);
        }
    }

    private void doReset() {
        cancelHint();
        game.reset();
        winRecorded = false;
        afterAction();
        setStatus("已重来本关", TEXT_DIM);
    }

    private void doChangeLevel(int delta) {
        if (delta > 0 && !game.canAdvance()) {
            setStatus("还没通过本关，不能进入下一关", TEXT_WARN);
            return;
        }
        cancelHint();
        if (game.changeLevel(delta)) {
            winRecorded = false;
            afterAction();
            setStatus("", TEXT_DIM);
        } else {
            setStatus(delta < 0 ? "已经是第一关了" : "没有更多关卡了", TEXT_DIM);
        }
    }

    private void doClearScores() {
        cancelHint();
        save.clearScores();
        updateHud();
        setStatus("已清除最佳步数记录（关卡进度保留）", TEXT_DIM);
    }

    /**
     * 提示：自动演示一条通关操作。
     *
     * <p>如果当前局面仍然停留在解法的路径上，就从当前位置接着演示；
     * 否则先重来再完整演示。</p>
     */
    private void doHint() {
        if (hintTimer != null && hintTimer.isRunning()) {
            cancelHint();
            return;
        }
        if (game.isWon()) {
            setStatus("本关已经通过了，按 N 进入下一关", TEXT_DIM);
            return;
        }

        setStatus("正在计算解法…", TEXT_DIM);
        // 让“正在计算”先画出来，再开始可能耗时的搜索
        statusLabel.paintImmediately(statusLabel.getVisibleRect());

        List<SokobanGame.Dir> solution = solutionFor(game.getLevelIndex());
        if (solution == null || solution.isEmpty()) {
            setStatus("没有找到这一关的解法", TEXT_WARN);
            return;
        }

        int matched = Solver.matchingPrefix(game.getLevel(), solution,
                game.getPlayer(), game.getBoxes());

        List<SokobanGame.Dir> plan;
        String note;
        if (matched < 0) {
            // 玩家已经走到解法之外，先重来再完整演示
            game.reset();
            winRecorded = false;
            board.refresh();
            plan = new ArrayList<SokobanGame.Dir>(solution);
            note = "当前局面已偏离解法，已重来并完整演示";
        } else {
            plan = new ArrayList<SokobanGame.Dir>(
                    solution.subList(matched, solution.size()));
            note = "自动演示中";
        }

        if (plan.isEmpty()) {
            setStatus("已经到达终点状态", TEXT_DIM);
            return;
        }
        startHint(plan);
        setStatus(note + "：共 " + plan.size() + " 步，按 H 或方向键可中断", TEXT_OK);
    }

    /**
     * 取某关的一条完整解法。
     *
     * <p>优先用求解器现算最短解；无尽关卡自带的那条解法是拉箱游走的产物，可能长达上千步，
     * 演示起来太久，只有在算不动的时候才退回使用。</p>
     */
    private List<SokobanGame.Dir> solutionFor(int levelIndex) {
        List<SokobanGame.Dir> cached = solvedCache.get(Integer.valueOf(levelIndex));
        if (cached != null) {
            return cached;
        }
        List<SokobanGame.Dir> optimal = Solver.solve(game.getLevel(), HINT_SOLVER_LIMIT);
        if (optimal != null && !optimal.isEmpty()) {
            solvedCache.put(Integer.valueOf(levelIndex), optimal);
            return optimal;
        }
        return campaign.getKnownSolution(levelIndex);
    }

    private void startHint(List<SokobanGame.Dir> plan) {
        stopHintTimer();
        this.hintPlan = plan;
        this.hintCursor = 0;
        // 无尽模式的解法可能有一两百步，按总时长反推单步间隔，避免演示十几秒
        long perStep = HINT_TARGET_TOTAL_MS / Math.max(1, plan.size());
        int delay = (int) Math.max(HINT_MIN_DELAY_MS, Math.min(HINT_MAX_DELAY_MS, perStep));
        this.hintTimer = new Timer(delay, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                stepHint();
            }
        });
        this.hintTimer.setInitialDelay(0);
        this.hintTimer.start();
    }

    private void stepHint() {
        if (hintPlan == null || hintCursor >= hintPlan.size()) {
            stopHintTimer();
            return;
        }
        SokobanGame.Dir dir = hintPlan.get(hintCursor);
        hintCursor++;
        if (!game.move(dir)) {
            stopHintTimer();
            setStatus("自动演示中断：第 " + hintCursor + " 步走不通", TEXT_WARN);
            return;
        }
        afterAction();
        if (game.isWon()) {
            stopHintTimer();
            setStatus("自动演示完成，已经通关", TEXT_OK);
        }
    }

    /** 用户主动打断自动演示。 */
    private void cancelHint() {
        if (hintTimer != null && hintTimer.isRunning()) {
            stopHintTimer();
            setStatus("已中断自动演示", TEXT_DIM);
        }
    }

    private void stopHintTimer() {
        if (hintTimer != null) {
            hintTimer.stop();
            hintTimer = null;
        }
        hintPlan = null;
        hintCursor = 0;
    }

    /* ---------------- 状态同步 ---------------- */

    private void afterAction() {
        if (game.isWon() && !winRecorded) {
            winRecorded = true;
            save.submit(game.getLevelIndex(), game.getSteps());
        }
        // 通关时 SokobanGame 已经解锁了下一关，这里把它落盘
        save.unlockLevel(game.getMaxUnlockedLevel());
        board.refresh();
    }

    private void updateHud() {
        levelLabel.setText(game.getLevelTitle());
        mapLabel.setText("地图 " + game.getLevel().getWidth()
                + "×" + game.getLevel().getHeight());
        stepsLabel.setText("步数 " + game.getSteps());
        pushesLabel.setText("推动 " + game.getPushes());
        int best = save.getBest(game.getLevelIndex());
        bestLabel.setText("最佳 " + (best < 0 ? "—" : best + " 步"));

        nextButton.setEnabled(game.canAdvance());
        nextButton.setToolTipText(game.canAdvance() ? null : "通过本关后才能进入下一关");

        setTitle("推箱子 · " + game.getLevelTitle());
    }

    private void setStatus(String text, Color color) {
        statusLabel.setText(text == null || text.isEmpty() ? " " : text);
        statusLabel.setForeground(color);
    }
}
