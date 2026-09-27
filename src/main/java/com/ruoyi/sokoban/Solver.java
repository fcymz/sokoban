package com.ruoyi.sokoban;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * 推箱子求解器，给“提示”功能算一条尽量短的解法。
 *
 * <h3>为什么按“推箱”而不是“走子”来搜</h3>
 * 直接在走子层面做广度优先，状态数会被玩家位置放大几十倍：同一组箱子摆法，
 * 玩家站在几十个不同格子上都算不同状态。地图一大就炸了，只能退回生成器留下的
 * 那条又长又绕的解法——这正是“自动演示步数太多、全是无用操作”的原因。
 *
 * <p>这里改成<b>只在推箱动作上搜索</b>：状态是“箱子摆法 + 玩家所在连通区域”，
 * 格子之间怎么走过去属于走路，不进入搜索。状态数少了一个数量级，再用 A*
 * （启发值取“所有箱子到目标点的距离之和”）就能在可接受的时间里搜完。</p>
 *
 * <p>搜到的是<b>推箱次数最少</b>的解，随后把每两次推箱之间的走动补成最短路径，
 * 总步数也就接近最优了。</p>
 */
public final class Solver {

    /** 默认的搜索规模上限。 */
    public static final int DEFAULT_STATE_LIMIT = 600000;

    /**
     * “走子级”最短步数搜索的状态上限。
     *
     * <p>地图小的时候这层搜索能给出真正的最短步数解，所以先试它；
     * 一旦超过这个规模就说明地图够大，继续搜只会白等，直接改用推箱级搜索。</p>
     */
    private static final int MOVE_SEARCH_LIMIT = 150000;

    /** 搜索树节点：一次推箱。 */
    private static final class Node {
        private final int player;      // 玩家所在区域的最小格，用于判重
        private final int[] boxes;
        private final Node parent;
        private final int boxIndex;    // 第几个箱子被推
        private final int dirOrdinal;  // 推动方向
        private final int g;           // 已推次数
        private final int f;           // g + 启发值

        Node(int player, int[] boxes, Node parent, int boxIndex, int dirOrdinal, int g, int f) {
            this.player = player;
            this.boxes = boxes;
            this.parent = parent;
            this.boxIndex = boxIndex;
            this.dirOrdinal = dirOrdinal;
            this.g = g;
            this.f = f;
        }
    }

    private Solver() {
    }

    /**
     * 求一条尽量短的解法。
     *
     * @param level 关卡
     * @return 完整操作序列；无解或超出规模上限时返回 {@code null}
     */
    public static List<SokobanGame.Dir> solve(Level level) {
        return solve(level, DEFAULT_STATE_LIMIT);
    }

    /**
     * 求一条尽量短的解法。
     *
     * @param level      关卡
     * @param stateLimit 最多展开多少个推箱状态
     * @return 完整操作序列；无解或超出上限时返回 {@code null}
     */
    public static List<SokobanGame.Dir> solve(Level level, int stateLimit) {
        if (level == null) {
            return null;
        }
        // 小地图先用“走子级”广度优先，拿到的是真正的最短步数解
        List<SokobanGame.Dir> exact = solveByMoves(level, MOVE_SEARCH_LIMIT);
        if (exact != null) {
            return simplify(level, exact);
        }
        // 大地图走子级会爆炸，改用“推箱级”A*：推箱次数最少，走动补最短路径
        List<int[]> pushes = solvePushes(level, stateLimit);
        if (pushes == null) {
            return null;
        }
        List<SokobanGame.Dir> moves = expand(level, pushes);
        return moves == null ? null : simplify(level, moves);
    }

    /* ---------------- 走子级最短步数搜索 ---------------- */

    /** 走子级搜索树节点。 */
    private static final class MoveNode {
        private final int player;
        private final int[] boxes;
        private final MoveNode parent;
        private final SokobanGame.Dir dir;

        MoveNode(int player, int[] boxes, MoveNode parent, SokobanGame.Dir dir) {
            this.player = player;
            this.boxes = boxes;
            this.parent = parent;
            this.dir = dir;
        }
    }

