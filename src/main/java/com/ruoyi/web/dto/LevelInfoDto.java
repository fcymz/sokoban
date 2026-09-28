package com.ruoyi.web.dto;

/**
 * 关卡列表里的一项。
 *
 * @param index         关卡下标（从 0 开始）
 * @param title         关卡标题
 * @param shortProgress 进度短文本
 * @param endless       是否属于无尽模式
 * @param endlessNumber 无尽层号；非无尽模式为 0
 * @param builtIn       是否是内置关卡
 * @param bestSteps     该关的历史最佳步数；还没有纪录时为 -1
 */
public record LevelInfoDto(
        int index,
        String title,
        String shortProgress,
        boolean endless,
        int endlessNumber,
        boolean builtIn,
        int bestSteps) {
}
