package com.ruoyi.sokoban;

import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.BorderFactory;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.GridBagLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.text.SimpleDateFormat;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
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
    private static final Color TEXT_FAINT = new Color(0x5C, 0x64, 0x8C);
    private static final Color BG_AUTO_SLOT = new Color(0x1E, 0x24, 0x3C);

    private static final String BASE_TITLE = "推箱子 · Sokoban";

    private static final String CARD_MENU = "menu";
    private static final String CARD_ENDLESS = "endless";
    private static final String CARD_GAME = "game";
    private static final String CARD_SLOTS = "slots";

    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("MM-dd HH:mm");

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
    private final SaveManager saves;

    private final JLabel levelLabel = chip();
    private final JLabel mapLabel = chip();
    private final JLabel stepsLabel = chip();
    private final JLabel pushesLabel = chip();
    private final JLabel bestLabel = chip();
    private final JLabel statusLabel = new JLabel(" ", JLabel.CENTER);

    private final JButton hintButton;
    private final JButton nextButton;

    /** 卡片容器：主菜单 / 游戏 / 存读档。 */
    private CardLayout cards;
    private JPanel cardHolder;
    private JButton[] menuButtons;
    private JLabel continueHint;

    private JLabel slotsTitle;
    private JPanel slotsList;
    private JButton slotsBackButton;
    private JButton slotsMenuButton;
    private JPanel slotsBottom;

    /** 存读档界面当前是“存档模式”还是“读档模式”。 */
    private boolean slotsForSaving;

    /** 无尽模式开局界面上的种子输入框与提示。 */
    private JTextField seedField;
    private JLabel endlessStatus;

    /** 当前停在哪个界面（主菜单 / 无尽开局 / 游戏 / 存读档）。 */
    private String currentCard = CARD_MENU;

    /** 存读档界面是从哪个界面点进来的，返回时原路回去。 */
    private String slotsReturnCard = CARD_MENU;

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
        this(Campaign.createDefault(), new SaveData(), new SaveManager());
    }

    /**
     * 使用指定战役与存档创建窗口。
     *
     * @param campaign 关卡来源
     * @param saveData 成绩/进度存档
     */
    public SokobanFrame(Campaign campaign, SaveData saveData) {
        this(campaign, saveData, new SaveManager());
    }

    /**
     * 使用指定战役、成绩存档与存档管理器创建窗口。
     *
     * @param campaign  关卡来源
     * @param saveData  成绩/进度存档
     * @param saveSlots 存档槽管理器
     */
    public SokobanFrame(Campaign campaign, SaveData saveData, SaveManager saveSlots) {
        super("推箱子 · Sokoban");
        this.campaign = campaign;
        this.save = saveData;
        this.saves = saveSlots;
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
        getContentPane().setBackground(BG);
        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(buildCards(), BorderLayout.CENTER);

        bindKeys();
        updateHud();
        updateMenuState();
        showMenu();

        setSize(960, 820);
        setMinimumSize(new Dimension(660, 560));
        setLocationRelativeTo(null);
    }

    /* ---------------- 卡片：主菜单 / 游戏 / 存读档 ---------------- */

    private JComponent buildCards() {
        cards = new CardLayout();
        cardHolder = new JPanel(cards);
        cardHolder.setBackground(BG);
        cardHolder.add(buildMenuCard(), CARD_MENU);
        cardHolder.add(buildEndlessCard(), CARD_ENDLESS);
        cardHolder.add(buildGameCard(), CARD_GAME);
        cardHolder.add(buildSlotsCard(), CARD_SLOTS);
        return cardHolder;
    }

    /** “直接从无尽模式开始”界面：随机种子，或手动指定种子。 */
    private JComponent buildEndlessCard() {
        JPanel outer = new JPanel(new GridBagLayout());
        outer.setBackground(BG);

        JPanel box = new JPanel();
        box.setOpaque(false);
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));

        JLabel title = new JLabel("从无尽模式开始", JLabel.CENTER);
        title.setFont(GamePanel.uiFont(32, Font.BOLD));
        title.setForeground(TEXT_MAIN);
        title.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel desc = new JLabel("跳过内置关卡，直接进入无尽第 1 层", JLabel.CENTER);
        desc.setFont(GamePanel.uiFont(13, Font.PLAIN));
        desc.setForeground(TEXT_DIM);
        desc.setAlignmentX(Component.CENTER_ALIGNMENT);
        desc.setBorder(BorderFactory.createEmptyBorder(4, 0, 26, 0));

        box.add(title);
        box.add(desc);

        box.add(actionButton("随机开始", "随机一个种子，直接开始无尽第 1 层", new Runnable() {
            @Override
            public void run() {
                startEndlessRandom();
            }
        }));
        box.add(Box.createVerticalStrut(22));

        JLabel orLabel = new JLabel("—— 或指定种子 ——", JLabel.CENTER);
        orLabel.setFont(GamePanel.uiFont(13, Font.PLAIN));
        orLabel.setForeground(TEXT_DIM);
        orLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        orLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));
        box.add(orLabel);

        seedField = new JTextField();
        seedField.setFont(GamePanel.uiFont(20, Font.BOLD));
        seedField.setHorizontalAlignment(JTextField.CENTER);
        seedField.setForeground(TEXT_MAIN);
        seedField.setBackground(BTN_BG);
        seedField.setCaretColor(TEXT_MAIN);
        seedField.setToolTipText(SeedCode.hint());
        seedField.setMaximumSize(new Dimension(280, 42));
        seedField.setPreferredSize(new Dimension(280, 42));
        seedField.setAlignmentX(Component.CENTER_ALIGNMENT);
        seedField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(LINE, 1),
                BorderFactory.createEmptyBorder(4, 10, 4, 10)));
        seedField.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                startEndlessFromInput();
            }
        });
        box.add(seedField);
        box.add(Box.createVerticalStrut(10));

        box.add(actionButton("用这个种子开始", SeedCode.hint(), new Runnable() {
            @Override
            public void run() {
                startEndlessFromInput();
            }
        }));
        box.add(Box.createVerticalStrut(10));

        JLabel formatHint = new JLabel(SeedCode.hint(), JLabel.CENTER);
        formatHint.setFont(GamePanel.uiFont(11, Font.PLAIN));
        formatHint.setForeground(TEXT_FAINT);
        formatHint.setAlignmentX(Component.CENTER_ALIGNMENT);
        box.add(formatHint);

        endlessStatus = new JLabel(" ", JLabel.CENTER);
        endlessStatus.setFont(GamePanel.uiFont(12, Font.BOLD));
        endlessStatus.setForeground(TEXT_WARN);
        endlessStatus.setAlignmentX(Component.CENTER_ALIGNMENT);
        endlessStatus.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0));
        box.add(endlessStatus);

        box.add(actionButton("返回主菜单 (Esc)", "回到主菜单", new Runnable() {
            @Override
            public void run() {
                showMenu();
            }
        }));

        outer.add(box);
        return outer;
    }

    private JComponent buildMenuCard() {
        JPanel outer = new JPanel(new GridBagLayout());
        outer.setBackground(BG);

        JPanel box = new JPanel();
        box.setOpaque(false);
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));

        JLabel title = new JLabel("推箱子", JLabel.CENTER);
        title.setFont(GamePanel.uiFont(48, Font.BOLD));
        title.setForeground(TEXT_MAIN);
        title.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel subtitle = new JLabel("SOKOBAN", JLabel.CENTER);
        subtitle.setFont(GamePanel.uiFont(13, Font.PLAIN));
        subtitle.setForeground(TEXT_DIM);
        subtitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        subtitle.setBorder(BorderFactory.createEmptyBorder(0, 0, 34, 0));

        box.add(title);
        box.add(subtitle);

        menuButtons = new JButton[5];
        menuButtons[0] = menuButton("新的开始", "随机生成一套全新的无尽地图，从第 1 关开始", 0);
        menuButtons[1] = menuButton("继续游戏", "从最近的一次存档接着玩", 1);
        menuButtons[2] = menuButton("无尽模式", "直接开始无尽第 1 层：随机种子，或自己指定一个种子", 2);
        menuButtons[3] = menuButton("读档", "打开存档列表，选择要读取的进度", 3);
        menuButtons[4] = menuButton("退出", "关闭游戏", 4);
        for (JButton menuButton : menuButtons) {
            box.add(menuButton);
            box.add(Box.createVerticalStrut(12));
        }

        continueHint = new JLabel(" ", JLabel.CENTER);
        continueHint.setFont(GamePanel.uiFont(12, Font.PLAIN));
        continueHint.setForeground(TEXT_DIM);
        continueHint.setAlignmentX(Component.CENTER_ALIGNMENT);
        continueHint.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        box.add(continueHint);

        outer.add(box);
        return outer;
    }

    private JButton menuButton(String text, String tooltip, final int action) {
        return actionButton(text, tooltip, new Runnable() {
            @Override
            public void run() {
                handleMenuAction(action);
            }
        });
    }

    /** 大号按钮，主菜单与无尽模式界面共用。 */
    private JButton actionButton(String text, String tooltip, final Runnable action) {
        JButton b = new JButton(text);
        b.setFont(GamePanel.uiFont(17, Font.PLAIN));
        b.setForeground(TEXT_MAIN);
        b.setBackground(BTN_BG);
        b.setFocusable(false);
        if (tooltip != null && !tooltip.isEmpty()) {
            b.setToolTipText(tooltip);
        }
        b.setAlignmentX(Component.CENTER_ALIGNMENT);
        b.setMaximumSize(new Dimension(280, 46));
        b.setPreferredSize(new Dimension(280, 46));
        b.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(LINE, 1),
                BorderFactory.createEmptyBorder(8, 18, 8, 18)));
        b.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
        return b;
    }

    private JComponent buildGameCard() {
        JPanel card = new JPanel(new BorderLayout());
        card.setBackground(BG);
        card.add(buildHeader(), BorderLayout.NORTH);
        card.add(board, BorderLayout.CENTER);
        card.add(buildFooter(), BorderLayout.SOUTH);
        return card;
    }

    private JComponent buildSlotsCard() {
        JPanel card = new JPanel(new BorderLayout());
        card.setBackground(BG);
        card.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));

        slotsTitle = new JLabel("读档", JLabel.CENTER);
        slotsTitle.setFont(GamePanel.uiFont(22, Font.BOLD));
        slotsTitle.setForeground(TEXT_MAIN);
        slotsTitle.setBorder(BorderFactory.createEmptyBorder(0, 0, 12, 0));

        slotsList = new JPanel();
        slotsList.setBackground(BG);
        slotsList.setLayout(new BoxLayout(slotsList, BoxLayout.Y_AXIS));

        JScrollPane scroll = new JScrollPane(slotsList);
        scroll.setBorder(BorderFactory.createLineBorder(LINE, 1));
        scroll.getViewport().setBackground(BG);
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        slotsBackButton = button("返回", new Runnable() {
            @Override
            public void run() {
                backFromSlots();
            }
        });
        slotsMenuButton = button("主菜单", new Runnable() {
            @Override
            public void run() {
                showMenu();
            }
        });
        slotsBottom = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        slotsBottom.setOpaque(false);
        slotsBottom.add(slotsBackButton);
        slotsBottom.add(slotsMenuButton);

        card.add(slotsTitle, BorderLayout.NORTH);
        card.add(scroll, BorderLayout.CENTER);
        card.add(slotsBottom, BorderLayout.SOUTH);
        return card;
    }

    /* ---------------- 主菜单行为 ---------------- */

    private void handleMenuAction(int action) {
        switch (action) {
            case 0:
                startNewGame();
                break;
            case 1:
                continueLatest();
                break;
            case 2:
                showEndlessCard();
                break;
            case 3:
                showSlots(false);
                break;
            default:
                exitGame();
                break;
        }
    }

    /* ---------------- 无尽模式开局 ---------------- */

    private void showEndlessCard() {
        cancelHint();
        endlessStatus.setText(" ");
        seedField.setText("");
        setTitle(BASE_TITLE);
        showCard(CARD_ENDLESS);
        seedField.requestFocusInWindow();
    }

    /** 随机种子直接开一局无尽模式。 */
    private void startEndlessRandom() {
        startEndless(SeedCode.randomSeed());
    }

    /** 用输入框里的种子开一局无尽模式。 */
    private void startEndlessFromInput() {
        Long seed = SeedCode.parse(seedField.getText());
        if (seed == null) {
            endlessStatus.setText(SeedCode.hint());
            return;
        }
        seedField.setText(SeedCode.format(seed.longValue()));
        startEndless(seed.longValue());
    }

    /**
     * 用指定种子从无尽第 1 层开始。
     *
     * @param seed 规整前的种子
     */
    private void startEndless(long seed) {
        campaign.reseed(seed);
        game.setMaxUnlockedLevel(0);
        game.loadLevel(campaign.getBuiltInCount());
        winRecorded = false;
        solvedCache.clear();
        updateHud();
        showGame();
        setStatus("无尽模式开始 · 种子 " + campaign.getSeedCode(), TEXT_OK);
    }

    /* ---------------- 查看 / 复制种子 ---------------- */

    private void showSeedDialog() {
        cancelHint();
        final String code = campaign.getSeedCode();

        final JDialog dialog = new JDialog(this, "无尽模式种子", true);
        dialog.getContentPane().setBackground(BG);
        dialog.setLayout(new BorderLayout());

        JPanel panel = new JPanel();
        panel.setBackground(BG);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(18, 26, 16, 26));

        JLabel caption = new JLabel("当前无尽模式的种子", JLabel.CENTER);
        caption.setFont(GamePanel.uiFont(13, Font.PLAIN));
        caption.setForeground(TEXT_DIM);
        caption.setAlignmentX(Component.CENTER_ALIGNMENT);

        final JTextField field = new JTextField(code);
        field.setEditable(false);
        field.setFont(GamePanel.uiFont(24, Font.BOLD));
        field.setHorizontalAlignment(JTextField.CENTER);
        field.setForeground(TEXT_MAIN);
        field.setBackground(BTN_BG);
        field.setCaretColor(TEXT_MAIN);
        field.setMaximumSize(new Dimension(280, 48));
        field.setPreferredSize(new Dimension(280, 48));
        field.setAlignmentX(Component.CENTER_ALIGNMENT);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(LINE, 1),
                BorderFactory.createEmptyBorder(6, 12, 6, 12)));
        field.selectAll();

        JLabel hint = new JLabel("相同的种子会生成完全相同的无尽关卡", JLabel.CENTER);
        hint.setFont(GamePanel.uiFont(12, Font.PLAIN));
        hint.setForeground(TEXT_DIM);
        hint.setAlignmentX(Component.CENTER_ALIGNMENT);
        hint.setBorder(BorderFactory.createEmptyBorder(10, 0, 14, 0));

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        actions.setOpaque(false);
        actions.add(button("复制种子", new Runnable() {
            @Override
            public void run() {
                boolean copied = copyToClipboard(code);
                setStatus(copied ? "种子已复制到剪贴板：" + code
                                : "复制失败，请手动选中复制：" + code,
                        copied ? TEXT_OK : TEXT_WARN);
                dialog.dispose();
            }
        }));
        actions.add(button("关闭", new Runnable() {
            @Override
            public void run() {
                dialog.dispose();
            }
        }));

        panel.add(caption);
        panel.add(Box.createVerticalStrut(10));
        panel.add(field);
        panel.add(hint);
        panel.add(actions);

        dialog.add(panel, BorderLayout.CENTER);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private static boolean copyToClipboard(String text) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(text), null);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 新的开始：换一套随机种子，从第 1 关重新开始。 */
    private void startNewGame() {
        campaign.reseed(SeedCode.randomSeed());
        game.setMaxUnlockedLevel(0);
        game.loadLevel(0);
        winRecorded = false;
        solvedCache.clear();
        updateHud();
        showGame();
        setStatus("新的开始：从第 1 关出发", TEXT_OK);
    }

    /** 继续游戏：读取最近的一次存档。 */
    private void continueLatest() {
        SaveSlot latest = saves.latest();
        if (latest == null) {
            setStatus("还没有任何存档", TEXT_WARN);
            return;
        }
        if (loadSlot(latest.getIndex())) {
            setStatus("已从存档 " + labels(latest.getIndex()) + " 继续", TEXT_OK);
        }
    }

    /** 退出游戏。 */
    private void exitGame() {
        dispose();
        System.exit(0);
    }

    private void showMenu() {
        cancelHint();
        updateMenuState();
        setTitle(BASE_TITLE);
        showCard(CARD_MENU);
    }

    private void showGame() {
        showCard(CARD_GAME);
    }

    /** 切换卡片，并记住当前停在哪个界面（返回时要用）。 */
    private void showCard(String name) {
        currentCard = name;
        cards.show(cardHolder, name);
    }

    /**
     * 打开存读档列表。
     *
     * <p>会记住是从“游戏”还是“主菜单”点进来的，返回时原路回去——
     * 在关卡里存完档可以直接回到刚才那一局继续玩。</p>
     *
     * @param forSaving {@code true} 表示“存档”模式，{@code false} 表示“读档”模式
     */
    private void showSlots(boolean forSaving) {
        cancelHint();
        this.slotsForSaving = forSaving;
        this.slotsReturnCard = currentCard;
        boolean fromGame = CARD_GAME.equals(slotsReturnCard);
        slotsTitle.setText(forSaving ? "存档 · 选择一个槽位写入" : "读档 · 选择要读取的进度");
        slotsBackButton.setText(fromGame ? "返回游戏 (Esc)" : "返回主菜单 (Esc)");
        slotsMenuButton.setVisible(fromGame);
        slotsBottom.revalidate();
        setTitle(BASE_TITLE);
        refreshSlots();
        showCard(CARD_SLOTS);
    }

    /**
     * 从存读档界面返回：从哪来的回哪去。
     *
     * <p>这一点很重要——如果只能回主菜单，那么在关卡中途进来存档之后，
     * 就只剩“读档”一条路能回到游戏，而存档里的进度比当前落后。</p>
     */
    private void backFromSlots() {
        cancelHint();
        if (CARD_GAME.equals(slotsReturnCard)) {
            showGame();
        } else {
            showMenu();
        }
    }

    /** Esc 的行为随当前界面而变：存读档界面原路返回，游戏界面回主菜单。 */
    private void handleEscape() {
        if (CARD_SLOTS.equals(currentCard)) {
            backFromSlots();
        } else if (!CARD_MENU.equals(currentCard)) {
            showMenu();
        }
    }

    private void refreshSlots() {
        slotsList.removeAll();
        for (int slot = 0; slot < SaveManager.SLOT_COUNT; slot++) {
            slotsList.add(buildSlotRow(slot, saves.read(slot)));
        }
        slotsList.add(Box.createVerticalStrut(8));
        slotsList.revalidate();
        slotsList.repaint();
    }

    private JComponent buildSlotRow(final int slot, final SaveSlot data) {
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setBackground(slot == SaveManager.AUTO_SLOT ? BG_AUTO_SLOT : BG);
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, LINE),
                BorderFactory.createEmptyBorder(10, 14, 10, 14)));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 62));

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));

        JLabel name = new JLabel(labels(slot));
        name.setFont(GamePanel.uiFont(14, Font.BOLD));
        name.setForeground(TEXT_MAIN);

        JLabel detail = new JLabel(data.exists()
                ? describe(data)
                : "空存档");
        detail.setFont(GamePanel.uiFont(12, Font.PLAIN));
        detail.setForeground(data.exists() ? TEXT_DIM : TEXT_FAINT);

        text.add(name);
        text.add(detail);
        row.add(text, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        if (slotsForSaving) {
            actions.add(button(data.exists() ? "覆盖保存" : "存入此档", new Runnable() {
                @Override
                public void run() {
                    saveToSlot(slot);
                }
            }));
        } else if (data.exists()) {
            actions.add(button("读取", new Runnable() {
                @Override
                public void run() {
                    if (loadSlot(slot)) {
                        setStatus("已读取存档 " + labels(slot), TEXT_OK);
                    }
                }
            }));
        }
        if (data.exists()) {
            actions.add(button("删除", new Runnable() {
                @Override
                public void run() {
                    saves.delete(slot);
                    refreshSlots();
                    updateMenuState();
                }
            }));
        }
        row.add(actions, BorderLayout.EAST);
        return row;
    }

    private static String labels(int slot) {
        return slot == SaveManager.AUTO_SLOT ? "自动存档" : "存档 " + slot;
    }

    /** 一行摘要：关卡进度 + 步数 + 保存时间。 */
    private String describe(SaveSlot data) {
        String progress = campaign.getTitle(data.getLevelIndex())
                + "　·　" + data.getSteps() + " 步";
        if (data.getSavedAt() <= 0L) {
            return progress;
        }
        return progress + "　·　" + TIME_FORMAT.format(new java.util.Date(data.getSavedAt()));
    }

    /* ---------------- 存 / 读 ---------------- */

    /** 把当前局面写进指定槽位。 */
    private void saveToSlot(int slot) {
        SaveSlot data = SaveSlot.of(slot,
                game.getLevelIndex(), game.getPlayer(), game.getBoxes(),
                game.getSteps(), game.getPushes(),
                game.getMaxUnlockedLevel(), campaign.getSeedBase(),
                System.currentTimeMillis());
        if (saves.write(slot, data)) {
            refreshSlots();
            updateMenuState();
            setStatus("已存入 " + labels(slot) + "：" + campaign.getTitle(data.getLevelIndex()),
                    TEXT_OK);
        } else {
            setStatus("存档失败：写不进存档目录", TEXT_WARN);
        }
    }

    /**
     * 读取指定槽位。
     *
     * @return 读取成功返回 {@code true}
     */
    private boolean loadSlot(int slot) {
        SaveSlot data = saves.read(slot);
        if (!data.exists()) {
            setStatus("这个槽位还没有存档", TEXT_WARN);
            return false;
        }
        // 先切到存档当时的随机种子，无尽地图才会和存的那一刻完全一致
        campaign.reseed(data.getSeedBase());
        solvedCache.clear();
        game.setMaxUnlockedLevel(data.getUnlocked());
        if (!game.restore(data.getLevelIndex(), data.getPlayer(), data.getBoxes(),
                data.getSteps(), data.getPushes())) {
            setStatus("存档内容有问题，无法读取", TEXT_WARN);
            return false;
        }
        winRecorded = game.isWon();
        updateHud();
        showGame();
        return true;
    }

    /** 每通关一关自动存一次，存的是“下一关的开头”，方便直接继续。 */
    private void autoSaveAfterWin() {
        int next = game.getLevelIndex() + 1;
        Level nextLevel = campaign.getLevel(next);
        SaveSlot data = SaveSlot.of(SaveManager.AUTO_SLOT,
                next, nextLevel.getPlayerStart(), nextLevel.getBoxStarts(),
                0, 0, Math.max(game.getMaxUnlockedLevel(), next),
                campaign.getSeedBase(), System.currentTimeMillis());
        if (saves.write(SaveManager.AUTO_SLOT, data)) {
            updateMenuState();
            if (slotsForSaving || cards != null) {
                refreshSlotsIfVisible();
            }
        }
    }

    private void refreshSlotsIfVisible() {
        if (slotsList != null && slotsList.isShowing()) {
            refreshSlots();
        }
    }

    /** 刷新主菜单上“继续游戏”的提示。 */
    private void updateMenuState() {
        if (menuButtons == null) {
            return;
        }
        SaveSlot latest = saves.latest();
        boolean hasSave = latest != null;
        menuButtons[1].setEnabled(hasSave);
        menuButtons[1].setToolTipText(hasSave
                ? "继续：" + describe(latest)
                : "还没有任何存档");
        continueHint.setText(hasSave ? "最近进度：" + describe(latest) : "还没有任何存档");
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

        JPanel systemButtons = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 2));
        systemButtons.setOpaque(false);
        systemButtons.add(button("保存 (S)", new Runnable() {
            @Override
            public void run() {
                showSlots(true);
            }
        }));
        systemButtons.add(button("读档 (L)", new Runnable() {
            @Override
            public void run() {
                showSlots(false);
            }
        }));
        systemButtons.add(button("种子", new Runnable() {
            @Override
            public void run() {
                showSeedDialog();
            }
        }));
        systemButtons.add(button("返回主菜单 (Esc)", new Runnable() {
            @Override
            public void run() {
                showMenu();
            }
        }));
        systemButtons.add(button("清除成绩", new Runnable() {
            @Override
            public void run() {
                doClearScores();
            }
        }));

        statusLabel.setFont(GamePanel.uiFont(12, Font.PLAIN));
        statusLabel.setForeground(TEXT_DIM);

        JLabel hint = new JLabel(
                "方向键 / WASD 移动 · U 撤销 · R 重来 · H 提示 · N 下一关 · P 上一关"
                        + " · S 存档 · L 读档", JLabel.CENTER);
        hint.setFont(GamePanel.uiFont(11, Font.PLAIN));
        hint.setForeground(TEXT_DIM);

        footer.add(buttons, BorderLayout.NORTH);
        footer.add(systemButtons, BorderLayout.CENTER);
        footer.add(statusLabel, BorderLayout.SOUTH);
        footer.add(hint, BorderLayout.PAGE_END);
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
        action(root, "save-slots", KeyStroke.getKeyStroke(KeyEvent.VK_S, 0), new Runnable() {
            @Override
            public void run() {
                showSlots(true);
            }
        });
        action(root, "load-slots", KeyStroke.getKeyStroke(KeyEvent.VK_L, 0), new Runnable() {
            @Override
            public void run() {
                showSlots(false);
            }
        });
        action(root, "escape", KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                new Runnable() {
                    @Override
                    public void run() {
                        handleEscape();
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
            // 每通关一关自动存档（存的是下一关的开头）
            autoSaveAfterWin();
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