    /**
     * 走子级广度优先：每一步算一步，得到的是真正最短的操作序列。
     *
     * @param stateLimit 状态数上限，超过就放弃
     * @return 最短操作序列；超出上限时返回 {@code null}
     */
    private static List<SokobanGame.Dir> solveByMoves(Level level, int stateLimit) {
        int width = level.getWidth();
        int[] startBoxes = level.getBoxStarts();
        boolean paired = level.isPaired();
        if (!paired) {
            Arrays.sort(startBoxes);
        }

        MoveNode start = new MoveNode(level.getPlayerStart(), startBoxes, null, null);
        Map<String, Boolean> seen = new HashMap<String, Boolean>();
        seen.put(key(start.player, start.boxes), Boolean.TRUE);

        Deque<MoveNode> queue = new ArrayDeque<MoveNode>();
        queue.add(start);

        while (!queue.isEmpty()) {
            if (seen.size() > stateLimit) {
                return null;
            }
            MoveNode current = queue.poll();
            if (level.isSolvedBy(current.boxes)) {
                return buildMoves(current);
            }
            int px = current.player % width;
            int py = current.player / width;
            for (SokobanGame.Dir dir : SokobanGame.Dir.values()) {
                int nx = px + dir.dx;
                int ny = py + dir.dy;
                if (level.isWall(nx, ny)) {
                    continue;
                }
                int target = ny * width + nx;
                int boxIndex = indexOf(current.boxes, target);

                int[] nextBoxes = current.boxes;
                if (boxIndex >= 0) {
                    int bx = nx + dir.dx;
                    int by = ny + dir.dy;
                    if (level.isWall(bx, by)) {
                        continue;
                    }
                    int beyond = by * width + bx;
                    if (indexOf(current.boxes, beyond) >= 0) {
                        continue;
                    }
                    nextBoxes = current.boxes.clone();
                    nextBoxes[boxIndex] = beyond;
                    if (!paired) {
                        Arrays.sort(nextBoxes);
                    }
                }
                if (seen.put(key(target, nextBoxes), Boolean.TRUE) == null) {
                    queue.add(new MoveNode(target, nextBoxes, current, dir));
                }
            }
        }
        return null;
    }

    private static List<SokobanGame.Dir> buildMoves(MoveNode node) {
        LinkedList<SokobanGame.Dir> path = new LinkedList<SokobanGame.Dir>();
        MoveNode current = node;
        while (current != null && current.dir != null) {
            path.addFirst(current.dir);
            current = current.parent;
        }
        return path;
    }

    /* ---------------- 第一段：按推箱搜索 ---------------- */

