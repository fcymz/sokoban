package com.ruoyi.web.dto;

import java.util.List;

/**
 * 一关的棋盘信息（前端渲染棋盘所需的全部静态数据）。
 *
 * <p>坐标统一用一维下标 {@code index = y * width + x}，前端按 {@code width} 换算行列。</p>
 *
 * @param name        关卡名
 * @param title       关卡标题，例如 {@code 第 3 关 · 穿廊}
 * @param width       列数
 * @param height      行数
 * @param walls       墙的格子下标
 * @param goals       目标点下标
 * @param paired      是否启用“箱子与目标点一一对应”
 * @param goalLabels  每个目标点的配对编号（下标即格子，值从 1 开始，0 表示没有编号）
 */
public record LevelDto(
        String name,
        String title,
        int width,
        int height,
        List<Integer> walls,
        List<Integer> goals,
        boolean paired,
        List<Integer> goalLabels) {
}
