package com.ruoyi.sokoban;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * 推箱子的游戏状态与规则。
 *
 * <p>本类不依赖任何 Swing / AWT 类型，可以脱离界面单独使用和测试。</p>
 *
 * <p>术语：一维下标 {@code index = y * width + x}。</p>
 *
 * <h3>关卡解锁</h3>
 * 只有下标小于等于 {@link #getMaxUnlockedLevel()} 的关卡可以进入。通关后会自动把
 * 下一关解锁，因此“没通过当前关卡就不能进入下一关”。进度本身由外部（{@link SaveData}）
 * 负责持久化，本类只保存内存状态。
 */
public final class SokobanGame {

    /** 移动方向。 */
    public enum Dir {
        /** 上 */ UP(0, -1),
        /** 下 */ DOWN(0, 1),
        /** 左 */ LEFT(-1, 0),
        /** 右 */ RIGHT(1, 0);

        /** 列增量。 */
        public final int dx;
        /** 行增量。 */
        public final int dy;

        Dir(int dx, int dy) {
            this.dx = dx;
            this.dy = dy;
        }

        /**
         * 反方向。
         *
         * @return 相反的方向
         */
        public Dir opposite() {
            switch (this) {
                case UP:
                    return DOWN;
                case DOWN:
                    return UP;
                case LEFT:
                    return RIGHT;
                default:
                    return LEFT;
            }
        }
    }

    /** 撤销用的一步快照。 */
    private static final class Snapshot {
        private final int player;
        private final int[] boxes;
        private final int steps;
        private final int pushes;

        Snapshot(int player, int[] boxes, int steps, int pushes) {
            this.player = player;
            this.boxes = boxes;
            this.steps = steps;
            this.pushes = pushes;
        }
    }

    /** 撤销历史的上限，防止内存无限增长。 */
    private static final int MAX_HISTORY = 3000;

    /** 作弊开启后允许跳到的最远无尽层数（防止一路按到天荒地老）。 */
    private static final int CHEAT_MAX_ENDLESS_LEVEL = 999;

    private final Campaign campaign;
    private final Deque<Snapshot> history = new ArrayDeque<Snapshot>();

    private int levelIndex;
    private Level level;
    private int player;
    private int[] boxes;
    private int steps;
    private int pushes;
    private boolean won;
    private Dir facing = Dir.DOWN;

    /** 已解锁的最高关卡下标。 */
    private int maxUnlockedLevel;

    /** 作弊开关：打开后无尽模式可以随意跳关。 */
    private boolean endlessSkipUnlocked;

    /**
     * 创建一局游戏并载入第 1 关。
     *
     * @param campaign 关卡来源
     */
    public SokobanGame(Campaign campaign) {
        if (campaign == null) {
            throw new IllegalArgumentException("campaign 不能为 null");
        }
        this.campaign = campaign;
        loadLevel(0);
    }

    /**
     * 用一组固定关卡创建游戏（没有无尽模式，便于测试）。
     *
     * @param levels 关卡列表
     */
    public SokobanGame(List<Level> levels) {
        this(new Campaign(levels, null, 0L));
    }

    /* ---------------- 关卡与解锁 ---------------- */

    /**
     * 载入指定关卡，并清空进度与撤销历史。
     *
     * <p>关卡未解锁时拒绝载入。</p>
     *
     * @param index 关卡下标
     * @return 载入成功返回 {@code true}
     */
    public boolean loadLevel(int index) {
        if (!canEnter(index)) {
            return false;
        }
        this.levelIndex = index;
        this.level = campaign.getLevel(index);
        this.player = level.getPlayerStart();
        this.boxes = level.getBoxStarts();
        this.steps = 0;
        this.pushes = 0;
        this.won = false;
        this.facing = Dir.DOWN;
        this.history.clear();
        return true;
    }

    /** 重玩当前关卡。 */
    public void reset() {
        loadLevel(levelIndex);
    }

    /**
     * 从存档恢复局面：直接摆好玩家和每个箱子的位置，而不是回到关卡开头。
     *
     * <p>所有位置都会先校验；只要有一处不合法就整体拒绝，不做任何改动。</p>
     *
     * @param index  关卡下标
     * @param player 玩家位置（一维下标）
     * @param boxes  箱子位置，顺序必须与 {@link Level#getBoxStarts()} 一致
     * @param steps  已走步数
     * @param pushes 已推箱次数
     * @return 恢复成功返回 {@code true}
     */
    public boolean restore(int index, int player, int[] boxes, int steps, int pushes) {
        if (index < 0 || boxes == null) {
            return false;
        }
        Level target = campaign.getLevel(index);
        if (boxes.length != target.getBoxCount()) {
            return false;
        }
        int cells = target.getWidth() * target.getHeight();
        if (player < 0 || player >= cells || target.isWallAt(player)) {
            return false;
        }
        boolean[] occupied = new boolean[cells];
        for (int box : boxes) {
            if (box < 0 || box >= cells || target.isWallAt(box) || occupied[box]) {
                return false;
            }
            occupied[box] = true;
        }
        if (occupied[player]) {
            return false;
        }

        this.levelIndex = index;
        this.level = target;
        this.player = player;
        this.boxes = boxes.clone();
        this.steps = Math.max(0, steps);
        this.pushes = Math.max(0, pushes);
        this.won = isSolved();
        this.facing = Dir.DOWN;
        this.history.clear();
        this.maxUnlockedLevel = Math.max(this.maxUnlockedLevel, index);
        return true;
    }

    /**
     * 切换关卡。
     *
     * <p>往后退永远允许（只要已解锁）；<b>往前进必须先把当前这关通关</b>，
     * 这样无尽模式下就不可能靠“已解锁范围”一路跳关。</p>
     *
     * @param delta 相对偏移，例如 -1 表示上一关
     * @return 关卡确实发生变化返回 {@code true}
     */
    public boolean changeLevel(int delta) {
        int next = levelIndex + delta;
        if (next < 0 || !canEnter(next)) {
            return false;
        }
        if (delta > 0 && !canAdvance()) {
            return false;
        }
        return loadLevel(next);
    }

    /**
     * 该下标是否已经解锁。
     *
     * <p>前 10 关（含紧接其后的无尽第 1 层）完全自由进入，
     * “通过本关才能进下一关”的限制只在无尽模式内部生效。</p>
     *
     * @param index 关卡下标
     * @return 可以进入返回 {@code true}
     */
    public boolean canEnter(int index) {
        if (index < 0) {
            return false;
        }
        if (index <= campaign.getBuiltInCount()) {
            return true;
        }
        if (endlessSkipUnlocked) {
            // 作弊开启：无尽模式随便进
            return index <= campaign.getBuiltInCount() + CHEAT_MAX_ENDLESS_LEVEL;
        }
        return index <= maxUnlockedLevel;
    }

    /**
     * 判断能否进入下一关。
     *
     * <p>前 10 关不受限制（第 10 关的下一关就是无尽第 1 层）。
     * <b>从无尽第 1 层起，必须先把当前这一层打通才能进入下一层。</b>
     * 注意这里看的是“这一次有没有通关”，而不是“以前有没有通关过”，
     * 否则带着历史进度进来就能一路往前跳。</p>
     *
     * @return 可以进入下一关返回 {@code true}
     */
    public boolean canAdvance() {
        if (levelIndex < campaign.getBuiltInCount()) {
            return true;
        }
        if (endlessSkipUnlocked) {
            return true;
        }
        return won;
    }

    /**
     * 打开“无尽模式跳关”作弊。
     *
     * <p>开启后无尽模式不再要求“通过本关才能进下一关”，可以一路往前跳。</p>
     *
     * @param unlocked 是否开启
     * @return 本次调用确实改变了状态返回 {@code true}
     */
    public boolean setEndlessSkipUnlocked(boolean unlocked) {
        if (this.endlessSkipUnlocked == unlocked) {
            return false;
        }
        this.endlessSkipUnlocked = unlocked;
        return true;
    }

    /** @return 是否已经开启了“无尽模式跳关”作弊。 */
    public boolean isEndlessSkipUnlocked() {
        return endlessSkipUnlocked;
    }

    /** @return 已解锁的最高关卡下标。 */
    public int getMaxUnlockedLevel() {
        return maxUnlockedLevel;
    }

    /**
     * 设置解锁进度（启动时从存档恢复用）。
     *
     * @param levelIndex 最高可进入下标，负数按 0 处理
     */
    public void setMaxUnlockedLevel(int levelIndex) {
        this.maxUnlockedLevel = Math.max(0, levelIndex);
    }

    /* ---------------- 游戏规则 ---------------- */

    /**
     * 朝指定方向走一步（如果目标格是箱子，则尝试推箱）。
     *
     * <p>无论是否走动成功，玩家朝向都会更新，以便界面绘制。</p>
     *
     * @param dir 方向
     * @return 真正发生了移动返回 {@code true}
     */
    public boolean move(Dir dir) {
        if (dir == null) {
            return false;
        }
        facing = dir;
        if (won) {
            return false;
        }

        int w = level.getWidth();
        int px = player % w;
        int py = player / w;
        int nx = px + dir.dx;
        int ny = py + dir.dy;
        if (level.isWall(nx, ny)) {
            return false;
        }

        int target = ny * w + nx;
        int boxIndex = indexOfBox(target);

        if (boxIndex >= 0) {
            int bx = nx + dir.dx;
            int by = ny + dir.dy;
            if (level.isWall(bx, by)) {
                return false;
            }
            int beyond = by * w + bx;
            if (indexOfBox(beyond) >= 0) {
                return false;
            }
            record();
            boxes[boxIndex] = beyond;
            pushes++;
        } else {
            record();
        }

        player = target;
        steps++;
        if (isSolved()) {
            won = true;
            // 过关即解锁下一关：这是“不通过当前关卡不能进入下一关”的唯一放行途径
            maxUnlockedLevel = Math.max(maxUnlockedLevel, levelIndex + 1);
        }
        return true;
    }

    /**
     * 撤销一步。
     *
     * @return 确实撤销了返回 {@code true}
     */
    public boolean undo() {
        if (won || history.isEmpty()) {
            return false;
        }
        Snapshot snapshot = history.pop();
        this.player = snapshot.player;
        this.boxes = snapshot.boxes;
        this.steps = snapshot.steps;
        this.pushes = snapshot.pushes;
        return true;
    }

    /** @return 还能撤销多少步。 */
    public int getUndoCount() {
        return history.size();
    }

    private void record() {
        if (history.size() >= MAX_HISTORY) {
            history.removeLast();
        }
        history.push(new Snapshot(player, boxes.clone(), steps, pushes));
    }

    private int indexOfBox(int index) {
        for (int i = 0; i < boxes.length; i++) {
            if (boxes[i] == index) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 判断是否所有箱子都在目标点上。
     *
     * <p>无尽模式启用了“箱子与目标点一一对应”，此时必须每个箱子都在自己的目标点上。</p>
     *
     * @return 全部归位返回 {@code true}
     */
    public boolean isSolved() {
        return level.isSolvedBy(boxes);
    }

    /**
     * 判断当前局面是不是已经不可能通关了。
     *
     * <p>只做<b>可靠</b>的判定：报死局就一定真的通关无望，绝不误报，
     * 因此可能漏掉一些更隐蔽的死局，但玩家不会被打扰到。</p>
     *
     * <p>三条判据：</p>
     * <ol>
     *   <li><b>贴墙滑轨</b>：箱子上下（或左右）只要有一侧是墙，竖直（水平）方向就再也推不动了——
     *       往墙那边推，箱子进不去；反方向推，玩家得站进墙里。于是它只能在本行（本列）里挪，
     *       一旦目标点不在这条线上就永远到不了。</li>
     *   <li><b>彻底卡死</b>：箱子上下左右同时被挡，一步也动不了。</li>
     *   <li><b>全局僵持</b>：所有箱子都推不动了，局面不可能再发生变化。</li>
     * </ol>
     *
     * @return 确认死局返回 {@code true}
     */
    public boolean isDeadlocked() {
        if (isSolved()) {
            return false;
        }
        int width = level.getWidth();
        for (int i = 0; i < boxes.length; i++) {
            int box = boxes[i];
            if (level.isBoxPlaced(i, box)) {
                continue;
            }
            int x = box % width;
            int y = box / width;
            boolean up = level.isWall(x, y - 1);
            boolean down = level.isWall(x, y + 1);
            boolean left = level.isWall(x - 1, y);
            boolean right = level.isWall(x + 1, y);

            // 封在一行（一列）里：只能在这条线上挪，目标点不在线上就没救了
            if (confinedToRow(x, y) && !targetInRow(i, y)) {
                return true;
            }
            if (confinedToColumn(x, y) && !targetInColumn(i, x)) {
                return true;
            }
            // 四方向全被挡：一步也动不了
            if ((up || down) && (left || right)) {
                return true;
            }
        }
        // 全局僵持：一个箱子都推不动了
        return !anyPushPossible();
    }

    /**
     * 箱子是否被“封”在这一行里。
     *
     * <p>箱子要竖直移动，必须在某个格子上做到“上方和下方都是空地”（往上推要站在下面，
     * 往下推要站在上面）。所以只要它所在的那段连续空地里，每一格都至少有一侧贴着墙，
     * 它就永远推不动上下，只能在这一行里左右挪。</p>
     *
     * <p>注意不能只看箱子当前这一格——它可能横着挪到同一行没有墙的位置再上下推。</p>
     */
    private boolean confinedToRow(int x, int y) {
        int left = x;
        while (!level.isWall(left - 1, y)) {
            left--;
        }
        int right = x;
        while (!level.isWall(right + 1, y)) {
            right++;
        }
        for (int cx = left; cx <= right; cx++) {
            if (!level.isWall(cx, y - 1) && !level.isWall(cx, y + 1)) {
                return false;
            }
        }
        return true;
    }

    /** 箱子是否被“封”在这一列里（判据与 {@link #confinedToRow} 对称）。 */
    private boolean confinedToColumn(int x, int y) {
        int up = y;
        while (!level.isWall(x, up - 1)) {
            up--;
        }
        int down = y;
        while (!level.isWall(x, down + 1)) {
            down++;
        }
        for (int cy = up; cy <= down; cy++) {
            if (!level.isWall(x - 1, cy) && !level.isWall(x + 1, cy)) {
                return false;
            }
        }
        return true;
    }

    /** 第 i 个箱子的目标点是否在指定行上（未配对时只要该行有任意目标点即可）。 */
    private boolean targetInRow(int boxIndex, int row) {
        int width = level.getWidth();
        if (level.isPaired()) {
            return level.getBoxTarget(boxIndex) / width == row;
        }
        for (int x = 0; x < width; x++) {
            if (level.isGoal(row * width + x)) {
                return true;
            }
        }
        return false;
    }

    /** 第 i 个箱子的目标点是否在指定列上（未配对时只要该列有任意目标点即可）。 */
    private boolean targetInColumn(int boxIndex, int column) {
        int width = level.getWidth();
        int height = level.getHeight();
        if (level.isPaired()) {
            return level.getBoxTarget(boxIndex) % width == column;
        }
        for (int y = 0; y < height; y++) {
            if (level.isGoal(y * width + column)) {
                return true;
            }
        }
        return false;
    }

    /** 是否还存在至少一个合法推箱动作。 */
    private boolean anyPushPossible() {
        int width = level.getWidth();
        boolean[] reachable = reachableCells(player);
        for (int box : boxes) {
            int x = box % width;
            int y = box / width;
            for (Dir dir : Dir.values()) {
                int standX = x - dir.dx;
                int standY = y - dir.dy;
                int toX = x + dir.dx;
                int toY = y + dir.dy;
                if (level.isWall(standX, standY) || level.isWall(toX, toY)) {
                    continue;
                }
                int stand = standY * width + standX;
                if (stand < 0 || stand >= reachable.length || !reachable[stand]) {
                    continue;
                }
                if (indexOfBox(toY * width + toX) >= 0) {
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    /** 玩家在不推箱子的前提下能走到的格子。 */
    private boolean[] reachableCells(int from) {
        int width = level.getWidth();
        int height = level.getHeight();
        boolean[] seen = new boolean[width * height];
        Deque<Integer> queue = new ArrayDeque<Integer>();
        seen[from] = true;
        queue.add(Integer.valueOf(from));
        while (!queue.isEmpty()) {
            int current = queue.poll().intValue();
            int cx = current % width;
            int cy = current / width;
            for (Dir dir : Dir.values()) {
                int nx = cx + dir.dx;
                int ny = cy + dir.dy;
                if (level.isWall(nx, ny)) {
                    continue;
                }
                int next = ny * width + nx;
                if (seen[next] || indexOfBox(next) >= 0) {
                    continue;
                }
                seen[next] = true;
                queue.add(Integer.valueOf(next));
            }
        }
        return seen;
    }

    /* ---------------- 状态访问 ---------------- */

    /** @return 当前关卡。 */
    public Level getLevel() {
        return level;
    }

    /** @return 当前关卡下标（从 0 开始）。 */
    public int getLevelIndex() {
        return levelIndex;
    }

    /** @return 内置关卡数量（下标大于等于它的都是无尽模式）。 */
    public int getBuiltInCount() {
        return campaign.getBuiltInCount();
    }

    /** @return 当前是否处于无尽模式。 */
    public boolean isEndless() {
        return campaign.isEndless(levelIndex);
    }

    /** @return 无尽层号（从 1 开始）；非无尽模式返回 0。 */
    public int getEndlessNumber() {
        return isEndless() ? campaign.getEndlessNumber(levelIndex) : 0;
    }

    /** @return 当前关卡名。 */
    public String getLevelName() {
        return level.getName();
    }

    /** @return 当前关卡标题，例如 {@code 第 3 关 · 穿廊} 或 {@code 无尽第 12 层}。 */
    public String getLevelTitle() {
        return campaign.getTitle(levelIndex);
    }

    /** @return 进度短文本，例如 {@code 3/10} 或 {@code 无尽 12}。 */
    public String getShortProgress() {
        return campaign.getShortProgress(levelIndex);
    }

    /** @return 玩家位置（一维下标）。 */
    public int getPlayer() {
        return player;
    }

    /** @return 箱子位置数组的副本。 */
    public int[] getBoxes() {
        return boxes.clone();
    }

    /** @return 已走步数。 */
    public int getSteps() {
        return steps;
    }

    /** @return 已推动箱子的次数。 */
    public int getPushes() {
        return pushes;
    }

    /** @return 是否已经过关。 */
    public boolean isWon() {
        return won;
    }

    /** @return 玩家当前朝向。 */
    public Dir getFacing() {
        return facing;
    }

    @Override
    public String toString() {
        return "SokobanGame{" + getLevelTitle() + ", steps=" + steps
                + ", pushes=" + pushes + ", won=" + won
                + ", unlocked=" + maxUnlockedLevel + "}";
    }

    /** 便于调试：把当前局面还原成字符画。 */
    public String toText() {
        int w = level.getWidth();
        int h = level.getHeight();
        char[][] grid = new char[h][w];
        for (int y = 0; y < h; y++) {
            Arrays.fill(grid[y], Level.FLOOR);
            for (int x = 0; x < w; x++) {
                if (level.isWall(x, y)) {
                    grid[y][x] = Level.WALL;
                } else if (level.isGoal(y * w + x)) {
                    grid[y][x] = Level.GOAL;
                }
            }
        }
        for (int i = 0; i < boxes.length; i++) {
            int box = boxes[i];
            int x = box % w;
            int y = box / w;
            if (level.isPaired()) {
                // 配对模式下用数字标出每个箱子的编号，就位时用 '='
                grid[y][x] = level.isBoxPlaced(i, box) ? '='
                        : (char) ('1' + (i % 10));
            } else {
                grid[y][x] = level.isGoal(box) ? Level.BOX_ON_GOAL : Level.BOX;
            }
        }
        grid[player / w][player % w] =
                level.isGoal(player) ? Level.PLAYER_ON_GOAL : Level.PLAYER;

        StringBuilder sb = new StringBuilder();
        for (int y = 0; y < h; y++) {
            sb.append(grid[y]).append('\n');
        }
        return sb.toString();
    }
}
