package com.ruoyi.sokoban;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 无第三方依赖的自检程序，直接用 {@code main} 方法运行。
 *
 * <p>覆盖：关卡数据、可解性、游戏规则、关卡解锁门控、存档、
 * 求解器与提示接续、无尽模式随机关卡（尺寸扩张 + 保证有解）、界面渲染。</p>
 */
public final class SokobanSelfTest {

    private static int passed;
    private static int failed;

    private SokobanSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        List<Level> levels = Levels.createDefault();

        File outDir = new File("target");
        if (!outDir.exists()) {
            outDir.mkdirs();
        }

        section("关卡数据校验");
        check("内置关卡数量为 10", levels.size() == 10, "实际 " + levels.size());
        boolean countsOk = true;
        for (Level lv : levels) {
            countsOk &= lv.getBoxCount() == lv.getGoalCount();
        }
        check("每关箱子数与目标点数一致", countsOk);
        check("箱子数与目标点数不符时构造关卡会报错",
                throwsOn(new Runnable() {
                    @Override
                    public void run() {
                        new Level("非法关卡", "######", "#$$@.#", "######");
                    }
                }));
        check("缺少玩家起点时构造关卡会报错",
                throwsOn(new Runnable() {
                    @Override
                    public void run() {
                        new Level("缺玩家", "#####", "# $.#", "#####");
                    }
                }));

        section("关卡可解性（独立 BFS 求最少步数）");
        int[] expectedMin = {5, 14, 7, 10, 7, 9, 17, 18, 16, 7};
        for (int i = 0; i < levels.size(); i++) {
            int best = minMoves(levels.get(i), 600000);
            check(String.format("第 %2d 关 [%s] 有解，最少 %d 步",
                            i + 1, levels.get(i).getName(), best),
                    best == expectedMin[i], "期望 " + expectedMin[i] + "，实际 " + best);
        }

        section("求解器：返回的步骤要真的能通关");
        for (int i = 0; i < levels.size(); i++) {
            Level lv = levels.get(i);
            List<SokobanGame.Dir> solution = Solver.solve(lv, 600000);
            boolean replayed = replay(lv, solution);
            check(String.format("第 %2d 关 [%s] 解法可回放通关（%s 步）",
                            i + 1, lv.getName(), solution == null ? "-" : "" + solution.size()),
                    solution != null && replayed);
        }

        section("求解器：解法要尽量短");
        for (int i = 0; i < levels.size(); i++) {
            Level lv = levels.get(i);
            int optimal = minMoves(lv, 600000);
            List<SokobanGame.Dir> solution = Solver.solve(lv, 600000);
            check(String.format("第 %2d 关 [%s] 解法 %d 步，不差于最优解（%d 步）",
                            i + 1, lv.getName(), solution == null ? -1 : solution.size(), optimal),
                    solution != null && solution.size() <= optimal,
                    "解法 " + (solution == null ? "-" : "" + solution.size()) + " vs 最优 " + optimal);
        }

        Random hintRandom = new Random(123450L);
        EndlessGenerator hintGenerator = new EndlessGenerator();
        int worstRatio = 0;
        boolean allSolved = true;
        for (int number = 1; number <= 12; number++) {
            EndlessGenerator.Generated g = hintGenerator.generate(number, hintRandom);
            List<SokobanGame.Dir> known = g.getSolution();
            List<SokobanGame.Dir> solved = Solver.solve(g.getLevel(), 600000);
            if (solved == null) {
                allSolved = false;
                continue;
            }
            worstRatio = Math.max(worstRatio, solved.size() * 100 / Math.max(1, known.size()));
        }
        check("无尽关卡全部都能算出解法（不再退化成生成器的长解法）", allSolved);
        check("提示解法都不到生成器长解法的一半（最差 " + worstRatio + "%）",
                worstRatio < 50, "最差 " + worstRatio + "%");

        section("提示：从当前局面接续解法");
        Level first = levels.get(0);
        List<SokobanGame.Dir> firstSolution = Solver.solve(first);
        SokobanGame hintGame = new SokobanGame(levels);
        check("开局时可以从第 0 步完整接续",
                Solver.matchingPrefix(first, firstSolution,
                        hintGame.getPlayer(), hintGame.getBoxes()) == 0);
        hintGame.move(firstSolution.get(0));
        hintGame.move(firstSolution.get(1));
        check("走了两步后从第 2 步接续",
                Solver.matchingPrefix(first, firstSolution,
                        hintGame.getPlayer(), hintGame.getBoxes()) == 2);

        SokobanGame offPath = new SokobanGame(levels);
        offPath.move(SokobanGame.Dir.DOWN);
        offPath.move(SokobanGame.Dir.DOWN);
        check("偏离解法后返回 -1（提示会改为重来后完整演示）",
                Solver.matchingPrefix(first, firstSolution,
                        offPath.getPlayer(), offPath.getBoxes()) == -1);

        section("方向工具");
        check("上下的反方向互换",
                SokobanGame.Dir.UP.opposite() == SokobanGame.Dir.DOWN
                        && SokobanGame.Dir.DOWN.opposite() == SokobanGame.Dir.UP);
        check("左右的反方向互换",
                SokobanGame.Dir.LEFT.opposite() == SokobanGame.Dir.RIGHT
                        && SokobanGame.Dir.RIGHT.opposite() == SokobanGame.Dir.LEFT);
        check("取反两次回到原方向",
                SokobanGame.Dir.UP.opposite().opposite() == SokobanGame.Dir.UP);

