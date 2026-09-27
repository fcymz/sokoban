package com.ruoyi.sokoban;

/**
 * 一个存档槽里的内容。
 *
 * <p>除了关卡编号，还记录了玩家和每个箱子的位置，所以读档可以从“上次存档时的那个局面”
 * 接着玩，而不是回到关卡开头。</p>
 *
 * <p>无尽关卡是随机生成的，所以还要带上 {@link #getSeedBase() 随机种子}，
 * 否则读档时会生成出另一张地图。</p>
 */
public final class SaveSlot {

    private final int index;
    private final boolean exists;
    private final int levelIndex;
    private final int player;
    private final int[] boxes;
    private final int steps;
    private final int pushes;
    private final int unlocked;
    private final long seedBase;
    private final long savedAt;

    private SaveSlot(int index, boolean exists, int levelIndex, int player, int[] boxes,
                     int steps, int pushes, int unlocked, long seedBase, long savedAt) {
        this.index = index;
        this.exists = exists;
        this.levelIndex = levelIndex;
        this.player = player;
        this.boxes = boxes;
        this.steps = steps;
        this.pushes = pushes;
        this.unlocked = unlocked;
        this.seedBase = seedBase;
        this.savedAt = savedAt;
    }

    /**
     * 构造一个空槽。
     *
     * @param index 槽位编号
     * @return 空存档
     */
    public static SaveSlot empty(int index) {
        return new SaveSlot(index, false, 0, 0, new int[0], 0, 0, 0, 0L, 0L);
    }

    /**
     * 构造一份存档。
     *
     * @param index      槽位编号
     * @param levelIndex 关卡下标
     * @param player     玩家位置
     * @param boxes      箱子位置，顺序与 {@link Level#getBoxStarts()} 一致
     * @param steps      已走步数
     * @param pushes     已推箱次数
     * @param unlocked   该存档的解锁进度
     * @param seedBase   无尽关卡的随机种子
     * @param savedAt    保存时间（毫秒时间戳）
     * @return 存档
     */
    public static SaveSlot of(int index, int levelIndex, int player, int[] boxes,
                              int steps, int pushes, int unlocked, long seedBase, long savedAt) {
        return new SaveSlot(index, true, levelIndex, player,
                boxes == null ? new int[0] : boxes.clone(),
                Math.max(0, steps), Math.max(0, pushes),
                Math.max(0, unlocked), seedBase, savedAt);
    }

    /** @return 槽位编号。 */
    public int getIndex() {
        return index;
    }

    /** @return 该槽是否有存档。 */
    public boolean exists() {
        return exists;
    }

    /** @return 关卡下标。 */
    public int getLevelIndex() {
        return levelIndex;
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

    /** @return 已推箱次数。 */
    public int getPushes() {
        return pushes;
    }

    /** @return 该存档记录的解锁进度。 */
    public int getUnlocked() {
        return unlocked;
    }

    /** @return 无尽关卡的随机种子。 */
    public long getSeedBase() {
        return seedBase;
    }

    /** @return 保存时间（毫秒时间戳）。 */
    public long getSavedAt() {
        return savedAt;
    }
}
