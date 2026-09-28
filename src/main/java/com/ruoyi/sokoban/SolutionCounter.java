package com.ruoyi.sokoban;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 统计一个关卡有多少种通关方式。
 *
 * <h3>两种口径</h3>
 * <ul>
 *   <li>{@link #count}：以“推一次箱子”为一步，统计从开局推到通关的<b>路径</b>条数。
 *       它对小关卡（1~2 个箱子）很好用，但箱子一多就会爆炸——因为
 *       “同一串推箱、玩家从不同方向绕过去站好”也会被当成不同路径。</li>
 *   <li>{@link #countPlans}：只统计<b>互不相同的推箱方案</b>，也就是“按什么顺序推哪个箱子、
 *       往哪个方向推”。玩家怎么走位不算差异。这才是玩家嘴里“这一关有几种解法”的意思，
 *       也是关卡生成器判定难度的口径。</li>
 * </ul>
 *
 * <h3>局面归一化</h3>
 * 单纯用“玩家位置 + 箱子集合”表示局面会出问题：玩家在两次推箱之间可以自由走动，
 * 而且开局时玩家的位置也不一定是“刚推完箱子”的位置。所以这里把玩家位置归一化成
 * <b>它所在连通区域里下标最小的格子</b>，这样“走得到但位置不同”的局面会自动合并，
 * 逆向搜索也才能和开局对上。
 */
public final class SolutionCounter {

    /** 统计结果。 */
    public static final class Result {

        private final int count;
        private final boolean exact;
        private final int states;

        Result(int count, boolean exact, int states) {
            this.count = count;
            this.exact = exact;
            this.states = states;
        }

        /** @return 通关方式的数量；不精确时含义见 {@link #isExact()}。 */
        public int getCount() {
            return count;
        }

        /**
         * @return 是否在给定上限内把结果算准了。
         *         {@code false} 表示搜索触顶或超限，{@link #getCount()} 不可信。
         */
        public boolean isExact() {
            return exact;
        }

        /** @return 搜索过程中访问过的局面数。 */
        public int getStates() {
            return states;
        }
    }

    /** 一个推箱动作：箱子序号 + 方向序号，用于给“方案”做指纹。 */
    private static final class Move {
        private final State next;
        private final int boxIndex;
        private final int dirOrdinal;

        Move(State next, int boxIndex, int dirOrdinal) {
            this.next = next;
            this.boxIndex = boxIndex;
            this.dirOrdinal = dirOrdinal;
        }
    }

    /** 一个归一化局面：玩家所在区域的最小格 + 有序箱子集合。 */
    private static final class State {
        private final int player;
        private final int[] boxes;

        State(int player, int[] boxes) {
            this.player = player;
            this.boxes = boxes;
        }

        private boolean solved(Level level) {
            return level.isSolvedBy(boxes);
        }
    }

    private SolutionCounter() {
    }

    /* ---------------- 口径一：推箱路径条数 ---------------- */

    /**
     * 统计通关路径条数。
     *
     * @param level      关卡
     * @param limit      数量上限，达到即提前返回
     * @param stateLimit 反向可达搜索的局面数上限
     * @param pathLimit  正向路径搜索的节点数上限
     * @return 统计结果
     */
    public static Result count(Level level, int limit, int stateLimit, int pathLimit) {
        return countInternal(level, limit, stateLimit, pathLimit, false);
    }

    /**
     * 同 {@link #count}，但<b>已经落在自己目标点上的箱子不允许再被推走</b>。
     *
     * <p>“箱子推上去了又推下来、再推回去”虽然形式上确实是另一条路径，但玩起来完全是
     * 同一套走法；箱子越多这种路径就越多，所以需要一个更严格的口径。</p>
     *
     * @param level      关卡
     * @param limit      数量上限，达到即提前返回
     * @param stateLimit 反向可达搜索的局面数上限
     * @param pathLimit  正向路径搜索的节点数上限
     * @return 统计结果
     */
    public static Result countStrict(Level level, int limit, int stateLimit, int pathLimit) {
        return countInternal(level, limit, stateLimit, pathLimit, true);
    }

    private static Result countInternal(Level level, int limit, int stateLimit, int pathLimit,
                                        boolean keepPlacedBoxes) {
        boolean paired = level.isPaired();
        int[] startBoxes = level.getBoxStarts();
        // 配对模式下箱子顺序即身份，不能排序
        if (!paired) {
            Arrays.sort(startBoxes);
        }
        State start = new State(canonical(level, level.getPlayerStart(), startBoxes), startBoxes);
        int[] seedBoxes = paired ? level.getBoxTargets() : collectGoalCells(level);

        // 1) 反向搜索：从通关局面倒推，找出所有“还能走回通关”的局面。
        //    通关时箱子都在目标点上，玩家可能在任意位置；箱子会把空地切成若干块，
        //    每一块都要单独作为起点，否则会漏掉一整片可行局面。
        //    这里不能照搬“就位箱子不再动”的剪枝：起点处所有箱子都在目标点上，
        //    一剪枝就一个前驱都展开不了。反向搜索宽松一点没关系，正向统计会再挡一道。
        Set<String> solvable = new HashSet<String>();
        Deque<State> queue = new ArrayDeque<State>();
        Set<Integer> seededRegions = new HashSet<Integer>();
        for (int y = 0; y < level.getHeight(); y++) {
            for (int x = 0; x < level.getWidth(); x++) {
                int cell = y * level.getWidth() + x;
                if (level.isWall(x, y) || contains(seedBoxes, cell)) {
                    continue;
                }
                int region = canonical(level, cell, seedBoxes);
                if (!seededRegions.add(Integer.valueOf(region))) {
                    continue;
                }
                State seed = new State(region, seedBoxes.clone());
                if (solvable.add(key(seed))) {
                    queue.add(seed);
                }
            }
        }
        boolean exact = true;
        while (!queue.isEmpty()) {
            if (solvable.size() > stateLimit) {
                exact = false;
                break;
            }
            State current = queue.poll();
            for (State previous : predecessors(level, current)) {
                if (solvable.add(key(previous))) {
                    queue.add(previous);
                }
            }
        }

        if (!solvable.contains(key(start))) {
            // 开局就走不到通关状态；反向搜索没搜完时不能断定无解
            return new Result(0, false, solvable.size());
        }

        // 2) 正向统计“不重复局面的推箱路径”条数
        int[] found = new int[1];
        int[] visitedNodes = new int[1];
        Set<String> onPath = new HashSet<String>();
        boolean completed = walk(level, start, solvable, onPath, found, visitedNodes,
                limit, pathLimit, keepPlacedBoxes);
        return new Result(Math.min(found[0], limit), completed && exact, solvable.size());
    }

    /**
     * 深度优先枚举“不重复局面的推箱路径”。
     *
     * @return 是否在限制内搜索完成
     */
    private static boolean walk(Level level, State current, Set<String> solvable,
                                Set<String> onPath, int[] found, int[] visitedNodes,
                                int limit, int pathLimit, boolean keepPlacedBoxes) {
        if (current.solved(level)) {
            found[0]++;
            return true;
        }
        if (found[0] >= limit) {
            return false;
        }
        if (++visitedNodes[0] > pathLimit) {
            return false;
        }

        String currentKey = key(current);
        onPath.add(currentKey);
        boolean completed = true;
        try {
            for (Move move : successors(level, current, keepPlacedBoxes)) {
                String nextKey = key(move.next);
                if (!solvable.contains(nextKey) || onPath.contains(nextKey)) {
                    continue;
                }
                if (!walk(level, move.next, solvable, onPath, found, visitedNodes, limit, pathLimit,
                        keepPlacedBoxes)) {
                    completed = false;
                    break;
                }
            }
        } finally {
            onPath.remove(currentKey);
        }
        return completed;
    }

    /** 收集所有目标点，升序排列。 */
    private static int[] collectGoalCells(Level level) {
        List<Integer> cells = new ArrayList<Integer>();
        for (int y = 0; y < level.getHeight(); y++) {
            for (int x = 0; x < level.getWidth(); x++) {
                int cell = y * level.getWidth() + x;
                if (level.isGoal(cell)) {
                    cells.add(Integer.valueOf(cell));
                }
            }
        }
        int[] result = new int[cells.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = cells.get(i).intValue();
        }
        Arrays.sort(result);
        return result;
    }

    /* ---------------- 口径二：互不相同的推箱方案 ---------------- */

    /**
     * 统计<b>互不相同的推箱方案</b>数量。
     *
     * <p>方案由“推哪个箱子、往哪个方向推”这一串动作决定；玩家为了站到推箱位置而绕的路
     * 不计入差异。已经就位的箱子不再被推走，避免同一套方案被“推上去再推下来”重复计数。</p>
     *
     * @param level     关卡
     * @param limit     方案数量上限，达到即提前返回
     * @param pathLimit 搜索节点数上限
     * @return 统计结果
     */
    public static Result countPlans(Level level, int limit, int pathLimit) {
        int[] startBoxes = level.getBoxStarts();
        if (!level.isPaired()) {
            Arrays.sort(startBoxes);
        }
        State start = new State(canonical(level, level.getPlayerStart(), startBoxes),
                startBoxes);

        Set<String> plans = new HashSet<String>();
        // seen：同一个局面只展开一次。玩家在同一片连通区域里的不同站位是等价的，
        // 归一化之后天然合并，方案也就不会因为“从哪边绕过去”而重复。
        Set<String> seen = new HashSet<String>();
        int[] visitedNodes = new int[1];
        boolean completed = walkPlans(level, start, seen, new StringBuilder(), plans,
                visitedNodes, limit, pathLimit);
        return new Result(Math.min(plans.size(), limit), completed, seen.size());
    }

    /**
     * 深度优先枚举推箱方案。
     *
     * @param seen 已经展开过的局面
     * @return 是否在限制内搜索完成
     */
    private static boolean walkPlans(Level level, State current, Set<String> seen,
                                     StringBuilder plan, Set<String> plans,
                                     int[] visitedNodes, int limit, int pathLimit) {
        if (current.solved(level)) {
            plans.add(plan.toString());
            return plans.size() < limit;
        }
        if (plans.size() >= limit) {
            return false;
        }
        if (++visitedNodes[0] > pathLimit) {
            return false;
        }
        if (!seen.add(key(current))) {
            // 这个局面之前已经展开过，同一批后续方案不必再数一遍
            return true;
        }

        boolean completed = true;
        int mark = plan.length();
        for (Move move : successors(level, current, true)) {
            plan.append(move.boxIndex).append(':').append(move.dirOrdinal).append(';');
            if (!walkPlans(level, move.next, seen, plan, plans, visitedNodes, limit, pathLimit)) {
                completed = false;
                plan.setLength(mark);
                break;
            }
            plan.setLength(mark);
        }
        plan.setLength(mark);
        return completed;
    }

    /* ---------------- 推演 ---------------- */

    /**
     * 反向推演：列出所有“推一步之后变成 {@code current}”的局面。
     *
     * <p>正向推箱：玩家在 B-d，箱子在 B，推方向 d 之后箱子到 B+d、玩家站到 B。
     * 所以给定 {@code current} 里某个箱子 B'，它只可能是从 B'-d 推过来的，
     * 并且推完之后玩家就站在 B'-d，因此 {@code current} 的玩家区域必须是 B'-d 所在的区域。</p>
     */
    private static List<State> predecessors(Level level, State current) {
        int width = level.getWidth();
        List<State> result = new ArrayList<State>();
        for (int i = 0; i < current.boxes.length; i++) {
            int to = current.boxes[i];
            int toX = to % width;
            int toY = to / width;
            for (SokobanGame.Dir dir : SokobanGame.Dir.values()) {
                int fromX = toX - dir.dx;
                int fromY = toY - dir.dy;
                if (level.isWall(fromX, fromY)) {
                    continue;
                }
                int standX = fromX - dir.dx;      // 玩家推箱时站的位置
                int standY = fromY - dir.dy;
                if (level.isWall(standX, standY)) {
                    continue;
                }
                int stand = standY * width + standX;
                if (contains(current.boxes, stand)) {
                    continue;
                }
                int from = fromY * width + fromX;
                // 推完后玩家站在 from，所以当前局面的玩家区域必须就是 from 所在区域
                if (canonical(level, from, current.boxes) != current.player) {
                    continue;
                }
                int[] boxes = current.boxes.clone();
                boxes[i] = from;
                if (!level.isPaired()) {
                    Arrays.sort(boxes);
                }
                result.add(new State(canonical(level, stand, boxes), boxes));
            }
        }
        return result;
    }

    /** 正向推演：从当前局面出发，所有可能的推箱结果。 */
    private static List<Move> successors(Level level, State current, boolean keepPlacedBoxes) {
        int width = level.getWidth();
        List<Move> result = new ArrayList<Move>();
        boolean[] reachable = reachableCells(level, current.player, current.boxes);

        for (int i = 0; i < current.boxes.length; i++) {
            int box = current.boxes[i];
            if (keepPlacedBoxes && level.isBoxPlaced(i, box)) {
                continue;   // 推到目标点上的箱子就不要再推走了，否则只是把同一套走法来回折腾
            }
            int boxX = box % width;
            int boxY = box / width;
            for (SokobanGame.Dir dir : SokobanGame.Dir.values()) {
                int standX = boxX - dir.dx;
                int standY = boxY - dir.dy;
                int toX = boxX + dir.dx;
                int toY = boxY + dir.dy;
                if (level.isWall(standX, standY) || level.isWall(toX, toY)) {
                    continue;
                }
                if (!reachable[standY * width + standX]) {
                    continue;
                }
                int to = toY * width + toX;
                if (contains(current.boxes, to)) {
                    continue;
                }
                int[] boxes = current.boxes.clone();
                boxes[i] = to;
                if (!level.isPaired()) {
                    Arrays.sort(boxes);
                }
                // 推完后玩家站在箱子原来的位置
                result.add(new Move(new State(canonical(level, box, boxes), boxes),
                        i, dir.ordinal()));
            }
        }
        return result;
    }

    /**
     * 归一化玩家位置：返回它所在连通区域里下标最小的格子。
     *
     * <p>区域指“不推箱子就能走到”的范围，箱子被当作障碍。</p>
     */
    private static int canonical(Level level, int from, int[] boxes) {
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
            for (SokobanGame.Dir dir : SokobanGame.Dir.values()) {
                int nx = cx + dir.dx;
                int ny = cy + dir.dy;
                if (level.isWall(nx, ny)) {
                    continue;
                }
                int next = ny * width + nx;
                if (seen[next] || contains(boxes, next)) {
                    continue;
                }
                seen[next] = true;
                queue.add(Integer.valueOf(next));
            }
        }
        for (int i = 0; i < seen.length; i++) {
            if (seen[i]) {
                return i;
            }
        }
        return from;
    }

    /** 玩家在不推箱子的前提下能走到的格子。 */
    private static boolean[] reachableCells(Level level, int player, int[] boxes) {
        int width = level.getWidth();
        int height = level.getHeight();
        boolean[] seen = new boolean[width * height];
        Deque<Integer> queue = new ArrayDeque<Integer>();
        seen[player] = true;
        queue.add(Integer.valueOf(player));
        while (!queue.isEmpty()) {
            int current = queue.poll().intValue();
            int cx = current % width;
            int cy = current / width;
            for (SokobanGame.Dir dir : SokobanGame.Dir.values()) {
                int nx = cx + dir.dx;
                int ny = cy + dir.dy;
                if (level.isWall(nx, ny)) {
                    continue;
                }
                int next = ny * width + nx;
                if (seen[next] || contains(boxes, next)) {
                    continue;
                }
                seen[next] = true;
                queue.add(Integer.valueOf(next));
            }
        }
        return seen;
    }

    private static boolean contains(int[] cells, int value) {
        for (int cell : cells) {
            if (cell == value) {
                return true;
            }
        }
        return false;
    }

    private static String key(State state) {
        StringBuilder sb = new StringBuilder(state.boxes.length * 4 + 4);
        sb.append(state.player);
        for (int box : state.boxes) {
            sb.append(',').append(box);
        }
        return sb.toString();
    }
}
