package com.ruoyi.sokoban;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 推箱子求解器（广度优先搜索，返回最短的完整操作序列）。
 *
 * <p>内置关卡箱子少、地图小，BFS 可以秒出结果；无尽关卡箱子多，BFS 可能爆炸，
 * 因此设置了状态数上限，超限时返回 {@code null} 而不是把界面卡死。
 * 无尽关卡本身带有生成时得到的通关步骤，不需要走这里。</p>
 */
public final class Solver {

    /** 默认的状态数上限。 */
    public static final int DEFAULT_STATE_LIMIT = 600000;

    private Solver() {
    }

    /** BFS 搜索树节点，用于回溯出操作序列。 */
    private static final class Node {
        private final int player;
        private final int[] boxes;
        private final Node parent;
        private final SokobanGame.Dir dir;

        Node(int player, int[] boxes, Node parent, SokobanGame.Dir dir) {
            this.player = player;
            this.boxes = boxes;
            this.parent = parent;
            this.dir = dir;
        }
    }

    /**
     * 求最短解。
     *
     * @param level 关卡
     * @return 操作序列；无解或超出状态上限时返回 {@code null}
     */
    public static List<SokobanGame.Dir> solve(Level level) {
        return solve(level, DEFAULT_STATE_LIMIT);
    }

    /**
     * 求最短解。
     *
     * @param level      关卡
     * @param stateLimit 最多搜索多少个状态
     * @return 操作序列；无解或超出上限时返回 {@code null}
     */
    public static List<SokobanGame.Dir> solve(Level level, int stateLimit) {
        if (level == null) {
            return null;
        }
        int width = level.getWidth();
        int[] startBoxes = level.getBoxStarts();
        // 配对模式下箱子是有身份的，顺序即身份，不能排序
        if (!level.isPaired()) {
            Arrays.sort(startBoxes);
        }

        Node start = new Node(level.getPlayerStart(), startBoxes, null, null);
        Set<String> seen = new HashSet<String>();
        seen.add(key(start.player, startBoxes));

        Deque<Node> queue = new ArrayDeque<Node>();
        queue.add(start);

        while (!queue.isEmpty()) {
            if (seen.size() > stateLimit) {
                return null;
            }
            Node current = queue.poll();
            if (isSolved(level, current.boxes)) {
                return buildPath(current);
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
                    if (!level.isPaired()) {
                        Arrays.sort(nextBoxes);
                    }
                }

                if (seen.add(key(target, nextBoxes))) {
                    queue.add(new Node(target, nextBoxes, current, dir));
                }
            }
        }
        return null;
    }

    /**
     * 判断一条已知解法可以从给定局面接着走多久。
     *
     * <p>提示功能用它来决定“从当前局面继续演示”还是“重来后完整演示”。</p>
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
        int[] target = boxes.clone();
        boolean paired = level.isPaired();
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

    private static boolean matches(int[] state, int player, int[] targetBoxes, boolean paired) {
        if (state[0] != player || state.length - 1 != targetBoxes.length) {
            return false;
        }
        int[] current = Arrays.copyOfRange(state, 1, state.length);
        if (paired) {
            // 配对模式下箱子顺序就是身份，必须逐位比较
            return Arrays.equals(current, targetBoxes);
        }
        Arrays.sort(current);
        return Arrays.equals(current, targetBoxes);
    }

    private static boolean isSolved(Level level, int[] boxes) {
        return level.isSolvedBy(boxes);
    }

    private static int indexOf(int[] boxes, int index) {
        for (int i = 0; i < boxes.length; i++) {
            if (boxes[i] == index) {
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

    private static List<SokobanGame.Dir> buildPath(Node node) {
        List<SokobanGame.Dir> path = new ArrayList<SokobanGame.Dir>();
        Node current = node;
        while (current != null && current.dir != null) {
            path.add(current.dir);
            current = current.parent;
        }
        Collections.reverse(path);
        return path;
    }
}
