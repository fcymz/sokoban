package com.ruoyi.web.dto;

import java.util.List;

/**
 * 提示结果：一条解法（自动演示用）。
 *
 * @param plan    从当前局面起要走的步骤（方向名列表，如 {@code ["UP","LEFT"]}）
 * @param total   这一段计划的总步数
 * @param message 给玩家看的说明文案，可为 {@code null}
 * @param state   算完提示之后的最新局面（玩家可能因为“偏离解法”被自动重来）
 */
public record HintDto(List<String> plan, int total, String message, GameStateDto state) {
}
