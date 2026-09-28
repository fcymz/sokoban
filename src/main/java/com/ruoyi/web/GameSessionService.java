package com.ruoyi.web;

import com.ruoyi.sokoban.Campaign;
import com.ruoyi.sokoban.SaveData;
import com.ruoyi.sokoban.SaveManager;
import com.ruoyi.sokoban.SaveSlot;
import com.ruoyi.sokoban.SeedCode;
import com.ruoyi.sokoban.SokobanGame;
import com.ruoyi.sokoban.Solver;
import com.ruoyi.web.dto.GameStateDto;
import com.ruoyi.web.dto.LevelInfoDto;
import com.ruoyi.web.dto.SaveSlotDto;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 游戏会话服务：把每一局游戏的状态放在服务端，前端只发指令、收快照。
 *
 * <p>这样做的好处是规则判定、关卡生成、求解器全部留在后端一处，
 * 前端不需要（也无法）自己算规则——前后端只通过 {@code /api/**} 的 JSON 交互。</p>
 *
 * <h3>会话与存档的关系</h3>
 * <ul>
 *   <li>会话（{@link SokobanGame}）是内存里的“正在玩的这一局”，带撤销历史；</li>
 *   <li>存档（{@link SaveManager}）是磁盘上的 8 个槽位，记录关卡下标、玩家与箱子位置、
 *       解锁进度和无尽种子；</li>
 *   <li>“继续游戏”＝取最近的一个存档槽恢复；“读档”＝按槽位恢复。</li>
 * </ul>
 */
@Service
public class GameSessionService {

    /** 每一关最多给求解器多少搜索规模（提示用）。 */
    private static final int HINT_SOLVER_LIMIT = 400000;

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final SaveManager saves = new SaveManager();
    private final SaveData saveData;

    /**
     * @param saveData 关卡解锁进度存档（Spring 单例，保证通关后进度立刻生效）
     */
    public GameSessionService(SaveData saveData) {
        this.saveData = saveData;
    }

    /** 一个会话：游戏状态 + 它的关卡来源。 */
    private static final class Session {
        private final SokobanGame game;
        private final Campaign campaign;

        Session(SokobanGame game, Campaign campaign) {
            this.game = game;
            this.campaign = campaign;
        }

        SokobanGame game() {
            return game;
        }

        Campaign campaign() {
            return campaign;
        }
    }

    /* ---------------- 会话生命周期 ---------------- */

    /**
     * 开一局新游戏。
     *
     * @param seedCode 种子展示码；为空表示随机种子
     * @param endless  是否直接从无尽第 1 层开始
     * @return 新的游戏快照
     */
    public GameStateDto createSession(String seedCode, boolean endless) {
        Campaign campaign = buildCampaign(seedCode);
        SokobanGame game = new SokobanGame(campaign);
        // 把历史最好成绩里记录的解锁进度带进来
        game.setMaxUnlockedLevel(saveData.getMaxUnlockedLevel());
        if (endless) {
            int target = campaign.getBuiltInCount();
            game.setMaxUnlockedLevel(Math.max(game.getMaxUnlockedLevel(), target));
            game.loadLevel(target);
        }
        String id = UUID.randomUUID().toString();
        sessions.put(id, new Session(game, campaign));
        return stateOf(id, new Session(game, campaign));
    }

    /**
     * 读取会话。
     *
     * @param sessionId 会话编号
     * @return 会话
     * @throws SessionNotFoundException 会话不存在
     */
    private Session require(String sessionId) {
        Session session = sessions.get(sessionId);
        if (session == null) {
            throw new SessionNotFoundException(sessionId);
        }
        return session;
    }

    /**
     * 取一局的当前快照。
     *
     * @param sessionId 会话编号
     * @return 游戏快照
     */
    public GameStateDto state(String sessionId) {
        Session session = require(sessionId);
        return stateOf(sessionId, session);
    }

    /**
     * 结束一局（前端离开时调用）。
     *
     * @param sessionId 会话编号
     * @return 确实删掉了返回 {@code true}
     */
    public boolean closeSession(String sessionId) {
        return sessions.remove(sessionId) != null;
    }

    /* ---------------- 游戏操作 ---------------- */

    /**
     * 走一步。
     *
     * @param sessionId 会话编号
     * @param dirName   方向名：{@code UP} / {@code DOWN} / {@code LEFT} / {@code RIGHT}
     * @return 走完之后的快照
     * @throws IllegalArgumentException 方向名非法
     */
    public GameStateDto move(String sessionId, String dirName) {
        Session session = require(sessionId);
        SokobanGame.Dir dir = parseDir(dirName);
        boolean moved = session.game().move(dir);
        if (moved && session.game().isWon()) {
            recordWin(session);
        }
        return stateOf(sessionId, session);
    }

    /**
     * 撤销一步。
     *
     * @param sessionId 会话编号
     * @return 撤销之后的快照
     */
    public GameStateDto undo(String sessionId) {
        Session session = require(sessionId);
        session.game().undo();
        return stateOf(sessionId, session);
    }

    /**
     * 重玩本关。
     *
     * @param sessionId 会话编号
     * @return 重开之后的快照
     */
    public GameStateDto reset(String sessionId) {
        Session session = require(sessionId);
        session.game().reset();
        return stateOf(sessionId, session);
    }

    /**
     * 按绝对下标载入关卡。
     *
     * @param sessionId 会话编号
     * @param index     关卡下标
     * @return 载入之后的快照
     * @throws LevelLockedException 关卡尚未解锁
     */
    public GameStateDto loadLevel(String sessionId, int index) {
        Session session = require(sessionId);
        if (!session.game().loadLevel(index)) {
            throw new LevelLockedException(index);
        }
        return stateOf(sessionId, session);
    }

    /**
     * 相对切换关卡。
     *
     * @param sessionId 会话编号
     * @param delta     偏移量
     * @return 切换之后的快照
     * @throws LevelLockedException 目标关卡不允许进入
     */
    public GameStateDto changeLevel(String sessionId, int delta) {
        Session session = require(sessionId);
        if (!session.game().changeLevel(delta)) {
            throw new LevelLockedException(session.game().getLevelIndex() + delta);
        }
        return stateOf(sessionId, session);
    }

    /**
     * 切换“无尽模式跳关”作弊开关。
     *
     * @param sessionId 会话编号
     * @param unlocked  是否打开
     * @return 切换之后的快照
     */
    public GameStateDto setEndlessSkip(String sessionId, boolean unlocked) {
        Session session = require(sessionId);
        session.game().setEndlessSkipUnlocked(unlocked);
        return stateOf(sessionId, session);
    }

    /**
     * 算一条提示：优先用求解器现算最短解，算不动时退回生成器自带的那条。
     *
     * @param sessionId 会话编号
     * @return 提示结果：完整解法 + 从当前局面起该走的那一段
     */
    public HintResult hint(String sessionId) {
        Session session = require(sessionId);
        SokobanGame game = session.game();
        List<SokobanGame.Dir> solution =
                Solver.solve(game.getLevel(), HINT_SOLVER_LIMIT);
        if (solution == null || solution.isEmpty()) {
            solution = session.campaign().getKnownSolution(game.getLevelIndex());
        }
        if (solution == null || solution.isEmpty()) {
            return new HintResult(List.of(), 0, "没有找到这一关的解法");
        }
        int matched = Solver.matchingPrefix(game.getLevel(), solution,
                game.getPlayer(), game.getBoxes());
        if (matched < 0) {
            // 玩家已经偏离解法：从关卡开头完整演示
            game.reset();
            return new HintResult(names(solution), solution.size(),
                    "当前局面已偏离解法，已重来并完整演示");
        }
        List<SokobanGame.Dir> plan = new ArrayList<>(solution.subList(matched, solution.size()));
        return new HintResult(names(plan), plan.size(),
                plan.isEmpty() ? "已经到达终点状态" : null);
    }

    /* ---------------- 存档 ---------------- */

    /**
     * 列出全部存档槽。
     *
     * @return 槽位列表（长度固定为 {@link SaveManager#SLOT_COUNT}）
     */
    public List<SaveSlotDto> listSaves() {
        List<SaveSlotDto> result = new ArrayList<>(SaveManager.SLOT_COUNT);
        for (int slot = 0; slot < SaveManager.SLOT_COUNT; slot++) {
            SaveSlot data = saves.read(slot);
            result.add(GameMapper.toSaveSlot(data,
                    titleOf(data.getLevelIndex()),
                    progressOf(data.getLevelIndex())));
        }
        return result;
    }

    /**
     * 把当前局面存进某个槽。
     *
     * @param sessionId 会话编号
     * @param slot      槽位编号
     * @return 存完之后的槽位列表
     * @throws IllegalArgumentException 槽位编号非法
     */
    public List<SaveSlotDto> save(String sessionId, int slot) {
        Session session = require(sessionId);
        if (slot < 0 || slot >= SaveManager.SLOT_COUNT) {
            throw new IllegalArgumentException("槽位编号必须在 0 ~ "
                    + (SaveManager.SLOT_COUNT - 1) + " 之间");
        }
        SokobanGame game = session.game();
        SaveSlot data = SaveSlot.of(slot,
                game.getLevelIndex(),
                game.getPlayer(),
                game.getBoxes(),
                game.getSteps(),
                game.getPushes(),
                game.getMaxUnlockedLevel(),
                session.campaign().getSeedBase(),
                System.currentTimeMillis());
        saves.write(slot, data);
        return listSaves();
    }

    /**
     * 从某个槽读档，并新建一个会话。
     *
     * @param slot 槽位编号
     * @return 读档之后的会话快照
     * @throws IllegalArgumentException 槽位非法或该槽没有存档
     */
    public GameStateDto loadSave(int slot) {
        if (slot < 0 || slot >= SaveManager.SLOT_COUNT) {
            throw new IllegalArgumentException("槽位编号必须在 0 ~ "
                    + (SaveManager.SLOT_COUNT - 1) + " 之间");
        }
        SaveSlot data = saves.read(slot);
        if (!data.exists()) {
            throw new IllegalArgumentException("第 " + (slot + 1) + " 个槽位还没有存档");
        }
        Campaign campaign = Campaign.createSeeded(data.getSeedBase());
        SokobanGame game = new SokobanGame(campaign);
        game.setMaxUnlockedLevel(data.getUnlocked());
        if (!game.restore(data.getLevelIndex(), data.getPlayer(), data.getBoxes(),
                data.getSteps(), data.getPushes())) {
            // 存档与当前关卡结构对不上（比如换了版本），退回该关开头
            game.loadLevel(data.getLevelIndex());
        }
        String id = UUID.randomUUID().toString();
        sessions.put(id, new Session(game, campaign));
        return stateOf(id, new Session(game, campaign));
    }

    /**
     * 删除某个槽。
     *
     * @param slot 槽位编号
     * @return 删除之后的槽位列表
     */
    public List<SaveSlotDto> deleteSave(int slot) {
        saves.delete(slot);
        return listSaves();
    }

    /* ---------------- 关卡信息 ---------------- */

    /**
     * 列出内置关卡（无尽关卡按需求生成，不预先枚举）。
     *
     * @return 内置关卡列表
     */
    public List<LevelInfoDto> listBuiltInLevels() {
        Campaign campaign = Campaign.createDefault();
        List<LevelInfoDto> result = new ArrayList<>();
        for (int i = 0; i < campaign.getBuiltInCount(); i++) {
            result.add(GameMapper.toLevelInfo(campaign, i));
        }
        return result;
    }

    /* ---------------- 内部工具 ---------------- */

    /**
     * 把会话映射成快照。
     *
     * @param sessionId 会话编号
     * @param session   会话
     * @return 快照
     */
    private GameStateDto stateOf(String sessionId, Session session) {
        return GameMapper.toState(sessionId, session.game(), session.campaign());
    }

    private Campaign buildCampaign(String seedCode) {
        long seed = seedBaseOf(seedCode);
        return seed == 0L ? Campaign.createDefault() : Campaign.createSeeded(seed);
    }

    private long seedBaseOf(String seedCode) {
        if (seedCode == null || seedCode.trim().isEmpty()) {
            return 0L;
        }
        Long parsed = SeedCode.parse(seedCode);
        if (parsed == null) {
            throw new IllegalArgumentException("种子格式不对，正确写法示例："
                    + SeedCode.format(SeedCode.randomSeed()));
        }
        return parsed.longValue();
    }

    private void recordWin(Session session) {
        // 无尽关卡的难度由种子决定，跨种子的步数没有可比性，因此只记录解锁进度
        saveData.unlockLevel(session.game().getMaxUnlockedLevel());
    }

    private String titleOf(int levelIndex) {
        return Campaign.createDefault().getTitle(levelIndex);
    }

    private String progressOf(int levelIndex) {
        return Campaign.createDefault().getShortProgress(levelIndex);
    }

    private static SokobanGame.Dir parseDir(String dirName) {
        if (dirName == null) {
            throw new IllegalArgumentException("方向不能为空");
        }
        try {
            return SokobanGame.Dir.valueOf(dirName.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("方向只能是 UP / DOWN / LEFT / RIGHT");
        }
    }

    private static List<String> names(List<SokobanGame.Dir> dirs) {
        List<String> result = new ArrayList<>(dirs.size());
        for (SokobanGame.Dir dir : dirs) {
            result.add(dir.name());
        }
        return result;
    }

    /**
     * 提示结果。
     *
     * @param plan    从当前局面起要走的步骤（方向名列表）
     * @param total   可选步骤总数
     * @param message 给玩家看的说明文案，可为 {@code null}
     */
    public record HintResult(List<String> plan, int total, String message) {
    }

    /** 会话不存在。 */
    public static class SessionNotFoundException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * @param sessionId 会话编号
         */
        public SessionNotFoundException(String sessionId) {
            super("找不到游戏会话：" + sessionId);
        }
    }

    /** 关卡未解锁。 */
    public static class LevelLockedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * @param index 关卡下标
         */
        public LevelLockedException(int index) {
            super("第 " + (index + 1) + " 关还没解锁：无尽模式必须先通过当前这一层");
        }
    }
}