    /**
     * 搜出推箱次数最少的推箱序列（A*，启发值可采纳，因此确实最优）。
     *
     * @return 每项是 {@code {箱子序号, 方向序号}}；搜不到返回 {@code null}
     */
    private static List<int[]> solvePushes(Level level, int stateLimit) {
        int width = level.getWidth();
        int[] startBoxes = level.getBoxStarts();
        if (!level.isPaired()) {
            Arrays.sort(startBoxes);
        }
        int[] goalDistance = nearestGoalField(level);

        Node start = new Node(canonical(level, level.getPlayerStart(), startBoxes),
                startBoxes, null, -1, -1, 0, heuristic(level, startBoxes, goalDistance));
        Map<String, Integer> bestG = new HashMap<String, Integer>();
        bestG.put(key(start.player, start.boxes), Integer.valueOf(0));

        PriorityQueue<Node> open = new PriorityQueue<Node>(64, new Comparator<Node>() {
            @Override
            public int compare(Node a, Node b) {
                if (a.f != b.f) {
                    return a.f < b.f ? -1 : 1;
                }
                // 代价相同时优先展开已经推得多的，更快逼近目标
                return b.g - a.g;
            }
        });
        open.add(start);

        SokobanGame.Dir[] dirs = SokobanGame.Dir.values();
        int expanded = 0;
        while (!open.isEmpty()) {
            Node current = open.poll();
            Integer known = bestG.get(key(current.player, current.boxes));
            if (known != null && current.g > known.intValue()) {
                continue;   // 已经有更省推箱次数的路径到过这里，这是过期节点
            }
            if (level.isSolvedBy(current.boxes)) {
                return buildPushes(current);
            }
            if (++expanded > stateLimit) {
                return null;
            }

            boolean[] reach = reachable(level, current.player, current.boxes);
            for (int i = 0; i < current.boxes.length; i++) {
                int box = current.boxes[i];
                int bx = box % width;
                int by = box / width;
                for (int d = 0; d < dirs.length; d++) {
                    SokobanGame.Dir dir = dirs[d];
                    int standX = bx - dir.dx;
                    int standY = by - dir.dy;
                    int toX = bx + dir.dx;
                    int toY = by + dir.dy;
                    if (level.isWall(standX, standY) || level.isWall(toX, toY)) {
                        continue;
                    }
                    if (!reach[standY * width + standX]) {
                        continue;
                    }
                    int to = toY * width + toX;
                    if (indexOf(current.boxes, to) >= 0) {
                        continue;
                    }

                    int[] boxes = current.boxes.clone();
                    boxes[i] = to;
                    if (!level.isPaired()) {
                        Arrays.sort(boxes);
                    }
                    int player = canonical(level, box, boxes);
                    int g = current.g + 1;
                    String k = key(player, boxes);
                    Integer seen = bestG.get(k);
                    if (seen != null && seen.intValue() <= g) {
                        continue;
                    }
                    bestG.put(k, Integer.valueOf(g));
                    open.add(new Node(player, boxes, current, i, d, g,
                            g + heuristic(level, boxes, goalDistance)));
                }
            }
        }
        return null;
    }

    private static List<int[]> buildPushes(Node node) {
        LinkedList<int[]> pushes = new LinkedList<int[]>();
        Node current = node;
        while (current != null && current.boxIndex >= 0) {
            pushes.addFirst(new int[] {current.boxIndex, current.dirOrdinal});
            current = current.parent;
        }
        return pushes;
    }

    /* ---------------- 第二段：把推箱序列补成完整走法 ---------------- */

    /**
     * 把推箱序列展开成“走过去 + 推一下”的完整操作序列，走动一律取最短路径。
     *
     * @return 完整操作序列；中间某步走不过去时返回 {@code null}
     */
    private static List<SokobanGame.Dir> expand(Level level, List<int[]> pushes) {
        SokobanGame.Dir[] dirs = SokobanGame.Dir.values();
        int width = level.getWidth();
        int[] state = initialState(level);
        List<SokobanGame.Dir> moves = new ArrayList<SokobanGame.Dir>();

        for (int[] push : pushes) {
            int boxCell = state[1 + push[0]];
            int bx = boxCell % width;
            int by = boxCell / width;
            SokobanGame.Dir dir = dirs[push[1]];

            int standX = bx - dir.dx;
            int standY = by - dir.dy;
            List<SokobanGame.Dir> walk = path(level, state, standY * width + standX);
            if (walk == null) {
                return null;
            }
            for (SokobanGame.Dir step : walk) {
                apply(level, state, step);
                moves.add(step);
            }
            if (!apply(level, state, dir)) {
                return null;
            }
            moves.add(dir);
        }
        return moves;
    }

