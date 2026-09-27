package com.ruoyi.sokoban;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 无尽模式关卡生成器。
 *
 * <h3>为什么生成的关卡一定有解</h3>
 * 采用“反向拉箱”构造：先摆出一个<b>已经通关</b>的局面（所有箱子都压在目标点上），
 * 再不断地执行“逆向拉箱”。一次逆向拉箱的定义是——玩家在 P，箱子在 P+d，
 * 把箱子拉到 P、玩家退到 P-d（要求 P-d 不是墙也没有箱子）。
 *
 * <p>把这次拉箱倒过来看，恰好就是一次合法推箱：玩家从 P-d 推向 P，箱子从 P 被推到 P+d。
 * 于是把整个拉箱序列反过来，就是一条从头走到通关的完整操作序列。
 * 因此生成的局面 100% 有解，且这条序列可以直接当作“提示”使用。</p>
 *
 * <h3>地图扩张</h3>
 * 每 {@value #LEVELS_PER_EXPANSION} 层，横竖各扩张 1 格。例如第 1~5 层为
 * {@value #BASE_WIDTH}×{@value #BASE_HEIGHT}，第 6~10 层为 9×8，以此类推。
 * 为避免层数很高时棋盘大到无法游玩，尺寸上限为 {@value #MAX_WIDTH}×{@value #MAX_HEIGHT}。
 */
public final class EndlessGenerator {

    /** 无尽头几层的基础宽度。 */
    public static final int BASE_WIDTH = 10;
    /** 无尽头几层的基础高度。 */
    public static final int BASE_HEIGHT = 9;
    /** 每多少层扩张一次。 */
    public static final int LEVELS_PER_EXPANSION = 5;
    /** 宽度上限（安全阀）。 */
    public static final int MAX_WIDTH = 24;
    /** 高度上限（安全阀）。 */
    public static final int MAX_HEIGHT = 24;

    /** 单层最多尝试生成多少次。 */
    private static final int MAX_ATTEMPTS = 400;
    /** 单层最多对几个候选做“最短解”度量（BFS 不便宜，要限量）。 */
    private static final int MAX_MEASUREMENTS = 8;
    /** 度量用的 BFS 状态上限，超过就认为这关足够复杂。 */
    private static final int MEASURE_STATE_LIMIT = 150000;
    /** 通关走法数量上限：第 {@value #STRICT_FROM_LEVEL} 层之后必须不超过这个数。 */
    private static final int MAX_SOLUTIONS = 10;
    /** 从第几层之后开始强制“走法不多于 {@value #MAX_SOLUTIONS} 种”。 */
    private static final int STRICT_FROM_LEVEL = 20;
    /** 严格模式下要求最短解不少于多少步，避免生成两步通关的水关。 */
    private static final int STRICT_MIN_MOVES = 15;
    /** 走法统计的反向可达状态上限。 */
    private static final int SOLUTION_STATE_LIMIT = 40000;
    /** 走法统计的正向路径搜索节点上限。 */
    private static final int SOLUTION_PATH_LIMIT = 50000;
    /** 严格模式下的生成时间预算（纳秒）。 */
    private static final long STRICT_BUDGET_NANOS = 4000000000L;
    /** 严格模式下最多对几个候选做走法统计。 */
    private static final int MAX_STRICT_MEASUREMENTS = 120;
    /** 严格模式下最多尝试生成多少次（失败的尝试很便宜，可以多试）。 */
    private static final int MAX_STRICT_ATTEMPTS = 6000;
    /** 内部墙占比：基础密度，以及 20 层之后额外增加的幅度与上限。 */
    private static final double WALL_RATIO_BASE = 0.16d;
    private static final double WALL_RATIO_EXTRA_PER_LEVEL = 0.02d;
    private static final double WALL_RATIO_MAX = 0.38d;
    /** 单层最多箱子数。 */
    private static final int MAX_BOXES = 3;
    /** 20 层之后（要求走法很少）时的箱子数上限：箱子越多出路越多。 */
    private static final int MAX_BOXES_STRICT = 1;
    /** 迷宫地图的环路比例（相对房间数）。 */
    private static final double MAZE_LOOP_RATIO = 0.08d;
    /** 无尽模式下，箱子开局到它自己专属目标点的曼哈顿距离下限。 */
    private static final int MIN_BOX_TARGET_DISTANCE = 7;

    /** 拉箱序列中的一步。 */
    private static final class Step {
        /** 方向。 */
        private final SokobanGame.Dir dir;
        /** {@code true} 表示拉箱，{@code false} 表示走动。 */
        private final boolean push;

        Step(SokobanGame.Dir dir, boolean push) {
            this.dir = dir;
            this.push = push;
        }
    }

    /** 生成结果：关卡 + 一条保证可用的通关步骤。 */
    public static final class Generated {

        private final Level level;
        private final List<SokobanGame.Dir> solution;

        Generated(Level level, List<SokobanGame.Dir> solution) {
            this.level = level;
            this.solution = Collections.unmodifiableList(
                    new ArrayList<SokobanGame.Dir>(solution));
        }

        /** @return 生成的关卡。 */
        public Level getLevel() {
            return level;
        }

        /** @return 一条一定能通关的步骤序列（可能不是最短的）。 */
        public List<SokobanGame.Dir> getSolution() {
            return solution;
        }
    }

    /**
     * 该层属于第几次扩张。
     *
     * @param endlessNumber 无尽层号，从 1 开始
     * @return 0 表示基础尺寸
     */
    public int scaleFor(int endlessNumber) {
        int number = Math.max(1, endlessNumber);
        return (number - 1) / LEVELS_PER_EXPANSION;
    }

    /**
     * 该层的宽度。
     *
     * @param endlessNumber 无尽层号，从 1 开始
     * @return 列数
     */
    public int widthFor(int endlessNumber) {
        return Math.min(MAX_WIDTH, BASE_WIDTH + scaleFor(endlessNumber));
    }

    /**
     * 该层的高度。
     *
     * @param endlessNumber 无尽层号，从 1 开始
     * @return 行数
     */
    public int heightFor(int endlessNumber) {
        return Math.min(MAX_HEIGHT, BASE_HEIGHT + scaleFor(endlessNumber));
    }

    /**
     * 生成一层无尽关卡。
     *
     * @param endlessNumber 无尽层号，从 1 开始
     * @param random        随机源
     * @return 关卡与通关步骤
     */
    public Generated generate(int endlessNumber, Random random) {
        int number = Math.max(1, endlessNumber);
        Random rnd = random == null ? new Random() : random;
        int width = widthFor(number);
        int height = heightFor(number);
        int scale = scaleFor(number);

        int targetPulls = targetPullsFor(number);
        int targetMoves = targetMovesFor(number);
        int minPulls = Math.max(4, Math.min(8, targetPulls / 2));
        boolean strict = number > STRICT_FROM_LEVEL;

        if (strict) {
            return generateStrict(number, width, height, scale, targetPulls, minPulls, rnd);
        }
        return generateRelaxed(number, width, height, scale, targetPulls, minPulls, targetMoves, rnd);
    }

    /**
     * 20 层之后：既要求通关走法不多于 {@value #MAX_SOLUTIONS} 种，
     * 又要求最短解不少于 {@value #STRICT_MIN_MOVES} 步。
     *
     * <p>只卡“走法少”会出问题：迷宫走廊里很容易生成两步就能通关的水关，
     * 所以两个条件必须一起满足。为了避免个别地图把统计跑爆，这里设了总时间预算。</p>
     */
    private Generated generateStrict(int number, int width, int height, int scale,
                                     int targetPulls, int minPulls, Random rnd) {
        long deadline = System.nanoTime() + STRICT_BUDGET_NANOS;
        Generated easiestBest = null;
        int easiestMoves = -1;
        Generated fewestBest = null;
        int fewest = Integer.MAX_VALUE;
        Generated anyBest = null;
        int measured = 0;

        for (int attempt = 0; attempt < MAX_STRICT_ATTEMPTS && measured < MAX_STRICT_MEASUREMENTS; attempt++) {
            if (System.nanoTime() > deadline) {
                break;
            }
            Generated candidate = attempt(number, width, height, scale,
                    targetPulls, minPulls, true, rnd);
            if (candidate == null) {
                continue;
            }
            measured++;
            SolutionCounter.Result routes = SolutionCounter.count(candidate.getLevel(),
                    MAX_SOLUTIONS, SOLUTION_STATE_LIMIT, SOLUTION_PATH_LIMIT);
            if (anyBest == null) {
                anyBest = candidate;
            }
            if (!routes.isExact()) {
                continue;
            }
            if (routes.getCount() > MAX_SOLUTIONS) {
                if (routes.getCount() < fewest) {
                    fewest = routes.getCount();
                    fewestBest = candidate;
                }
                continue;
            }

            // 走法达标了，但还要够“有得想”：最短解不能太短
            List<SokobanGame.Dir> optimal =
                    Solver.solve(candidate.getLevel(), MEASURE_STATE_LIMIT);
            int moves = optimal == null ? Integer.MAX_VALUE : optimal.size();
            if (moves >= STRICT_MIN_MOVES) {
                return candidate;
            }
            if (moves > easiestMoves) {
                easiestMoves = moves;
                easiestBest = candidate;
            }
        }

        if (easiestBest != null) {
            return easiestBest;
        }
        if (fewestBest != null) {
            return fewestBest;
        }
        if (anyBest != null) {
            return anyBest;
        }
        return fallback(width, height);
    }

    /** 20 层及以前：用最短解衡量难度，挑最难的候选。 */
    private Generated generateRelaxed(int number, int width, int height, int scale,
                                      int targetPulls, int minPulls, int targetMoves,
                                      Random rnd) {
        // 几何质量（箱子离目标点足够远）已经在 attempt() 里作为硬性条件筛过了，
        // 这里只需要挑“最短解最长”的那个候选。
        Generated best = null;
        int bestMoves = -1;
        int measured = 0;

        for (int attempt = 0; attempt < MAX_ATTEMPTS && measured < MAX_MEASUREMENTS; attempt++) {
            Generated candidate = attempt(number, width, height, scale,
                    targetPulls, minPulls, false, rnd);
            if (candidate == null) {
                continue;
            }

            // 用最短解衡量难度：随机拉箱出来的局面时难时易，
            // 只看“拉了多少次”会被来回抵消的无效拉箱骗到。
            List<SokobanGame.Dir> optimal =
                    Solver.solve(candidate.getLevel(), MEASURE_STATE_LIMIT);
            measured++;

            if (optimal == null) {
                // 连求解器都算不动，说明局面足够复杂
                return candidate;
            }

            int moves = optimal.size();
            if (moves > bestMoves) {
                bestMoves = moves;
                best = candidate;
            }
            if (moves >= targetMoves) {
                return candidate;
            }
        }

        if (best != null) {
            return best;
        }
        return fallback(width, height);
    }

    /** 目标拉箱次数：层数越高，把箱子拉得越乱。 */
    private int targetPullsFor(int endlessNumber) {
        return Math.min(40, 10 + endlessNumber);
    }

    /** 期望的最短解步数：层数越高越难。 */
    private int targetMovesFor(int endlessNumber) {
        return Math.min(40, 8 + endlessNumber);
    }

    /** 该层的内部墙占比：20 层之后逐步加码，场地越来越紧。 */
    private double wallRatioFor(int endlessNumber) {
        double extra = Math.max(0, endlessNumber - 20) * WALL_RATIO_EXTRA_PER_LEVEL;
        return Math.min(WALL_RATIO_MAX, WALL_RATIO_BASE + extra);
    }

    /** 该层允许的最大箱子数。 */
    private static int maxBoxesFor(boolean strict) {
        return strict ? MAX_BOXES_STRICT : MAX_BOXES;
    }

    /**
     * 以“墙段游走”的方式铺墙：从随机点出发连续铺一小段，偶尔拐弯，
     * 形成走廊和拐角，而不是在一片空地上撒孤立柱子。
     *
     * <p>每铺一格都要求剩余空地仍然连通、并且不少于 {@code minOpen}。</p>
     */
    private static void carveWalls(boolean[][] wall, int width, int height,
                                   int wallTarget, int minOpen, Random random) {
        if (width < 5 || height < 5 || wallTarget <= 0) {
            return;
        }
        SokobanGame.Dir[] dirs = SokobanGame.Dir.values();
        int placed = 0;
        int attempts = 0;
        int attemptLimit = wallTarget * 60;
        while (placed < wallTarget && attempts++ < attemptLimit) {
            int x = 1 + random.nextInt(width - 2);
            int y = 1 + random.nextInt(height - 2);
            SokobanGame.Dir dir = dirs[random.nextInt(dirs.length)];
            int run = 1 + random.nextInt(5);
            for (int step = 0; step < run && placed < wallTarget; step++) {
                if (x <= 0 || y <= 0 || x >= width - 1 || y >= height - 1) {
                    break;
                }
                if (!wall[y][x]) {
                    wall[y][x] = true;
                    if (countOpen(wall, width, height) < minOpen
                            || !isConnected(wall, width, height)) {
                        wall[y][x] = false;
                        break;
                    }
                    placed++;
                }
                if (random.nextInt(3) == 0) {
                    dir = dirs[random.nextInt(dirs.length)];
                }
                x += dir.dx;
                y += dir.dy;
            }
        }
    }

    /**
     * 挖一个迷宫式走廊地图：内部先全部填成墙，再用递归回溯法在“房间格”之间挖出
     * 一格宽的通道，最后随机打通少量墙形成环路。
     *
     * <p>没有环路时玩家永远绕不到箱子另一侧，箱子只能单向推进；环路数量决定玩家
     * 有多少次“绕后”的机会，也就决定了通关走法的多少。</p>
     *
     * @param wall      输出：墙的分布
     * @param loopRatio 环路数量相对房间数的比例
     */
    private static void carveMaze(boolean[][] wall, int width, int height,
                                  double loopRatio, Random random) {
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                wall[y][x] = true;
            }
        }
        int roomCols = (width - 1) / 2;
        int roomRows = (height - 1) / 2;
        if (roomCols < 2 || roomRows < 2) {
            return;
        }

        boolean[] visited = new boolean[roomCols * roomRows];
        Deque<int[]> stack = new ArrayDeque<int[]>();
        List<int[]> options = new ArrayList<int[]>();
        int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

        int startCol = random.nextInt(roomCols);
        int startRow = random.nextInt(roomRows);
        visited[startRow * roomCols + startCol] = true;
        wall[1 + startRow * 2][1 + startCol * 2] = false;
        stack.push(new int[] {startCol, startRow});

        while (!stack.isEmpty()) {
            int[] current = stack.peek();
            options.clear();
            for (int[] step : steps) {
                int nc = current[0] + step[0];
                int nr = current[1] + step[1];
                if (nc < 0 || nr < 0 || nc >= roomCols || nr >= roomRows) {
                    continue;
                }
                if (visited[nr * roomCols + nc]) {
                    continue;
                }
                options.add(new int[] {nc, nr});
            }
            if (options.isEmpty()) {
                stack.pop();
                continue;
            }
            int[] next = options.get(random.nextInt(options.size()));
            int midX = 1 + current[0] * 2 + (next[0] - current[0]);
            int midY = 1 + current[1] * 2 + (next[1] - current[1]);
            wall[midY][midX] = false;
            wall[1 + next[1] * 2][1 + next[0] * 2] = false;
            visited[next[1] * roomCols + next[0]] = true;
            stack.push(next);
        }

        // 打通少量墙形成环路，给玩家留出“绕到箱子另一侧”的机会
        int loops = (int) Math.round(roomCols * roomRows * loopRatio);
        int guard = 0;
        while (loops > 0 && guard++ < loops * 60) {
            int x = 1 + random.nextInt(width - 2);
            int y = 1 + random.nextInt(height - 2);
            if (!wall[y][x]) {
                continue;
            }
            boolean horizontal = !wall[y][x - 1] && !wall[y][x + 1]
                    && wall[y - 1][x] && wall[y + 1][x];
            boolean vertical = !wall[y - 1][x] && !wall[y + 1][x]
                    && wall[y][x - 1] && wall[y][x + 1];
            if (horizontal || vertical) {
                wall[y][x] = false;
                loops--;
            }
        }
    }

    private static int countOpen(boolean[][] wall, int width, int height) {
        int open = 0;
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                if (!wall[y][x]) {
                    open++;
                }
            }
        }
        return open;
    }

    /* ---------------- 单次尝试 ---------------- */

    private Generated attempt(int number, int width, int height, int scale,
                              int targetPulls, int minPulls, boolean strict, Random random) {
        boolean[][] wall = new boolean[height][width];
        for (int x = 0; x < width; x++) {
            wall[0][x] = true;
            wall[height - 1][x] = true;
        }
        for (int y = 0; y < height; y++) {
            wall[y][0] = true;
            wall[y][width - 1] = true;
        }

        // 以“墙段游走”的方式铺墙：墙体连成串，形成走廊和拐角，
        // 而不是在一片空地上撒几根孤立柱子。这既能避免空旷场地，也能收紧箱子的活动余地。
        int interior = (width - 2) * (height - 2);
        int boxes = maxBoxesFor(strict);
        int boxCount = boxCountFor(scale, interior, boxes);
        if (strict) {
            // 20 层之后改用迷宫式走廊：通道只有一格宽，箱子几乎没法掉头，
            // 通关走法自然就被压到很少。
            carveMaze(wall, width, height, MAZE_LOOP_RATIO, random);
        } else {
            int minOpen = Math.max(boxCount * 5 + 6, (int) Math.round(interior * 0.42));
            int wallTarget = (int) Math.round(interior * wallRatioFor(number));
            carveWalls(wall, width, height, wallTarget, minOpen, random);
        }

        List<int[]> open = new ArrayList<int[]>();
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                if (!wall[y][x]) {
                    open.add(new int[] {x, y});
                }
            }
        }
        boxCount = boxCountFor(scale, open.size(), boxes);
        if (open.size() < boxCount + 3) {
            return null;
        }
        // 每个箱子都要被拉离它自己的目标点至少 MIN_BOX_TARGET_DISTANCE 步，
        // 而一次拉箱只能挪一格，所以所需的拉箱次数远多于“层号 + 10”这个基础值
        targetPulls = Math.min(200, Math.max(targetPulls, boxCount * 16 + 16));

        Collections.shuffle(open, random);
        boolean[][] goal = new boolean[height][width];
        boolean[][] box = new boolean[height][width];
        // 箱子编号（从 1 开始，0 表示没有箱子）。配对模式要求箱子是有身份的，
        // 每个箱子的专属目标点就是它出发时踩着的那个目标点。
        int[][] boxId = new int[height][width];
        int[] targets = new int[boxCount];
        // 目标点贴着随机一条边散开，把另一侧整片空间留给箱子，
        // 这样每个箱子才可能离自己的目标点足够远
        List<int[]> goalCells = chooseGoals(open, boxCount, width, height, random);
        if (goalCells == null) {
            return null;
        }
        for (int i = 0; i < boxCount; i++) {
            int[] cell = goalCells.get(i);
            goal[cell[1]][cell[0]] = true;
            box[cell[1]][cell[0]] = true;
            boxId[cell[1]][cell[0]] = i + 1;
            targets[i] = cell[1] * width + cell[0];
        }

        // 玩家从一个箱子的相邻格出发，保证第一次拉箱有可能发生
        List<int[]> starts = new ArrayList<int[]>();
        for (int i = 0; i < boxCount; i++) {
            int[] cell = goalCells.get(i);
            for (SokobanGame.Dir dir : SokobanGame.Dir.values()) {
                int nx = cell[0] + dir.dx;
                int ny = cell[1] + dir.dy;
                if (nx < 0 || ny < 0 || nx >= width || ny >= height) {
                    continue;
                }
                if (!wall[ny][nx] && !box[ny][nx]) {
                    starts.add(new int[] {nx, ny});
                }
            }
        }
        if (starts.isEmpty()) {
            return null;
        }
        int[] player = starts.get(random.nextInt(starts.size()));
        int px = player[0];
        int py = player[1];

        // 反向拉箱：每次拉箱的逆操作都是一次合法推箱。
        // 两次拉箱之间允许玩家自由走动（走动可逆，反向后依然是合法操作）；
        // 否则玩家一旦身边没有箱子，序列就会提前中断，生成出来的局面过于简单。
        List<Step> steps = new ArrayList<Step>();
        SokobanGame.Dir[] dirs = SokobanGame.Dir.values();
        // 状态禁忌表：避免“拉出去又拉回来”这类原地打转，让每次拉箱都真正打乱局面
        Set<String> visited = new HashSet<String>();
        visited.add(stateKey(box, py * width + px, width, height));
        int pulls = 0;
        for (int step = 0; step < targetPulls; step++) {
            boolean[] reachable = reachableCells(wall, box, width, height, px, py);
            // 还没全部达标时按分数贪心；全部达标后只在不破坏条件的前提下随机打乱
            boolean satisfied = minBoxTargetDistance(boxId, targets, width, height)
                    >= MIN_BOX_TARGET_DISTANCE;
            List<int[]> bestFresh = new ArrayList<int[]>();
            List<int[]> bestTies = new ArrayList<int[]>();
            List<int[]> keepFresh = new ArrayList<int[]>();
            List<int[]> keepTies = new ArrayList<int[]>();
            List<int[]> offGoal = new ArrayList<int[]>();
            List<int[]> candidates = new ArrayList<int[]>();
            int bestScore = Integer.MIN_VALUE;

            for (int by = 0; by < height; by++) {
                for (int bx = 0; bx < width; bx++) {
                    int movingId = boxId[by][bx];
                    if (movingId == 0) {
                        continue;
                    }
                    for (SokobanGame.Dir dir : dirs) {
                        int sx = bx - dir.dx;   // 玩家站位
                        int sy = by - dir.dy;
                        int tx = sx - dir.dx;   // 玩家退路
                        int ty = sy - dir.dy;
                        if (sx < 0 || sy < 0 || sx >= width || sy >= height) {
                            continue;
                        }
                        if (tx < 0 || ty < 0 || tx >= width || ty >= height) {
                            continue;
                        }
                        if (wall[sy][sx] || box[sy][sx] || wall[ty][tx] || box[ty][tx]) {
                            continue;
                        }
                        if (!reachable[sy * width + sx]) {
                            continue;
                        }
                        int[] move = new int[] {sx, sy, dir.ordinal()};
                        candidates.add(move);
                        // 箱子会被拉到玩家站位，落在目标点上容易退化成送分题
                        if (goal[sy][sx]) {
                            continue;
                        }
                        offGoal.add(move);
                        boolean fresh = !visited.contains(stateKeyAfter(box, width, height,
                                bx, by, sx, sy, tx, ty));

                        int[] stats = targetDistanceStatsAfter(boxId, targets, width, height,
                                movingId, bx, by, sx, sy);
                        if (satisfied) {
                            // 已经全部达标：只保留“拉完仍然全部达标”的走法
                            if (stats[0] >= MIN_BOX_TARGET_DISTANCE) {
                                keepTies.add(move);
                                if (fresh) {
                                    keepFresh.add(move);
                                }
                            }
                            continue;
                        }
                        // 分数 = 最小距离 * 1000 + 距离之和。
                        // 只用“最小距离”当分数会失效：只要还有箱子贴着目标点，
                        // 所有走法的最小距离都是同一个值，贪心就退化成随机了。
                        int score = stats[0] * 1000 + stats[1];
                        if (score > bestScore) {
                            bestScore = score;
                            bestTies.clear();
                            bestFresh.clear();
                        }
                        if (score == bestScore) {
                            bestTies.add(move);
                            if (fresh) {
                                bestFresh.add(move);
                            }
                        }
                    }
                }
            }

            List<int[]> pool;
            if (satisfied) {
                pool = !keepFresh.isEmpty() ? keepFresh
                        : (!keepTies.isEmpty() ? keepTies
                        : (!offGoal.isEmpty() ? offGoal : candidates));
            } else {
                pool = !bestFresh.isEmpty() ? bestFresh
                        : (!bestTies.isEmpty() ? bestTies
                        : (!offGoal.isEmpty() ? offGoal : candidates));
            }
            if (pool.isEmpty()) {
                break;
            }
            int[] chosen = pool.get(random.nextInt(pool.size()));
            SokobanGame.Dir dir = dirs[chosen[2]];

            List<SokobanGame.Dir> walk = pathTo(wall, box, width, height,
                    px, py, chosen[0], chosen[1]);
            if (walk == null) {
                continue;
            }
            for (SokobanGame.Dir w : walk) {
                steps.add(new Step(w, false));
                px += w.dx;
                py += w.dy;
            }

            int sourceX = px + dir.dx;
            int sourceY = py + dir.dy;
            int movedId = boxId[sourceY][sourceX];
            box[sourceY][sourceX] = false;
            boxId[sourceY][sourceX] = 0;
            box[py][px] = true;
            boxId[py][px] = movedId;
            px -= dir.dx;
            py -= dir.dy;
            steps.add(new Step(dir, true));
            visited.add(stateKey(box, py * width + px, width, height));
            pulls++;
        }

        if (pulls == 0) {
            return null;
        }
        // 便宜的预筛：拉箱次数太少的局面基本是送分题，直接丢掉，
        // 省下宝贵的 BFS 度量额度给更有希望的候选
        if (pulls < minPulls) {
            return null;
        }
        if (allBoxesOnOwnTargets(boxId, targets, width, height)) {
            // 拉完还全是已解状态，等于开局即通关，丢弃
            return null;
        }

        List<SokobanGame.Dir> solution = reverseToSolution(steps);

        // 按最终棋盘的“行优先”顺序整理配对关系，交给 Level 校验并保存
        int[] pairTargets = new int[boxCount];
        int pairIndex = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (boxId[y][x] != 0) {
                    pairTargets[pairIndex++] = targets[boxId[y][x] - 1];
                }
            }
        }

        String[] rows = toRows(wall, box, goal, px, py, width, height);
        Level level = new Level("无尽第 " + number + " 层", pairTargets, rows);

        // 自检：自带解法必须真的能逐步走到通关，否则丢弃这个候选。
        // 这是“必须保证有解”的最后一道防线。
        if (!solutionWorks(level, solution)) {
            return null;
        }
        // 每个箱子开局必须离它自己的目标点足够远
        if (!everyBoxFarFromTarget(level, MIN_BOX_TARGET_DISTANCE)) {
            return null;
        }
        return new Generated(level, solution);
    }

    /**
     * 选目标点：贴着随机一条边散开，另一侧整片空间留给箱子。
     *
     * <p>配对模式下每个箱子只需要离<b>自己的</b>目标点足够远，所以不必再把目标点挤成一团；
     * 但目标点仍然要待在某一侧，否则箱子无处可去（离自己目标点 {@value #MIN_BOX_TARGET_DISTANCE} 步
     * 在 {@code 8x7} 这类小地图上已经接近极限距离了）。</p>
     *
     * @param open   所有空格
     * @param count  目标点数量
     * @param width  宽
     * @param height 高
     * @param random 随机源
     * @return 目标点列表；凑不够时返回 {@code null}
     */
    private static List<int[]> chooseGoals(List<int[]> open, int count,
                                           int width, int height, Random random) {
        if (open.size() < count) {
            return null;
        }
        int side = random.nextInt(4);
        List<int[]> candidates = new ArrayList<int[]>();
        int limit = 1;
        while (candidates.size() < count && limit < Math.max(width, height)) {
            candidates.clear();
            for (int[] cell : open) {
                if (distanceToSide(cell, side, width, height) <= limit) {
                    candidates.add(cell);
                }
            }
            limit++;
        }
        if (candidates.size() < count) {
            return null;
        }
        Collections.shuffle(candidates, random);

        // 先挑互相隔开的，避免目标点紧挨在一起；不够再放宽
        List<int[]> chosen = new ArrayList<int[]>();
        for (int[] cell : candidates) {
            if (chosen.size() >= count) {
                break;
            }
            if (!tooClose(chosen, cell)) {
                chosen.add(cell);
            }
        }
        for (int[] cell : candidates) {
            if (chosen.size() >= count) {
                break;
            }
            if (!chosen.contains(cell)) {
                chosen.add(cell);
            }
        }
        return chosen.size() >= count ? chosen : null;
    }

    private static boolean tooClose(List<int[]> chosen, int[] cell) {
        for (int[] picked : chosen) {
            if (Math.abs(picked[0] - cell[0]) + Math.abs(picked[1] - cell[1]) < 2) {
                return true;
            }
        }
        return false;
    }

    /** 空格到指定边的距离（0 表示紧贴那条边）。 */
    private static int distanceToSide(int[] cell, int side, int width, int height) {
        switch (side) {
            case 0:
                return cell[0] - 1;
            case 1:
                return width - 2 - cell[0];
            case 2:
                return cell[1] - 1;
            default:
                return height - 2 - cell[1];
        }
    }

    /**
     * 检查每个箱子开局时到<b>它自己的专属目标点</b>的曼哈顿距离是否都不小于给定值。
     *
     * @param level       关卡
     * @param minDistance 距离下限
     * @return 全部达标返回 {@code true}
     */
    private static boolean everyBoxFarFromTarget(Level level, int minDistance) {
        int width = level.getWidth();
        int[] starts = level.getBoxStarts();
        for (int i = 0; i < starts.length; i++) {
            int target = level.getBoxTarget(i);
            int distance = Math.abs(starts[i] % width - target % width)
                    + Math.abs(starts[i] / width - target / width);
            if (distance < minDistance) {
                return false;
            }
        }
        return true;
    }

    /** 是否所有箱子都已经停在自己的目标点上。 */
    private static boolean allBoxesOnOwnTargets(int[][] boxId, int[] targets,
                                                int width, int height) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int id = boxId[y][x];
                if (id == 0) {
                    continue;
                }
                if (y * width + x != targets[id - 1]) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 当前所有箱子到“各自专属目标点”的距离统计。
     *
     * @return 长度为 2 的数组：{@code [最小距离, 距离之和]}
     */
    private static int[] targetDistanceStats(int[][] boxId, int[] targets,
                                             int width, int height) {
        int min = Integer.MAX_VALUE;
        int sum = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int id = boxId[y][x];
                if (id == 0) {
                    continue;
                }
                int target = targets[id - 1];
                int distance = Math.abs(x - target % width) + Math.abs(y - target / width);
                sum += distance;
                if (distance < min) {
                    min = distance;
                }
            }
        }
        return new int[] {min == Integer.MAX_VALUE ? 0 : min, sum};
    }

    /** 当前所有箱子到各自专属目标点的最小距离。 */
    private static int minBoxTargetDistance(int[][] boxId, int[] targets,
                                            int width, int height) {
        return targetDistanceStats(boxId, targets, width, height)[0];
    }

    /**
     * 假想“编号为 {@code movingId} 的箱子从 (fromX,fromY) 拉到 (toX,toY)”之后的距离统计。
     *
     * @return 长度为 2 的数组：{@code [最小距离, 距离之和]}
     */
    private static int[] targetDistanceStatsAfter(int[][] boxId, int[] targets,
                                                  int width, int height, int movingId,
                                                  int fromX, int fromY, int toX, int toY) {
        int target = targets[movingId - 1];
        int[] moved = new int[] {
            Math.abs(toX - target % width) + Math.abs(toY - target / width), 0
        };
        moved[1] = moved[0];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int id = boxId[y][x];
                if (id == 0 || (x == fromX && y == fromY)) {
                    continue;
                }
                int other = targets[id - 1];
                int distance = Math.abs(x - other % width) + Math.abs(y - other / width);
                moved[1] += distance;
                if (distance < moved[0]) {
                    moved[0] = distance;
                }
            }
        }
        return moved;
    }

    /**
     * 把“反向拉箱序列”倒过来变成一条正向通关步骤。
     *
     * <p>这里有个容易踩的坑：拉箱的逆操作是<b>同方向</b>的推箱，
     * 而走动的逆操作是<b>反方向</b>的走动。所以不能简单地把列表倒序，
     * 走动步骤的方向必须取反，否则生成的解法根本走不通。</p>
     *
     * @param steps 拉箱序列
     * @return 正向通关步骤
     */
    private static List<SokobanGame.Dir> reverseToSolution(List<Step> steps) {
        List<SokobanGame.Dir> solution = new ArrayList<SokobanGame.Dir>(steps.size());
        for (int i = steps.size() - 1; i >= 0; i--) {
            Step step = steps.get(i);
            solution.add(step.push ? step.dir : step.dir.opposite());
        }
        return solution;
    }

    /** 用真实的游戏规则回放一遍解法，确认它真的能通关。 */
    private static boolean solutionWorks(Level level, List<SokobanGame.Dir> solution) {
        SokobanGame game = new SokobanGame(Arrays.asList(level));
        for (SokobanGame.Dir dir : solution) {
            if (!game.move(dir)) {
                return false;
            }
        }
        return game.isWon();
    }

    private static String[] toRows(boolean[][] wall, boolean[][] box, boolean[][] goal,
                                   int playerX, int playerY, int width, int height) {
        char[][] grid = new char[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (wall[y][x]) {
                    grid[y][x] = Level.WALL;
                } else if (box[y][x] && goal[y][x]) {
                    grid[y][x] = Level.BOX_ON_GOAL;
                } else if (box[y][x]) {
                    grid[y][x] = Level.BOX;
                } else if (goal[y][x]) {
                    grid[y][x] = Level.GOAL;
                } else {
                    grid[y][x] = Level.FLOOR;
                }
            }
        }
        grid[playerY][playerX] = goal[playerY][playerX]
                ? Level.PLAYER_ON_GOAL : Level.PLAYER;

        String[] rows = new String[height];
        for (int y = 0; y < height; y++) {
            rows[y] = new String(grid[y]);
        }
        return rows;
    }

    private static int boxCountFor(int scale, int openSize, int maxBoxes) {
        int wanted = 2 + scale;
        int areaCap = Math.max(1, openSize / 6);
        return Math.max(1, Math.min(maxBoxes, Math.min(wanted, areaCap)));
    }

    /**
     * 局面签名：箱子集合 + 玩家位置。用于禁忌表判重。
     *
     * @param box    箱子分布
     * @param player 玩家位置（一维下标）
     * @return 签名
     */
    private static String stateKey(boolean[][] box, int player, int width, int height) {
        return boxesKey(box, width, height, -1, -1) + '|' + player;
    }

    /**
     * 假想“把 (fromX,fromY) 的箱子拉到 (toX,toY)”之后的局面签名。
     *
     * @return 签名
     */
    private static String stateKeyAfter(boolean[][] box, int width, int height,
                                        int fromX, int fromY, int toX, int toY,
                                        int playerX, int playerY) {
        return boxesKey(box, width, height, fromY * width + fromX, toY * width + toX)
                + '|' + (playerY * width + playerX);
    }

    private static String boxesKey(boolean[][] box, int width, int height,
                                   int remove, int add) {
        List<Integer> cells = new ArrayList<Integer>();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (box[y][x]) {
                    int cell = y * width + x;
                    if (cell != remove) {
                        cells.add(Integer.valueOf(cell));
                    }
                }
            }
        }
        if (add >= 0) {
            cells.add(Integer.valueOf(add));
        }
        Collections.sort(cells);
        StringBuilder sb = new StringBuilder(cells.size() * 4 + 2);
        for (Integer cell : cells) {
            sb.append(cell.intValue()).append(',');
        }
        return sb.toString();
    }

    /**
     * 玩家在不推动任何箱子的前提下，从当前位置能走到的所有格子。
     *
     * @param wall   墙
     * @param box    箱子（当作障碍）
     * @param width  宽
     * @param height 高
     * @param fromX  起点列
     * @param fromY  起点行
     * @return 长度 {@code width*height} 的可达标记
     */
    private static boolean[] reachableCells(boolean[][] wall, boolean[][] box,
                                            int width, int height, int fromX, int fromY) {
        boolean[] seen = new boolean[width * height];
        Deque<Integer> queue = new ArrayDeque<Integer>();
        int start = fromY * width + fromX;
        seen[start] = true;
        queue.add(Integer.valueOf(start));
        while (!queue.isEmpty()) {
            int current = queue.poll().intValue();
            int cx = current % width;
            int cy = current / width;
            for (SokobanGame.Dir dir : SokobanGame.Dir.values()) {
                int nx = cx + dir.dx;
                int ny = cy + dir.dy;
                if (nx < 0 || ny < 0 || nx >= width || ny >= height) {
                    continue;
                }
                if (wall[ny][nx] || box[ny][nx]) {
                    continue;
                }
                int next = ny * width + nx;
                if (seen[next]) {
                    continue;
                }
                seen[next] = true;
                queue.add(Integer.valueOf(next));
            }
        }
        return seen;
    }

    /**
     * 玩家绕过墙与箱子从起点走到终点的最短路径。
     *
     * @return 每一步的方向；不连通时返回 {@code null}
     */
    private static List<SokobanGame.Dir> pathTo(boolean[][] wall, boolean[][] box,
                                                int width, int height,
                                                int fromX, int fromY, int toX, int toY) {
        int start = fromY * width + fromX;
        int target = toY * width + toX;
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
                if (nx < 0 || ny < 0 || nx >= width || ny >= height) {
                    continue;
                }
                if (wall[ny][nx] || box[ny][nx]) {
                    continue;
                }
                int next = ny * width + nx;
                if (previous[next] != -2) {
                    continue;
                }
                previous[next] = current;
                queue.add(Integer.valueOf(next));
            }
        }
        if (previous[target] == -2) {
            return null;
        }

        LinkedList<SokobanGame.Dir> path = new LinkedList<SokobanGame.Dir>();
        int current = target;
        while (current != start) {
            int before = previous[current];
            path.addFirst(dirBetween(before % width, before / width,
                    current % width, current / width));
            current = before;
        }
        return path;
    }

    private static SokobanGame.Dir dirBetween(int fromX, int fromY, int toX, int toY) {
        for (SokobanGame.Dir dir : SokobanGame.Dir.values()) {
            if (dir.dx == toX - fromX && dir.dy == toY - fromY) {
                return dir;
            }
        }
        throw new IllegalArgumentException("两点不相邻");
    }

    /** 泛洪填充判断所有空地是否连通。 */
    private static boolean isConnected(boolean[][] wall, int width, int height) {
        int startX = -1;
        int startY = -1;
        int total = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (!wall[y][x]) {
                    total++;
                    if (startX < 0) {
                        startX = x;
                        startY = y;
                    }
                }
            }
        }
        if (total == 0) {
            return false;
        }

        boolean[][] seen = new boolean[height][width];
        Deque<int[]> stack = new ArrayDeque<int[]>();
        stack.push(new int[] {startX, startY});
        seen[startY][startX] = true;
        int count = 1;
        while (!stack.isEmpty()) {
            int[] cell = stack.pop();
            for (SokobanGame.Dir dir : SokobanGame.Dir.values()) {
                int nx = cell[0] + dir.dx;
                int ny = cell[1] + dir.dy;
                if (nx < 0 || ny < 0 || nx >= width || ny >= height) {
                    continue;
                }
                if (wall[ny][nx] || seen[ny][nx]) {
                    continue;
                }
                seen[ny][nx] = true;
                count++;
                stack.push(new int[] {nx, ny});
            }
        }
        return count == total;
    }

    /**
     * 极端兜底：万一随机尝试全部失败，给出一个必定可解、并且同样满足
     * “箱子离自己的目标点 >= {@value #MIN_BOX_TARGET_DISTANCE} 步”的极简关卡。
     *
     * @param width  宽度
     * @param height 高度
     * @return 关卡与一条通关步骤
     */
    private Generated fallback(int width, int height) {
        int w = Math.max(8, width);
        int h = Math.max(8, height);
        char[][] grid = new char[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean border = y == 0 || x == 0 || y == h - 1 || x == w - 1;
                grid[y][x] = border ? Level.WALL : Level.FLOOR;
            }
        }
        // 目标点在左上角，箱子在右下角，先往左推再往上推
        int goalX = 1;
        int goalY = 1;
        int boxX = w - 3;
        int boxY = h - 3;
        grid[goalY][goalX] = Level.GOAL;
        grid[boxY][boxX] = Level.BOX;
        grid[boxY][boxX + 1] = Level.PLAYER;

        String[] rows = new String[h];
        for (int y = 0; y < h; y++) {
            rows[y] = new String(grid[y]);
        }

        List<SokobanGame.Dir> solution = new ArrayList<SokobanGame.Dir>();
        for (int i = 0; i < boxX - goalX; i++) {
            solution.add(SokobanGame.Dir.LEFT);
        }
        // 绕到箱子下方再往上推
        solution.add(SokobanGame.Dir.DOWN);
        solution.add(SokobanGame.Dir.LEFT);
        for (int i = 0; i < boxY - goalY; i++) {
            solution.add(SokobanGame.Dir.UP);
        }
        int[] pairTargets = new int[] {goalY * w + goalX};
        return new Generated(new Level("无尽兜底关卡", pairTargets, rows), solution);
    }
}