        section("游戏规则：移动与阻挡");
        SokobanGame game = new SokobanGame(levels);
        check("初始步数为 0", game.getSteps() == 0);
        check("初始未通关", !game.isWon());
        check("初始没有可撤销的步骤", game.getUndoCount() == 0);
        check("传 null 方向不会抛异常", !game.move(null));
        check("向下走一步成功", game.move(SokobanGame.Dir.DOWN));
        check("步数累加到 1", game.getSteps() == 1, "steps=" + game.getSteps());
        check("撞墙时移动失败", !game.move(SokobanGame.Dir.DOWN));
        check("撞墙不计入步数", game.getSteps() == 1, "steps=" + game.getSteps());
        check("撤销成功", game.undo());
        check("撤销后步数归 0", game.getSteps() == 0, "steps=" + game.getSteps());
        check("没有历史时撤销返回 false", !game.undo());

        section("游戏规则：推箱与通关");
        game.reset();
        game.move(SokobanGame.Dir.LEFT);
        game.move(SokobanGame.Dir.LEFT);
        game.move(SokobanGame.Dir.UP);
        check("走到箱子左侧共 3 步", game.getSteps() == 3, "steps=" + game.getSteps());
        check("此时尚未推动箱子", game.getPushes() == 0);
        game.move(SokobanGame.Dir.RIGHT);
        check("推箱子使推动数 +1", game.getPushes() == 1, "pushes=" + game.getPushes());
        game.move(SokobanGame.Dir.RIGHT);
        check("箱子归位后判定通关", game.isWon());
        check("通关共 5 步", game.getSteps() == 5, "steps=" + game.getSteps());
        check("通关后移动被忽略", !game.move(SokobanGame.Dir.UP));
        check("通关后步数不再变化", game.getSteps() == 5, "steps=" + game.getSteps());

        section("死局判断：能确认的死局");
        SokobanGame corner = new SokobanGame(Arrays.asList(new Level("死角",
                "#####", "#$  #", "# @ #", "#  .#", "#####")));
        check("箱子被推进死角 -> 死局", corner.isDeadlocked());

        SokobanGame railRow = new SokobanGame(Arrays.asList(new Level("贴墙横排",
                "#######",
                "#  .  #",
                "#     #",
                "#@$   #",
                "#######")));
        check("箱子贴着下墙、目标点不在同一行 -> 死局", railRow.isDeadlocked());

        SokobanGame railColumn = new SokobanGame(Arrays.asList(new Level("贴墙竖列",
                "######",
                "#    #",
                "# .  #",
                "#    #",
                "#@   #",
                "#$   #",
                "######")));
        check("箱子贴着右墙、目标点不在同一列 -> 死局", railColumn.isDeadlocked());

        SokobanGame stuck = new SokobanGame(Arrays.asList(new Level("四个箱子互相顶死",
                "#########",
                "#....   #",
                "#  $$   #",
                "#  $$   #",
                "#   @   #",
                "#########")));
        check("所有箱子都推不动 -> 死局", stuck.isDeadlocked());

        SokobanGame notStuck = new SokobanGame(Arrays.asList(new Level("少一个箱子就推得动",
                "#########",
                "#...     #",
                "#  $$    #",
                "#  $     #",
                "#   @    #",
                "#########")));
        check("同样布局但有一个箱子推得动 -> 不是死局", !notStuck.isDeadlocked());

        section("死局判断：绝不误报");
        SokobanGame sameRow = new SokobanGame(Arrays.asList(new Level("同排可解",
                "#######",
                "#     #",
                "#     #",
                "#@$ . #",
                "#######")));
        check("箱子贴墙但目标点就在同一行 -> 不是死局", !sameRow.isDeadlocked());

        boolean anyFalsePositive = false;
        for (Level level : levels) {
            anyFalsePositive |= new SokobanGame(Arrays.asList(level)).isDeadlocked();
        }
        check("10 个内置关卡的开局都不会被判成死局", !anyFalsePositive);

        Random endlessDeadlock = new Random(606060L);
        EndlessGenerator deadlockGenerator = new EndlessGenerator();
        boolean endlessFalsePositive = false;
        for (int number = 1; number <= 12; number++) {
            Level lv = deadlockGenerator.generate(number, endlessDeadlock).getLevel();
            endlessFalsePositive |= new SokobanGame(Arrays.asList(lv)).isDeadlocked();
        }
        check("无尽关卡的开局也不会被判成死局（开局必定有解）", !endlessFalsePositive);

        section("死局判断：走死了能立刻发现，撤销后又能恢复");
        SokobanGame walkInto = new SokobanGame(Arrays.asList(new Level("推到底",
                "#######",
                "#     #",
                "# $ . #",
                "#  @  #",
                "#     #",
                "#######")));
        check("开局不是死局", !walkInto.isDeadlocked());
        walkInto.move(SokobanGame.Dir.UP);
        walkInto.move(SokobanGame.Dir.UP);
        walkInto.move(SokobanGame.Dir.LEFT);
        walkInto.move(SokobanGame.Dir.DOWN);
        walkInto.move(SokobanGame.Dir.DOWN);
        check("把箱子推到最底下一排后判定为死局", walkInto.isDeadlocked());
        check("此时还没通关", !walkInto.isWon());
        walkInto.undo();
        check("撤销一步后不再是死局", !walkInto.isDeadlocked());

        SokobanGame solvedGame = new SokobanGame(levels);
        solveFirstLevel(solvedGame);
        check("已经通关的局面不算死局", solvedGame.isWon() && !solvedGame.isDeadlocked());

        section("关卡解锁：前 10 关自由进入，限制只在无尽生效");
        SokobanGame nav = new SokobanGame(levels);
        check("开局就能进入任意一关前 10 关",
                nav.canEnter(0) && nav.canEnter(4) && nav.canEnter(9));
        check("可以直接跳到第 10 关", nav.loadLevel(9) && nav.getLevelIndex() == 9);
        check("第 10 关步数归零", nav.getSteps() == 0);
        check("前 10 关的“下一关”始终可用", nav.canAdvance());
        check("可以直接退回第 3 关", nav.loadLevel(2) && nav.getLevelIndex() == 2);
        check("回到第 1 关后不能再往前", nav.loadLevel(0) && !nav.changeLevel(-1));