    /**
     * 去掉解法里“走一步又原路走回来”这类无用操作。
     *
     * <p>只撤销<b>纯走动</b>的往返：推箱那一步不能撤，否则箱子会被推回去。</p>
     *
     * @param level 关卡
     * @param moves 原始操作序列
     * @return 精简后的序列；原序列有问题时原样返回
     */
    public static List<SokobanGame.Dir> simplify(Level level, List<SokobanGame.Dir> moves) {
        if (level == null || moves == null || moves.isEmpty()) {
            return moves;
        }
        int width = level.getWidth();
        int[] state = initialState(level);
        List<SokobanGame.Dir> result = new ArrayList<SokobanGame.Dir>(moves.size());
        Deque<Boolean> pushed = new ArrayDeque<Boolean>();

        for (SokobanGame.Dir dir : moves) {
            int nx = state[0] % width + dir.dx;
            int ny = state[0] / width + dir.dy;
            boolean isPush = !level.isWall(nx, ny) && indexOf(state, ny * width + nx) > 0;

            if (!isPush && !result.isEmpty()
                    && !pushed.peekLast().booleanValue()
                    && result.get(result.size() - 1) == dir.opposite()) {
                // 后一步把前一步的纯走动原样走了回去，两步一起删掉
                SokobanGame.Dir last = result.remove(result.size() - 1);
                pushed.removeLast();
                apply(level, state, last.opposite());
                continue;
            }
            if (!apply(level, state, dir)) {
                return moves;   // 解法本身走不通，不要乱改
            }
            result.add(dir);
            pushed.addLast(Boolean.valueOf(isPush));
        }
        return result;
    }

    /* ---------------- 基础工具 ---------------- */

    /**
     * 在给定局面上执行一步，状态为 {@code [玩家位置, 箱子位置...]}。
     *
     * @param level 关卡
     * @param state 会被就地修改
     * @param dir   方向
     * @return 移动合法并已执行返回 {@code true}
     */
    public static boolean apply(Level level, int[] state, SokobanGame.Dir dir) {
        if (level == null || state == null || dir == null || state.length == 0) {
            return false;
        }
        int width = level.getWidth();
        int player = state[0];
        int nx = player % width + dir.dx;
        int ny = player / width + dir.dy;
        if (level.isWall(nx, ny)) {
            return false;
        }
        int target = ny * width + nx;

        int boxIndex = -1;
        for (int i = 1; i < state.length; i++) {
            if (state[i] == target) {
                boxIndex = i;
                break;
            }
        }
        if (boxIndex >= 0) {
            int bx = nx + dir.dx;
            int by = ny + dir.dy;
            if (level.isWall(bx, by)) {
                return false;
            }
            int beyond = by * width + bx;
            for (int i = 1; i < state.length; i++) {
                if (state[i] == beyond) {
                    return false;
                }
            }
            state[boxIndex] = beyond;
        }
        state[0] = target;
        return true;
    }

    /** 构造关卡的初始状态 {@code [玩家位置, 箱子位置...]}。 */
    public static int[] initialState(Level level) {
        int[] boxes = level.getBoxStarts();
        int[] state = new int[boxes.length + 1];
        state[0] = level.getPlayerStart();
        System.arraycopy(boxes, 0, state, 1, boxes.length);
        return state;
    }

    /**
     * 判断一条已知解法可以从给定局面接着走多久。
     *
     * @param level    关卡
     * @param solution 从关卡初始局面出发的解法
     * @param player   当前玩家位置
     * @param boxes    当前箱子位置（顺序任意）
     * @return 已经走过的前缀长度（可以接续的起点）；对不上返回 -1
     */
    public static int matchingPrefix(Level level, List<SokobanGame.Dir> solution,
                                     int player, int[] boxes) {
        if (level == null || solution == null || boxes == null) {
            return -1;
        }
        boolean paired = level.isPaired();
        int[] target = boxes.clone();
        if (!paired) {
            Arrays.sort(target);
        }
        int[] state = initialState(level);
        if (matches(state, player, target, paired)) {
            return 0;
        }
        for (int i = 0; i < solution.size(); i++) {
            if (!apply(level, state, solution.get(i))) {
                return -1;
            }
            if (matches(state, player, target, paired)) {
                return i + 1;
            }
        }
        return -1;
    }

    private static boolean matches(int[] state, int player, int[] targetBoxes, boolean paired) {
        if (state[0] != player || state.length - 1 != targetBoxes.length) {
            return false;
        }
        int[] current = Arrays.copyOfRange(state, 1, state.length);
        if (paired) {
            return Arrays.equals(current, targetBoxes);
        }
        Arrays.sort(current);
        return Arrays.equals(current, targetBoxes);
    }

