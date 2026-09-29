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

    /** 一个会话：游戏状态 + 它的关卡来源 + “有没有改动没存档”的标记。 */
    private static final class Session {
        private final SokobanGame game;
        private final Campaign campaign;

        /**
         * 当前局面是否有改动还没存进任何槽位。
         *
         * <p>初始为 {@code false}：刚开的局、刚载入的关卡都是“干净的”，
         * 玩家此时退出不应该被问“要不要存档”。一旦推了箱子（或撤销、重来、被提示强制重来）
         * 就置为 {@code true}；存进任意一个槽位（含无尽模式的自动存档）之后置回 {@code false}。</p>
         */
        private boolean unsaved;

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

        boolean isUnsaved() {
            return unsaved;
        }

        /** 棋盘有改动，标记为“有未存档的进度”。 */
        void markUnsaved() {
            unsaved = true;
        }

        /** 当前局面已经落盘（或本来就是干净的），标记为“无未存档的进度”。 */
        void markSaved() {
            unsaved = false;
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
        // 把存档里记录的解锁进度带进来
        game.setMaxUnlockedLevel(saveData.getMaxUnlockedLevel());
        Session session = new Session(game, campaign);
        if (endless) {
            int target = campaign.getBuiltInCount();
            game.setMaxUnlockedLevel(Math.max(game.getMaxUnlockedLevel(), target));
            game.loadLevel(target);
            afterEnterLevel(session);
        }
        String id = UUID.randomUUID().toString();
        sessions.put(id, session);
        return stateOf(id, session);
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
        if (moved) {
            session.markUnsaved();
            if (session.game().isWon()) {
                recordWin(session);
            }
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
        if (session.game().undo()) {
            session.markUnsaved();
        }
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
        // 一步没走过就重来，棋盘没有变化，不算“有未存档的进度”
        boolean hadProgress = session.game().getSteps() > 0;
        session.game().reset();
        if (hadProgress) {
            session.markUnsaved();
        }
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
        afterEnterLevel(session);
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
        afterEnterLevel(session);
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
            session.markUnsaved();
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
     * <p>自动存档槽位只由系统写入（无尽模式进入新层、关标签页兜底），不接受手动存档 ——
     * 否则玩家手动存进去的内容会在下一次自动存档时被悄悄覆盖掉。</p>
     *
     * @param sessionId 会话编号
     * @param slot      槽位编号
     * @return 存完之后的槽位列表
     * @throws IllegalArgumentException 槽位编号非法，或想手动存进自动存档槽
     */
    public List<SaveSlotDto> save(String sessionId, int slot) {
        Session session = require(sessionId);
        checkSlot(slot);
        if (slot == SaveManager.AUTO_SLOT) {
            throw new IllegalArgumentException("第 " + (SaveManager.AUTO_SLOT + 1)
                    + " 个槽位是自动存档槽，不能手动存入；"
                    + "请选其它槽位，或把它「转存到…」别的槽位");
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
        if (saves.write(slot, data)) {
            session.markSaved();
        }
        return listSaves();
    }

    /**
     * 把某个会话的当前局面写进自动存档槽位（不关卡类型）。
     *
     * <p>给前端「关标签页兜底」用：玩家确认离开时页面已经要关了，只有 sendBeacon 还能发请求，
     * 所以单独开一个接口，避免和「不接受手动存档」那条规则打架。</p>
     *
     * @param sessionId 会话编号
     * @return 写完之后（并清掉未存档标记）的槽位列表
     */
    public List<SaveSlotDto> autoSave(String sessionId) {
        Session session = require(sessionId);
        writeAutoSlot(session);
        session.markSaved();
        return listSaves();
    }

    /**
     * 把一个槽位的存档转存到另一个槽位。
     *
     * <p>主要给自动存档槽用：无尽模式推进时 1 号槽位会被反复覆盖，
     * 玩家可以在这里把当前这份存到别的槽位长期保留。</p>
     *
     * <p>是「复制」而不是「搬走」：自动槽位的内容保持不动（它本来就是随时会被系统覆盖的），
     * 目标槽位原有的存档会被覆盖。保存时间沿用来源那份，方便看出这份局面是什么时候的。</p>
     *
     * @param from 来源槽位
     * @param to   目标槽位
     * @return 转存之后的槽位列表
     * @throws IllegalArgumentException 槽位非法、来源为空，或目标就是自动存档槽
     */
    public List<SaveSlotDto> copySave(int from, int to) {
        checkSlot(from);
        checkSlot(to);
        if (to == SaveManager.AUTO_SLOT) {
            throw new IllegalArgumentException("自动存档槽位只由系统写入，不能作为转存目标");
        }
        if (from == to) {
            throw new IllegalArgumentException("来源和目标不能是同一个槽位");
        }
        SaveSlot source = saves.read(from);
        if (!source.exists()) {
            throw new IllegalArgumentException("第 " + (from + 1) + " 个槽位还没有存档，"
                    + "没有可以转存的内容");
        }
        SaveSlot copy = SaveSlot.of(to,
                source.getLevelIndex(),
                source.getPlayer(),
                source.getBoxes(),
                source.getSteps(),
                source.getPushes(),
                source.getUnlocked(),
                source.getSeedBase(),
                source.getSavedAt());
        if (!saves.write(to, copy)) {
            throw new IllegalArgumentException("转存失败：存档目录写不进去");
        }
        return listSaves();
    }

    /**
     * 校验槽位编号。
     *
     * @param slot 槽位编号
     * @throws IllegalArgumentException 超出范围
     */
    private static void checkSlot(int slot) {
        if (slot < 0 || slot >= SaveManager.SLOT_COUNT) {
            throw new IllegalArgumentException("槽位编号必须在 0 ~ "
                    + (SaveManager.SLOT_COUNT - 1) + " 之间");
        }
    }

    /**
     * 从某个槽读档，并新建一个会话。
     *
     * @param slot 槽位编号
     * @return 读档之后的会话快照
     * @throws IllegalArgumentException 槽位非法或该槽没有存档
     */
    public GameStateDto loadSave(int slot) {
        checkSlot(slot);
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
        Session session = new Session(game, campaign);
        // 当前局面就是槽位里那一份，所以不算“有未存档的进度”
        session.markSaved();
        sessions.put(id, session);
        return stateOf(id, session);
    }

    /**
     * 删除某个槽。
     *
     * @param slot 槽位编号
     * @return 删除之后的槽位列表
     */
    public List<SaveSlotDto> deleteSave(int slot) {
        checkSlot(slot);
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
        return GameMapper.toState(sessionId, session.game(), session.campaign(),
                session.isUnsaved());
    }

    /**
     * 进入一个新关卡之后要做的事。
     *
     * <p>新关卡的棋盘是干净的（步数为 0、没有历史），所以先清掉“有未存档的进度”标记；
     * 无尽模式再顺手把这一层自动存进自动槽位，这样玩家推进无尽层数时进度天然是存过的。</p>
     *
     * @param session 会话
     */
    private void afterEnterLevel(Session session) {
        session.markSaved();
        if (session.game().isEndless()) {
            writeAutoSlot(session);
        }
    }

    /**
     * 把当前局面写进自动存档槽位。
     *
     * <p>完全容错：磁盘写不进去时只是这一次没存上，不影响关卡切换也不会报错
     * （下一次存档或退出提示会照常出现）。</p>
     *
     * @param session 会话
     */
    private void writeAutoSlot(Session session) {
        SokobanGame game = session.game();
        SaveSlot data = SaveSlot.of(SaveManager.AUTO_SLOT,
                game.getLevelIndex(),
                game.getPlayer(),
                game.getBoxes(),
                game.getSteps(),
                game.getPushes(),
                game.getMaxUnlockedLevel(),
                session.campaign().getSeedBase(),
                System.currentTimeMillis());
        saves.write(SaveManager.AUTO_SLOT, data);
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