        section("关卡解锁：无尽模式内部仍然要通关才能进下一层");
        SokobanGame deep = new SokobanGame(levels);
        check("开局即可直接进入无尽第 1 层", deep.canEnter(10) && deep.loadLevel(10));
        check("当前处于无尽模式", deep.isEndless());
        check("无尽层号为 1", deep.getEndlessNumber() == 1);
        check("未通关时不能进入无尽第 2 层", !deep.canEnter(11) && !deep.canAdvance());
        check("直接载入无尽第 2 层会被拒绝", !deep.loadLevel(11));
        check("被拒绝后仍停在第 1 层", deep.getLevelIndex() == 10);

        check("无尽第 1 层可以通关", solveCurrent(deep));
        check("通关后自动解锁无尽第 2 层", deep.getMaxUnlockedLevel() == 11);
        check("通关后 canAdvance 为 true", deep.canAdvance());
        check("可以进入无尽第 2 层", deep.changeLevel(1) && deep.getEndlessNumber() == 2);
        check("进入第 2 层后必须重新通关才能继续前进", !deep.canAdvance() && !deep.changeLevel(1));
        check("无尽第 3 层依然未解锁", !deep.canEnter(12));
        check("可以退回无尽第 1 层", deep.changeLevel(-1) && deep.getEndlessNumber() == 1);
        check("退回后仍要重新通关才能前进", !deep.canAdvance());

        section("关卡解锁：带着历史进度也不能在无尽里跳关");
        SokobanGame legacy = new SokobanGame(Campaign.createSeeded(7L));
        // 模拟旧存档：曾经通关到很后面，解锁阈值很高
        legacy.setMaxUnlockedLevel(33);
        check("可以进入无尽第 1 层", legacy.loadLevel(10));
        check("未通关时 canAdvance 为 false", !legacy.canAdvance());
        check("未通关时 changeLevel(+1) 被拒绝", !legacy.changeLevel(1));
        check("未通关时无法跳到更后面的无尽层", legacy.getLevelIndex() == 10);
        check("通关无尽第 1 层", solveCurrent(legacy));
        check("通关后可以进入下一层",
                legacy.canAdvance() && legacy.changeLevel(1) && legacy.getEndlessNumber() == 2);
        check("下一层未通关时再次禁止前进", !legacy.canAdvance() && !legacy.changeLevel(1));

        section("作弊码：上下左右左右上下");
        SokobanGame.Dir[] cheatSequence = {
            SokobanGame.Dir.UP, SokobanGame.Dir.DOWN,
            SokobanGame.Dir.LEFT, SokobanGame.Dir.RIGHT,
            SokobanGame.Dir.LEFT, SokobanGame.Dir.RIGHT,
            SokobanGame.Dir.UP, SokobanGame.Dir.DOWN
        };
        CheatCode code = new CheatCode();
        boolean premature = false;
        for (int i = 0; i < cheatSequence.length - 1; i++) {
            premature |= code.input(cheatSequence[i]);
        }
        check("只按前 7 下不会触发", !premature);
        check("按满 8 下触发作弊码",
                code.input(cheatSequence[cheatSequence.length - 1]));
        check("触发后记录被清空，可以再次触发",
                !code.input(SokobanGame.Dir.UP) && replayCheatSequence(code, cheatSequence));

        CheatCode wrong = new CheatCode();
        for (int i = 0; i < cheatSequence.length - 1; i++) {
            wrong.input(cheatSequence[i]);
        }
        check("最后一下按错不触发", !wrong.input(SokobanGame.Dir.LEFT));

        CheatCode sliding = new CheatCode();
        sliding.input(SokobanGame.Dir.UP);
        sliding.input(SokobanGame.Dir.UP);
        boolean hit = false;
        for (SokobanGame.Dir dir : cheatSequence) {
            hit = sliding.input(dir);
        }
        check("前面多按几下也能靠滑动窗口识别出来", hit);

        section("作弊效果：无尽模式可以跳关");
        SokobanGame cheatGame = new SokobanGame(Campaign.createSeeded(11L));
        check("先进入无尽第 1 层", cheatGame.loadLevel(levels.size()));
        check("未作弊时不能前进", !cheatGame.canAdvance() && !cheatGame.changeLevel(1));
        check("开启作弊改变了状态",
                cheatGame.setEndlessSkipUnlocked(true) && cheatGame.isEndlessSkipUnlocked());
        check("开启后可以前进", cheatGame.canAdvance());
        check("可以跳到无尽第 2 层",
                cheatGame.changeLevel(1) && cheatGame.getEndlessNumber() == 2);
        check("不用通关就能继续往前跳",
                cheatGame.changeLevel(1) && cheatGame.getEndlessNumber() == 3);
        check("可以直接载入很后面的无尽层", cheatGame.loadLevel(levels.size() + 20));
        check("重复开启不再改变状态", !cheatGame.setEndlessSkipUnlocked(true));
        check("关掉作弊恢复限制",
                cheatGame.setEndlessSkipUnlocked(false) && !cheatGame.isEndlessSkipUnlocked());
        check("关掉后未通关又不能前进", !cheatGame.canAdvance() && !cheatGame.changeLevel(1));

        // 把“按键 → 识别 → 解锁”整条链路串起来跑一遍（和窗口里的接法一致）
        SokobanGame wired = new SokobanGame(Campaign.createSeeded(3L));
        check("先进入无尽第 1 层", wired.loadLevel(levels.size()));
        CheatCode wiredCode = new CheatCode();
        boolean unlocked = false;
        for (SokobanGame.Dir dir : cheatSequence) {
            if (wiredCode.input(dir)) {
                unlocked = wired.setEndlessSkipUnlocked(true);
            }
        }
        check("按完序列后无尽模式立刻可以跳关",
                unlocked && wired.canAdvance() && wired.changeLevel(1));

