package com.ruoyi.web.dto;

import java.util.List;

/**
 * 成绩榜：每关的最佳步数。
 *
 * <p>只有“通关过”的关卡才会有记录，所以 {@code entries} 通常比关卡总数少。</p>
 *
 * @param entries 各关成绩，按关卡下标升序
 * @param total   内置关卡数量（前端可以据此显示“x/10 关已有纪录”）
 */
public record ScoreBoardDto(List<ScoreEntryDto> entries, int total) {

    /**
     * 一关的成绩。
     *
     * @param levelIndex   关卡下标
     * @param title        关卡标题
     * @param shortProgress 进度短文本
     * @param bestSteps    最佳步数
     */
    public record ScoreEntryDto(
            int levelIndex,
            String title,
            String shortProgress,
            int bestSteps) {
    }
}
