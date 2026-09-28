package com.ruoyi.sokoban;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个不可变的推箱子关卡定义。
 *
 * <p>关卡用字符画描述，每个字符的含义如下：</p>
 * <pre>
 *   '#' 墙        ' ' 地板      '.' 目标点
 *   '$' 箱子      '*' 箱子(已在目标点上)
 *   '@' 玩家      '+' 玩家(站在目标点上)
 * </pre>
 *
 * <p>不足行宽的短行会按“墙”补齐，因此任何情况下玩家都无法走出地图。</p>
 */
public final class Level {

    /** 墙。 */
    public static final char WALL = '#';
    /** 地板。 */
    public static final char FLOOR = ' ';
    /** 目标点。 */
    public static final char GOAL = '.';
    /** 箱子。 */
    public static final char BOX = '$';
    /** 位于目标点上的箱子。 */
    public static final char BOX_ON_GOAL = '*';
    /** 玩家。 */
    public static final char PLAYER = '@';
    /** 站在目标点上的玩家。 */
    public static final char PLAYER_ON_GOAL = '+';

    private final String name;
    private final int width;
    private final int height;
    private final boolean[] walls;
    private final boolean[] goals;
    private final int[] boxStarts;
    private final int playerStart;
    private final int goalCount;
    /** 箱子与目标点的一一对应关系：{@code boxTargets[i]} 是第 i 个箱子的专属目标点；为 null 表示可互换。 */
    private final int[] boxTargets;
    /** 按格子索引记录目标点的配对编号（从 1 开始，0 表示没有编号）。 */
    private final int[] goalLabels;

    /**
     * 解析一个关卡（箱子与目标点可互换）。
     *
     * @param name 关卡名
     * @param rows 字符画，每一行长度可以不同
     * @throws IllegalArgumentException 关卡非法（缺玩家、箱子与目标数量不符等）
     */
    public Level(String name, String... rows) {
        this(name, null, rows);
    }

    /**
     * 解析一个关卡，并指定箱子与目标点的一一对应关系。
     *
     * @param name       关卡名
     * @param boxTargets 按“箱子在字符画中出现的先后顺序”排列的专属目标点下标；
     *                   为 {@code null} 表示沿用“任意目标点都算完成”的经典规则
     * @param rows       字符画，每一行长度可以不同
     * @throws IllegalArgumentException 关卡非法
     */
    public Level(String name, int[] boxTargets, String... rows) {
        if (rows == null || rows.length == 0) {
            throw new IllegalArgumentException("关卡 [" + name + "] 内容为空");
        }
        int w = 0;
        for (String row : rows) {
            if (row != null) {
                w = Math.max(w, row.length());
            }
        }
        if (w == 0) {
            throw new IllegalArgumentException("关卡 [" + name + "] 宽度为 0");
        }

        this.name = name;
        this.width = w;
        this.height = rows.length;
        this.walls = new boolean[w * rows.length];
        this.goals = new boolean[w * rows.length];

        List<Integer> boxes = new ArrayList<Integer>();
        int player = -1;
        int goalsFound = 0;

        for (int y = 0; y < rows.length; y++) {
            String row = rows[y] == null ? "" : rows[y];
            for (int x = 0; x < w; x++) {
                char c = x < row.length() ? row.charAt(x) : WALL;
                int k = y * w + x;
                switch (c) {
                    case WALL:
                        walls[k] = true;
                        break;
                    case GOAL:
                        goals[k] = true;
                        goalsFound++;
                        break;
                    case BOX:
                        boxes.add(Integer.valueOf(k));
                        break;
                    case BOX_ON_GOAL:
                        goals[k] = true;
                        goalsFound++;
                        boxes.add(Integer.valueOf(k));
                        break;
                    case PLAYER:
                        player = k;
                        break;
                    case PLAYER_ON_GOAL:
                        goals[k] = true;
                        goalsFound++;
                        player = k;
                        break;
                    default:
                        // 其余字符一律视为地板
                        break;
                }
            }
        }

        if (player < 0) {
            throw new IllegalArgumentException("关卡 [" + name + "] 缺少玩家起点 '@'");
        }
        if (boxes.isEmpty()) {
            throw new IllegalArgumentException("关卡 [" + name + "] 没有任何箱子 '$'");
        }
        if (boxes.size() != goalsFound) {
            throw new IllegalArgumentException("关卡 [" + name + "] 箱子数 " + boxes.size()
                    + " 与目标点数 " + goalsFound + " 不相等");
        }

        this.playerStart = player;
        this.goalCount = goalsFound;
        this.boxStarts = new int[boxes.size()];
        for (int i = 0; i < boxStarts.length; i++) {
            boxStarts[i] = boxes.get(i).intValue();
        }

        this.goalLabels = new int[w * rows.length];
        if (boxTargets == null) {
            this.boxTargets = null;
        } else {
            if (boxTargets.length != boxStarts.length) {
                throw new IllegalArgumentException("关卡 [" + name + "] 的配对数量 "
                        + boxTargets.length + " 与箱子数 " + boxStarts.length + " 不一致");
            }
            boolean[] used = new boolean[w * rows.length];
            int[] copy = new int[boxTargets.length];
            for (int i = 0; i < boxTargets.length; i++) {
                int target = boxTargets[i];
                if (target < 0 || target >= used.length || !goals[target]) {
                    throw new IllegalArgumentException("关卡 [" + name + "] 第 " + (i + 1)
                            + " 个箱子配的目标不是目标点");
                }
                if (used[target]) {
                    throw new IllegalArgumentException("关卡 [" + name + "] 有多个箱子配到了同一个目标点");
                }
                used[target] = true;
                copy[i] = target;
                goalLabels[target] = i + 1;
            }
            this.boxTargets = copy;
        }
    }