        section("存档槽：写入 / 读取 / 删除");
        File saveDir = new File(outDir, "test-saves");
        purge(saveDir);
        SaveManager manager = new SaveManager(saveDir);
        check("初始没有任何存档", manager.latest() == null && manager.isEmpty());
        check("空槽读出来是空的", !manager.read(0).exists());

        SaveSlot written = SaveSlot.of(2, 5, 42, new int[] {1, 2, 3}, 17, 6, 9, 123456789L, 1000L);
        check("写入成功", manager.write(2, written));
        SaveSlot back = manager.read(2);
        check("关卡下标读回一致", back.getLevelIndex() == 5);
        check("玩家位置读回一致", back.getPlayer() == 42);
        check("箱子位置读回一致", Arrays.equals(back.getBoxes(), new int[] {1, 2, 3}));
        check("步数与推动数读回一致", back.getSteps() == 17 && back.getPushes() == 6);
        check("解锁进度读回一致", back.getUnlocked() == 9);
        check("随机种子读回一致", back.getSeedBase() == 123456789L);
        check("保存时间读回一致", back.getSavedAt() == 1000L);
        check("没写过的槽仍是空的", !manager.read(3).exists());
        check("latest 能找到唯一的存档",
                manager.latest() != null && manager.latest().getIndex() == 2);

        manager.write(5, SaveSlot.of(5, 7, 1, new int[] {4}, 3, 1, 8, 1L, 2000L));
        check("latest 返回最新的那个", manager.latest().getIndex() == 5);
        check("删除成功", manager.delete(5) && !manager.read(5).exists());
        check("重复删除返回 false", !manager.delete(5));
        check("拿不到存档目录时写入失败", !new SaveManager(null).write(0, written));
        check("拿不到存档目录时读取为空", !new SaveManager(null).read(0).exists());

        section("存档/读档：从上次的局面继续");
        SokobanGame original = new SokobanGame(Campaign.createSeeded(777L));
        original.loadLevel(3);
        original.move(SokobanGame.Dir.LEFT);   // 走到箱子下方
        original.move(SokobanGame.Dir.UP);     // 往上推一格
        original.move(SokobanGame.Dir.LEFT);
        int savedPlayer = original.getPlayer();
        int[] savedBoxes = original.getBoxes();
        int savedSteps = original.getSteps();
        int savedPushes = original.getPushes();
        check("存档前确实走动了", savedSteps == 3 && savedPushes > 0);

        SokobanGame loaded = new SokobanGame(Campaign.createSeeded(999L));
        loaded.setMaxUnlockedLevel(20);
        check("换一套种子后仍能恢复到这个局面",
                loaded.restore(3, savedPlayer, savedBoxes, savedSteps, savedPushes));
        check("关卡下标一致", loaded.getLevelIndex() == 3);
        check("玩家位置一致", loaded.getPlayer() == savedPlayer);
        check("箱子位置一致", Arrays.equals(loaded.getBoxes(), savedBoxes));
        check("步数与推动数一致",
                loaded.getSteps() == savedSteps && loaded.getPushes() == savedPushes);
        check("恢复后未判定为通关", !loaded.isWon());

        original.move(SokobanGame.Dir.RIGHT);
        loaded.move(SokobanGame.Dir.RIGHT);
        check("读档后继续操作，结果与原局面完全一致",
                original.getPlayer() == loaded.getPlayer()
                        && Arrays.equals(original.getBoxes(), loaded.getBoxes())
                        && original.getSteps() == loaded.getSteps());

        check("玩家落在墙上会被拒绝", !loaded.restore(3, 0, savedBoxes, 0, 0));
        check("箱子数量对不上会被拒绝",
                !loaded.restore(3, savedPlayer, new int[] {savedBoxes[0]}, 0, 0));
        check("两个箱子叠在一起会被拒绝",
                !loaded.restore(3, savedPlayer,
                        new int[] {savedBoxes[0], savedBoxes[0]}, 0, 0));
        check("恢复失败后局面没有被改坏",
                loaded.getPlayer() == original.getPlayer()
                        && Arrays.equals(loaded.getBoxes(), original.getBoxes()));

        section("无尽地图可复现（否则读档会读到另一张地图）");
        Campaign seedA = Campaign.createSeeded(4242L);
        Campaign seedB = Campaign.createSeeded(4242L);
        Campaign seedC = Campaign.createSeeded(9999L);
        check("同一个种子生成同一张地图",
                sameLevel(seedA.getLevel(12), seedB.getLevel(12)));
        check("不同种子生成不同地图",
                !sameLevel(seedA.getLevel(12), seedC.getLevel(12)));
        Level beforeReseed = seedA.getLevel(15);
        seedA.reseed(4242L);
        check("切回同一个种子后地图又变回来了",
                sameLevel(beforeReseed, seedA.getLevel(15)));

        section("种子格式：8 位、可读、可无损往返");
        long sample = 0xABCDEF1234L;
        String seedCode = SeedCode.format(sample);
        check("长度固定是 9（8 位 + 1 个短横线）", seedCode.length() == 9, seedCode);
        check("按 4 位一组用短横线分开", seedCode.charAt(4) == '-', seedCode);
        check("只用了规定字符集",
                seedCode.replace("-", "").matches("[0-9A-HJKMNP-TV-Z]{8}"), seedCode);

        long normalized = SeedCode.normalize(sample);
        check("同一个种子编码结果稳定",
                SeedCode.format(sample).equals(SeedCode.format(normalized)));
        check("编码后再解析能还原",
                SeedCode.parse(seedCode) != null
                        && SeedCode.parse(seedCode).longValue() == normalized,
                seedCode + " -> " + SeedCode.parse(seedCode));
        check("任意 long 编解码都无损",
                SeedCode.parse(SeedCode.format(Long.MIN_VALUE)).longValue()
                        == SeedCode.normalize(Long.MIN_VALUE));