    /** 玩家绕过墙和箱子从当前位置走到目标格的最短路径。 */
    private static List<SokobanGame.Dir> path(Level level, int[] state, int target) {
        int width = level.getWidth();
        int height = level.getHeight();
        int start = state[0];
        if (start == target) {
            return new ArrayList<SokobanGame.Dir>();
        }
        int[] previous = new int[width * height];
        Arrays.fill(previous, -2);
        previous[start] = -1;
        Deque<Integer> queue = new ArrayDeque<Integer>();
        queue.add(Integer.valueOf(start));
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
                if (previous[next] != -2 || indexOf(state, next) > 0) {
                    continue;
                }
                previous[next] = current;
                queue.add(Integer.valueOf(next));
            }
        }
        if (previous[target] == -2) {
            return null;
        }
        LinkedList<SokobanGame.Dir> result = new LinkedList<SokobanGame.Dir>();
        int current = target;
        while (current != start) {
            int before = previous[current];
            result.addFirst(dirBetween(before % width, before / width,
                    current % width, current / width));
            current = before;
        }
        return result;
    }

    private static SokobanGame.Dir dirBetween(int fromX, int fromY, int toX, int toY) {
        for (SokobanGame.Dir dir : SokobanGame.Dir.values()) {
            if (dir.dx == toX - fromX && dir.dy == toY - fromY) {
                return dir;
            }
        }
        throw new IllegalArgumentException("两点不相邻");
    }

    /** 玩家在不推箱子的前提下能走到的格子。 */
    private static boolean[] reachable(Level level, int from, int[] boxes) {
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
                if (seen[next] || indexOf(boxes, next) >= 0) {
                    continue;
                }
                seen[next] = true;
                queue.add(Integer.valueOf(next));
            }
        }
        return seen;
    }

    /** 把玩家位置归一化成所在区域里下标最小的格子。 */
    private static int canonical(Level level, int from, int[] boxes) {
        boolean[] region = reachable(level, from, boxes);
        for (int i = 0; i < region.length; i++) {
            if (region[i]) {
                return i;
            }
        }
        return from;
    }

    /** 每个格子到最近目标点的曼哈顿距离（未配对关卡的启发值用）。 */
    private static int[] nearestGoalField(Level level) {
        int width = level.getWidth();
        int height = level.getHeight();
        int[] field = new int[width * height];
        Arrays.fill(field, Integer.MAX_VALUE);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (!level.isGoal(y * width + x)) {
                    continue;
                }
                for (int cy = 0; cy < height; cy++) {
                    for (int cx = 0; cx < width; cx++) {
                        int index = cy * width + cx;
                        int distance = Math.abs(cx - x) + Math.abs(cy - y);
                        if (distance < field[index]) {
                            field[index] = distance;
                        }
                    }
                }
            }
        }
        for (int i = 0; i < field.length; i++) {
            if (field[i] == Integer.MAX_VALUE) {
                field[i] = 0;
            }
        }
        return field;
    }

    /**
     * 启发值：所有箱子到目标点的距离之和。
     *
     * <p>推一次箱子最多让这个和减少 1，所以它是“还差几次推箱”的下界，
     * 拿它做 A* 不会破坏最优性。</p>
     */
    private static int heuristic(Level level, int[] boxes, int[] goalDistance) {
        int width = level.getWidth();
        if (level.isPaired()) {
            int total = 0;
            for (int i = 0; i < boxes.length; i++) {
                int target = level.getBoxTarget(i);
                total += Math.abs(boxes[i] % width - target % width)
                        + Math.abs(boxes[i] / width - target / width);
            }
            return total;
        }
        int total = 0;
        for (int box : boxes) {
            total += goalDistance[box];
        }
        return total;
    }

    private static int indexOf(int[] cells, int value) {
        for (int i = 0; i < cells.length; i++) {
            if (cells[i] == value) {
                return i;
            }
        }
        return -1;
    }

    private static String key(int player, int[] boxes) {
        StringBuilder sb = new StringBuilder(boxes.length * 4 + 4);
        sb.append(player);
        for (int box : boxes) {
            sb.append(',').append(box);
        }
        return sb.toString();
    }
}