    /** @return 关卡名。 */
    public String getName() {
        return name;
    }

    /** @return 关卡宽度（列数）。 */
    public int getWidth() {
        return width;
    }

    /** @return 关卡高度（行数）。 */
    public int getHeight() {
        return height;
    }

    /** @return 箱子数量，等于目标点数量。 */
    public int getBoxCount() {
        return boxStarts.length;
    }

    /** @return 目标点数量。 */
    public int getGoalCount() {
        return goalCount;
    }

    /** @return 玩家出生位置（一维下标）。 */
    public int getPlayerStart() {
        return playerStart;
    }

    /** @return 箱子初始位置数组的副本。 */
    public int[] getBoxStarts() {
        return boxStarts.clone();
    }

    /**
     * 判断某个坐标是否是墙或地图外。
     *
     * @param x 列
     * @param y 行
     * @return 是墙（或越界）返回 {@code true}
     */
    public boolean isWall(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            return true;
        }
        return walls[y * width + x];
    }

    /**
     * 判断某个下标是否是墙。
     *
     * @param index 一维下标
     * @return 是墙（或越界）返回 {@code true}
     */
    public boolean isWallAt(int index) {
        return index < 0 || index >= walls.length || walls[index];
    }

    /**
     * 判断某个下标是否是目标点。
     *
     * @param index 一维下标
     * @return 是目标点返回 {@code true}
     */
    public boolean isGoal(int index) {
        return index >= 0 && index < goals.length && goals[index];
    }

    /**
     * 是否启用了“箱子与目标点一一对应”的规则。
     *
     * @return 启用返回 {@code true}
     */
    public boolean isPaired() {
        return boxTargets != null;
    }

    /** @return 配对数量；未启用配对时返回 0。 */
    public int getPairCount() {
        return boxTargets == null ? 0 : boxTargets.length;
    }

    /**
     * 第 i 个箱子的专属目标点。
     *
     * @param boxIndex 箱子序号（按字符画中的出现顺序，从 0 开始）
     * @return 目标点下标；未启用配对时返回 -1
     */
    public int getBoxTarget(int boxIndex) {
        return boxTargets == null ? -1 : boxTargets[boxIndex];
    }

    /**
     * 第 i 个箱子的配对编号，用于界面上的图标。
     *
     * @param boxIndex 箱子序号
     * @return 从 1 开始的编号；未启用配对时返回 0
     */
    public int getBoxLabel(int boxIndex) {
        return boxTargets == null ? 0 : boxIndex + 1;
    }

    /**
     * 某个目标点的配对编号。
     *
     * @param cell 一维下标
     * @return 从 1 开始的编号；该格不是目标点或未启用配对时返回 0
     */
    public int getGoalLabel(int cell) {
        return cell >= 0 && cell < goalLabels.length ? goalLabels[cell] : 0;
    }

    /**
     * 判断给定箱子摆放是否已经通关。
     *
     * <p>启用配对时要求每个箱子都在<b>它自己的</b>目标点上；否则只要是目标点即可。</p>
     *
     * @param boxes 箱子位置，顺序必须与 {@link #getBoxStarts()} 一致
     * @return 通关返回 {@code true}
     */
    public boolean isSolvedBy(int[] boxes) {
        if (boxes == null || boxes.length != boxStarts.length) {
            return false;
        }
        if (boxTargets == null) {
            for (int box : boxes) {
                if (!isGoal(box)) {
                    return false;
                }
            }
            return true;
        }
        for (int i = 0; i < boxes.length; i++) {
            if (boxes[i] != boxTargets[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断第 i 个箱子是否已经就位。
     *
     * @param boxIndex 箱子序号
     * @param cell     箱子当前位置
     * @return 就位返回 {@code true}
     */
    public boolean isBoxPlaced(int boxIndex, int cell) {
        if (boxTargets == null) {
            return isGoal(cell);
        }
        return cell == boxTargets[boxIndex];
    }

    @Override
    public String toString() {
        return "Level{" + name + ", " + width + "x" + height
                + ", boxes=" + boxStarts.length + "}";
    }
}