        check("输入时忽略短横线与空格",
                SeedCode.parse("  " + seedCode + "  ") != null
                        && SeedCode.parse(seedCode.replace("-", "")).longValue() == normalized);
        check("大小写不敏感",
                SeedCode.parse(seedCode.toLowerCase()) != null
                        && SeedCode.parse(seedCode.toLowerCase()).longValue() == normalized);
        check("把 I / L 当成 1、把 O 当成 0",
                SeedCode.parse("IIII-IIII").longValue() == SeedCode.parse("1111-1111").longValue()
                        && SeedCode.parse("OOOO-OOOO").longValue()
                        == SeedCode.parse("0000-0000").longValue());
        check("位数不够会被拒绝", !SeedCode.isValid("7K3M"));
        check("位数太多会被拒绝", !SeedCode.isValid("7K3M9QPZ8"));
        check("用了被剔除的字母 U 会被拒绝", !SeedCode.isValid("UUUU-UUUU"));
        check("夹杂非法符号会被拒绝", !SeedCode.isValid("7K3M-9Q!Z"));
        check("null 会被拒绝", !SeedCode.isValid(null));

        section("种子：同一个种子必然生成同一套无尽关卡");
        Campaign worldA = Campaign.createSeeded(SeedCode.parse(seedCode).longValue());
        Campaign worldB = Campaign.createSeeded(SeedCode.parse(seedCode).longValue());
        Campaign worldC = Campaign.createSeeded(SeedCode.randomSeed());
        check("同一个种子：第 12 层一致", sameLevel(worldA.getLevel(12), worldB.getLevel(12)));
        check("同一个种子：第 23 层也一致", sameLevel(worldA.getLevel(23), worldB.getLevel(23)));
        check("不同种子：地图不同", !sameLevel(worldA.getLevel(12), worldC.getLevel(12)));
        check("关卡里能读出规范格式的种子", worldA.getSeedCode().equals(seedCode));
        check("随机种子也是合法的规范编码",
                SeedCode.parse(SeedCode.format(SeedCode.randomSeed())) != null);

        section("存档：成绩与解锁进度");
        File saveFile = new File(outDir, "test-save.properties");
        saveFile.delete();
        SaveData data = new SaveData(saveFile);
        check("初始没有成绩", data.getBest(0) == -1);
        check("初始进度为第 1 关", data.getMaxUnlockedLevel() == 0);
        check("首次提交成绩返回 true", data.submit(0, 12));
        check("更差的成绩不会覆盖", !data.submit(0, 20));
        check("更好的成绩会覆盖", data.submit(0, 7));
        check("可以读回成绩", data.getBest(0) == 7, "best=" + data.getBest(0));
        check("解锁进度只增不减", data.unlockLevel(5) && data.getMaxUnlockedLevel() == 5);
        check("更小的解锁值被忽略", !data.unlockLevel(2) && data.getMaxUnlockedLevel() == 5);

        SaveData reloaded = new SaveData(saveFile);
        check("重新读盘后成绩仍在", reloaded.getBest(0) == 7, "best=" + reloaded.getBest(0));
        check("重新读盘后进度仍在", reloaded.getMaxUnlockedLevel() == 5,
                "unlocked=" + reloaded.getMaxUnlockedLevel());
        reloaded.clearScores();
        check("清除成绩后没有记录了", reloaded.hasNoScores());
        check("清除成绩不会重置关卡进度", reloaded.getMaxUnlockedLevel() == 5);
        reloaded.resetAll();
        check("重置后进度归零", reloaded.getMaxUnlockedLevel() == 0);

        SaveData memoryOnly = new SaveData(null);
        check("拿不到存档文件时仍可记录", memoryOnly.submit(0, 9)
                && memoryOnly.getBest(0) == 9);
        check("拿不到存档文件时仍可记录进度", memoryOnly.unlockLevel(3)
                && memoryOnly.getMaxUnlockedLevel() == 3);

        section("无尽模式：地图每 5 层横竖各扩 1 格");
        EndlessGenerator generator = new EndlessGenerator();
        check("第 1 层为 10×9（基础尺寸）",
                generator.widthFor(1) == 10 && generator.heightFor(1) == 9);
        check("第 5 层仍为 10×9",
                generator.widthFor(5) == 10 && generator.heightFor(5) == 9);
        check("第 6 层扩为 11×10",
                generator.widthFor(6) == 11 && generator.heightFor(6) == 10);
        check("第 10 层仍为 11×10",
                generator.widthFor(10) == 11 && generator.heightFor(10) == 10);
        check("第 11 层扩为 12×11",
                generator.widthFor(11) == 12 && generator.heightFor(11) == 11);
        check("第 31 层扩为 16×15",
                generator.widthFor(31) == 16 && generator.heightFor(31) == 15);
        check("纵向横向扩展幅度一致（宽高同步 +1）", expansionIsSynchronized(generator));

        section("无尽模式：随机生成且自带解法必定可通关");
        Random seeded = new Random(20240607L);
        for (int number = 1; number <= 30; number++) {
            EndlessGenerator.Generated generated = generator.generate(number, seeded);
            Level lv = generated.getLevel();
            boolean sizeOk = lv.getWidth() == generator.widthFor(number)
                    && lv.getHeight() == generator.heightFor(number);
            boolean countsOk2 = lv.getBoxCount() == lv.getGoalCount();
            SokobanGame replay = new SokobanGame(Arrays.asList(lv));
            boolean notSolvedAtStart = !replay.isWon();
            for (SokobanGame.Dir dir : generated.getSolution()) {
                replay.move(dir);
            }
            boolean replayOk = replay.isWon();
            check(String.format("无尽第 %2d 层 %2d×%-2d 箱子 %d：尺寸/开局/解法",
                            number, lv.getWidth(), lv.getHeight(), lv.getBoxCount()),
                    sizeOk && countsOk2 && notSolvedAtStart && replayOk,
                    "size=" + sizeOk + " counts=" + countsOk2
                            + " notSolved=" + notSolvedAtStart + " replay=" + replayOk);
        }

