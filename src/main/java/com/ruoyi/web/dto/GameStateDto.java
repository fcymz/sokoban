package com.ruoyi.web.dto;

import java.util.List;

/**
 * 一局游戏的完整快照。
 *
 * <p>前端只靠这一个对象就能把界面画出来：棋盘 + 玩家/箱子位置 + 计分 + 状态标志。</p>
 *
 * @param sessionId     会话编号
 * @param level         当前关卡信息
 * @param levelIndex    当前关卡下标（从 0 开始）
 * @param builtInCount  内置关卡数量；下标大于等于它的都是无尽模式
 * @param endless       是否处于无尽模式
 * @param endlessNumber 无尽层号（从 1 开始）；非无尽模式为 0
 * @param shortProgress 进度短文本，例如 {@code 3/10} 或 {@code 无尽 12}
 * @param player        玩家位置
 * @param boxes         箱子位置，顺序与箱子身份一致
 * @param boxLabels     每个箱子的配对编号（从 1 开始，未配对模式为空）
 * @param boxOnTarget   每个箱子是否已经停在自己的目标点上
 * @param facing        玩家朝向：{@code UP} / {@code DOWN} / {@code LEFT} / {@code RIGHT}
 * @param steps         已走步数
 * @param pushes        已推箱次数
 * @param undoCount     还能撤销多少步
 * @param won           是否已经通关
 * @param deadlocked    是否已经确认死局
 * @param maxUnlocked   已解锁的最高关卡下标
 * @param canAdvance    当前是否允许进入下一关
 * @param endlessSkip   作弊开关是否打开（无尽模式可跳关）
 * @param seedCode      无尽关卡的种子展示码
 */
public record GameStateDto(
        String sessionId,
        LevelDto level,
        int levelIndex,
        int builtInCount,
        boolean endless,
        int endlessNumber,
        String shortProgress,
        int player,
        List<Integer> boxes,
        List<Integer> boxLabels,
        List<Boolean> boxOnTarget,
        String facing,
        int steps,
        int pushes,
        int undoCount,
        boolean won,
        boolean deadlocked,
        int maxUnlocked,
        boolean canAdvance,
        boolean endlessSkip,
        String seedCode) {
}