        section("无尽模式：低层用独立 BFS 复核确实有解，并且不是送分题");
        Random seeded2 = new Random(778899L);
        for (int number = 1; number <= 5; number++) {
            Level lv = generator.generate(number, seeded2).getLevel();
            List<SokobanGame.Dir> best = Solver.solve(lv, 600000);
            boolean solved = best != null && replay(lv, best);
            check("无尽第 " + number + " 层 BFS 求到解"
                            + (best == null ? "" : "（最少 " + best.size() + " 步）")
                            + "，且不是几步就能解开的送分题",
                    solved && best.size() >= 8,
                    "solved=" + solved + " moves=" + (best == null ? "-" : best.size()));
        }

        section("无尽模式：生成过程不会拉出无意义的超长解法");
        Random seeded3 = new Random(13579L);
        int longest = 0;
        for (int number = 1; number <= 10; number++) {
            int len = generator.generate(number, seeded3).getSolution().size();
            longest = Math.max(longest, len);
        }
        // 提示用的是求解器算出的最短解，自带解法长一点无所谓，
        // 这里只是防它膨胀到不可收拾
        check("前 10 层自带解法都不超过 400 步（最长 " + longest + "）", longest <= 400,
                "最长 " + longest);

        section("无尽模式：地图不是空旷场地");
        Random seeded4 = new Random(555111L);
        double leastWalls = 1.0d;
        for (int number = 1; number <= 25; number++) {
            leastWalls = Math.min(leastWalls, wallRatio(generator.generate(number, seeded4).getLevel()));
        }
        check(String.format("前 25 层内部墙体占比都 >= 10%%（最低 %.0f%%）", leastWalls * 100),
                leastWalls >= 0.10d, "最低 " + leastWalls);

        section("无尽模式：箱子开局离“自己的”目标点至少 7 步");
        Random distanceRandom = new Random(314159L);
        int worstDistance = Integer.MAX_VALUE;
        boolean allPaired = true;
        for (int number = 1; number <= 25; number++) {
            Level lv = generator.generate(number, distanceRandom).getLevel();
            allPaired &= lv.isPaired();
            worstDistance = Math.min(worstDistance, ownTargetDistance(lv));
        }
        check("前 25 层都启用了箱子与目标点一一对应", allPaired);
        check("前 25 层每个箱子离自己的目标点都 >= 7（最低 " + worstDistance + "）",
                worstDistance >= 7, "最低 " + worstDistance);

        int strictWorst = Integer.MAX_VALUE;
        Random strictDistanceRandom = new Random(271828L);
        for (int number = 21; number <= 26; number++) {
            Level lv = generator.generate(number, strictDistanceRandom).getLevel();
            strictWorst = Math.min(strictWorst, ownTargetDistance(lv));
        }
        check("21~26 层同样满足距离下限（最低 " + strictWorst + "）", strictWorst >= 7,
                "最低 " + strictWorst);

        section("无尽模式：箱子与目标点一一对应");
        Level paired = generator.generate(1, new Random(4242L)).getLevel();
        check("配对数量等于箱子数", paired.getPairCount() == paired.getBoxCount());
        check("每个目标点各自对应一个箱子", distinctGoalLabels(paired));
        check("箱子有属于自己的编号", paired.getBoxLabel(0) == 1
                && paired.getBoxLabel(1) == 2);

        // 把两个箱子对调：即使都压在目标点上，配错了也不算通关
        int goalA = 1 * 8 + 5;
        int goalB = 1 * 8 + 6;
        Level crossed = new Level("对调测试", new int[] {goalB, goalA},
                "########",
                "#$$  ..#",
                "#  @   #",
                "########");
        check("两个箱子都压在目标点上、但配错了，不算通关",
                !crossed.isSolvedBy(new int[] {goalA, goalB}));
        check("只有各就各位才算通关",
                crossed.isSolvedBy(new int[] {goalB, goalA}));
        check("目标点上带着配对编号",
                crossed.getGoalLabel(goalB) == 1 && crossed.getGoalLabel(goalA) == 2);
        check("未配对的关卡沿用经典规则（任意目标点即可）",
                !levels.get(0).isPaired() && levels.get(0).isSolvedBy(new int[] {2 * 7 + 4}));

        section("走法统计：口径校验");
        SolutionCounter.Result single = SolutionCounter.count(
                new Level("直线", "#####", "#@$.#", "#####"), 50, 100000, 200000);
        check("只有一条路的关卡统计为 1 种走法",
                single.isExact() && single.getCount() == 1,
                "count=" + single.getCount() + " exact=" + single.isExact());

        SolutionCounter.Result roomy = SolutionCounter.count(
                levels.get(3), 100, 100000, 200000);
        check("空旷关卡走法明显超过 10 种",
                roomy.getCount() > 10, "count=" + roomy.getCount());

        int exactCount = 0;
        boolean allExactPositive = true;
        for (int i = 0; i < levels.size(); i++) {
            SolutionCounter.Result r = SolutionCounter.count(levels.get(i), 1000, 50000, 60000);
            if (!r.isExact()) {
                continue;
            }
            exactCount++;
            if (r.getCount() < 1) {
                allExactPositive = false;
            }
        }
        check("能统计完的内置关卡走法数都 >= 1（" + exactCount + "/" + levels.size()
                        + " 关可精确统计）",
                allExactPositive && exactCount >= 6,
                "exact=" + exactCount + " allPositive=" + allExactPositive);

        section("无尽模式：20 层之后箱子不少于 3 个，解法不多于 10 种，且不能是水关");
        Random strictRandom = new Random(24680L);
        for (int number = 21; number <= 26; number++) {
            EndlessGenerator.Generated g = generator.generate(number, strictRandom);
            Level lv = g.getLevel();
            boolean replayOk = replay(lv, g.getSolution());
            SolutionCounter.Result plans = SolutionCounter.countPlans(lv, 50, 400000);
            List<SokobanGame.Dir> optimal = Solver.solve(lv, 400000);
            boolean boxesOk = lv.getBoxCount() >= 3;
            boolean plansOk = plans.isExact() && plans.getCount() <= 10;
            boolean notTooEasy = optimal != null && optimal.size() >= 15;
            check(String.format(
                            "无尽第 %2d 层 %2d×%-2d：可通关 / 箱子 %d 个 / 解法 %s 种 / 最短解 %s 步",
                            number, lv.getWidth(), lv.getHeight(), lv.getBoxCount(),
                            plans.isExact() ? String.valueOf(plans.getCount()) : "?",
                            optimal == null ? "?" : String.valueOf(optimal.size())),
                    replayOk && boxesOk && plansOk && notTooEasy,
                    "replay=" + replayOk + " boxesOk=" + boxesOk
                            + " plansOk=" + plansOk + " notTooEasy=" + notTooEasy);
        }

        section("无尽模式：后 5 层比前 5 层更紧（墙体更多）");
        Random tightRandom = new Random(97531L);
        double early = 0;
        double late = 0;
        for (int number = 16; number <= 20; number++) {
            early += wallRatio(generator.generate(number, tightRandom).getLevel());
        }
        for (int number = 26; number <= 30; number++) {
            late += wallRatio(generator.generate(number, tightRandom).getLevel());
        }
        check(String.format("26~30 层墙体占比（%.0f%%）高于 16~20 层（%.0f%%）",
                        late / 5 * 100, early / 5 * 100),
                late > early, "early=" + early / 5 + " late=" + late / 5);

        section("无尽模式：同一层在本次运行内保持一致");
        Campaign campaign = Campaign.createSeeded(4242L);
        Level e1 = campaign.getLevel(campaign.getBuiltInCount());
        Level e1again = campaign.getLevel(campaign.getBuiltInCount());
        check("重复取同一层拿到的是同一张地图", e1 == e1again);
        check("无尽关卡标题正确", "无尽第 1 层".equals(
                campaign.getTitle(campaign.getBuiltInCount())));
        check("内置关卡标题正确", campaign.getTitle(0).startsWith("第 1 关"));

        section("局面文字快照");
        SokobanGame text = new SokobanGame(levels);
        String snapshot = text.toText();
        check("快照包含玩家符号 @", snapshot.indexOf('@') >= 0);
        check("快照包含箱子符号 $", snapshot.indexOf('$') >= 0);
        check("快照包含目标点符号 .", snapshot.indexOf('.') >= 0);
        check("快照行数与关卡高度一致",
                snapshot.split("\n", -1).length - 1 == text.getLevel().getHeight());

        section("界面渲染冒烟测试（导出 PNG）");
        SokobanGame shot = new SokobanGame(levels);
        check("第 1 关可以渲染并导出 PNG",
                renderToPng(shot, new File(outDir, "preview-level1.png")) > 1024);

        solveFirstLevel(shot);
        check("用于截图的第 1 关已通关", shot.isWon());
        check("通关结算界面可以渲染",
                renderToPng(shot, new File(outDir, "preview-win.png")) > 1024);

        SokobanGame big = new SokobanGame(levels);
        big.setMaxUnlockedLevel(levels.size());
        big.loadLevel(7);
        check("大尺寸内置关卡可以渲染",
                renderToPng(big, new File(outDir, "preview-level8.png")) > 1024);

        SokobanGame endlessShot = new SokobanGame(Campaign.createSeeded(99L));
        endlessShot.setMaxUnlockedLevel(endlessShot.getBuiltInCount() + 12);
        endlessShot.loadLevel(endlessShot.getBuiltInCount() + 5);
        check("无尽模式第 6 层可以渲染",
                renderToPng(endlessShot, new File(outDir, "preview-endless6.png")) > 1024);

        SokobanGame locked = new SokobanGame(Arrays.asList(new Level("死局",
                "#####", "#$  #", "# @ #", "#  .#", "#####")));
        check("死局提示条可以渲染",
                renderToPng(locked, new File(outDir, "preview-deadlock.png")) > 1024);

        System.out.println();
        System.out.println("结果：通过 " + passed + " 项，失败 " + failed + " 项");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /* ---------------- 辅助 ---------------- */

    /** 用一条解法回放整关，返回是否通关。 */
    private static boolean replay(Level level, List<SokobanGame.Dir> solution) {
        if (solution == null) {
            return false;
        }
        SokobanGame replay = new SokobanGame(Arrays.asList(level));
        for (SokobanGame.Dir dir : solution) {
            if (!replay.move(dir)) {
                return false;
            }
        }
        return replay.isWon();
    }

    /** 第 1 关（入门）的最短解：左、左、上、右、右。 */
    private static void solveFirstLevel(SokobanGame game) {
        game.reset();
        game.move(SokobanGame.Dir.LEFT);
        game.move(SokobanGame.Dir.LEFT);
        game.move(SokobanGame.Dir.UP);
        game.move(SokobanGame.Dir.RIGHT);
        game.move(SokobanGame.Dir.RIGHT);
    }

    /** 用求解器算出当前关卡的解法并执行完，返回是否通关。 */
    private static boolean solveCurrent(SokobanGame game) {
        List<SokobanGame.Dir> solution = Solver.solve(game.getLevel(), 600000);
        if (solution == null) {
            return false;
        }
        for (SokobanGame.Dir dir : solution) {
            if (!game.move(dir)) {
                return false;
            }
        }
        return game.isWon();
    }

    /** 关卡内部墙体的占比，用来衡量“是不是空旷场地”。 */
    private static double wallRatio(Level level) {
        int interior = 0;
        int walls = 0;
        for (int y = 1; y < level.getHeight() - 1; y++) {
            for (int x = 1; x < level.getWidth() - 1; x++) {
                interior++;
                if (level.isWall(x, y)) {
                    walls++;
                }
            }
        }
        return interior == 0 ? 0 : (double) walls / interior;
    }

    /** 两张地图是否完全相同（墙、目标点、箱子起点、玩家起点）。 */
    private static boolean sameLevel(Level a, Level b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            return false;
        }
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.isWall(x, y) != b.isWall(x, y)) {
                    return false;
                }
                int cell = y * a.getWidth() + x;
                if (a.isGoal(cell) != b.isGoal(cell)) {
                    return false;
                }
            }
        }
        return a.getPlayerStart() == b.getPlayerStart()
                && Arrays.equals(a.getBoxStarts(), b.getBoxStarts());
    }

    /** 递归删掉一个目录（测试用）。 */
    private static void purge(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                purge(child);
            }
        }
        file.delete();
    }

    /** 完整按一遍作弊码，返回最后一下是否触发。 */
    private static boolean replayCheatSequence(CheatCode code, SokobanGame.Dir[] sequence) {
        boolean hit = false;
        for (SokobanGame.Dir dir : sequence) {
            hit = code.input(dir);
        }
        return hit;
    }

    /** 每个箱子到“自己专属目标点”的曼哈顿距离中的最小值。 */
    private static int ownTargetDistance(Level level) {
        if (!level.isPaired()) {
            return -1;
        }
        int width = level.getWidth();
        int[] starts = level.getBoxStarts();
        int min = Integer.MAX_VALUE;
        for (int i = 0; i < starts.length; i++) {
            int target = level.getBoxTarget(i);
            min = Math.min(min, Math.abs(starts[i] % width - target % width)
                    + Math.abs(starts[i] / width - target / width));
        }
        return min == Integer.MAX_VALUE ? -1 : min;
    }

    /** 每个目标点的配对编号是否互不相同。 */
    private static boolean distinctGoalLabels(Level level) {
        int width = level.getWidth();
        int height = level.getHeight();
        Set<Integer> labels = new HashSet<Integer>();
        int count = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int label = level.getGoalLabel(y * width + x);
                if (label > 0) {
                    count++;
                    if (!labels.add(Integer.valueOf(label))) {
                        return false;
                    }
                }
            }
        }
        return count == level.getPairCount();
    }

    private static boolean expansionIsSynchronized(EndlessGenerator generator) {
        for (int n = 1; n <= 60; n++) {
            int scale = generator.scaleFor(n);
            if (scale > 16) {
                break; // 已到安全上限
            }
            if (generator.widthFor(n) != 10 + scale || generator.heightFor(n) != 9 + scale) {
                return false;
            }
        }
        return true;
    }

    private static boolean throwsOn(Runnable runnable) {
        try {
            runnable.run();
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static int renderToPng(SokobanGame game, File out) {
        try {
            GamePanel panel = new GamePanel(game);
            int w = 820;
            int h = 560;
            panel.setSize(w, h);
            BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            try {
                panel.paint(g);
            } finally {
                g.dispose();
            }
            ImageIO.write(image, "png", out);
            return (int) out.length();
        } catch (Throwable t) {
            return 0;
        }
    }

    /* ---------------- 独立 BFS（与 Solver 相互印证） ---------------- */

    private static int minMoves(Level level, int stateLimit) {
        int w = level.getWidth();
        int[] boxes = level.getBoxStarts();
        Arrays.sort(boxes);

        int[] start = new int[boxes.length + 1];
        start[0] = level.getPlayerStart();
        System.arraycopy(boxes, 0, start, 1, boxes.length);

        Set<String> seen = new HashSet<String>();
        seen.add(Arrays.toString(start));
        Deque<int[]> queue = new ArrayDeque<int[]>();
        queue.add(start);

        int depth = 0;
        while (!queue.isEmpty() && depth <= 500) {
            if (seen.size() > stateLimit) {
                return -1;
            }
            int size = queue.size();
            for (int i = 0; i < size; i++) {
                int[] cur = queue.poll();
                if (solved(level, cur)) {
                    return depth;
                }
                int px = cur[0] % w;
                int py = cur[0] / w;
                for (SokobanGame.Dir dir : SokobanGame.Dir.values()) {
                    int nx = px + dir.dx;
                    int ny = py + dir.dy;
                    if (level.isWall(nx, ny)) {
                        continue;
                    }
                    int target = ny * w + nx;
                    int boxIndex = -1;
                    for (int b = 1; b < cur.length; b++) {
                        if (cur[b] == target) {
                            boxIndex = b;
                            break;
                        }
                    }

                    // 必须克隆：若复用 cur，入队后继续遍历其它方向会改写同一个数组
                    int[] next = cur.clone();
                    if (boxIndex >= 0) {
                        int bx = nx + dir.dx;
                        int by = ny + dir.dy;
                        if (level.isWall(bx, by)) {
                            continue;
                        }
                        int beyond = by * w + bx;
                        boolean occupied = false;
                        for (int b = 1; b < cur.length; b++) {
                            if (cur[b] == beyond) {
                                occupied = true;
                                break;
                            }
                        }
                        if (occupied) {
                            continue;
                        }
                        next[boxIndex] = beyond;
                        Arrays.sort(next, 1, next.length);
                    }
                    next[0] = target;
                    if (seen.add(Arrays.toString(next))) {
                        queue.add(next);
                    }
                }
            }
            depth++;
        }
        return -1;
    }

    private static boolean solved(Level level, int[] state) {
        for (int i = 1; i < state.length; i++) {
            if (!level.isGoal(state[i])) {
                return false;
            }
        }
        return true;
    }

    /* ---------------- 断言 ---------------- */

    private static void section(String title) {
        System.out.println();
        System.out.println("== " + title + " ==");
    }

    private static void check(String label, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  [OK]   " + label);
        } else {
            failed++;
            System.out.println("  [FAIL] " + label + (detail == null ? "" : " -- " + detail));
        }
    }

    private static void check(String label, boolean ok) {
        check(label, ok, null);
    }
}
