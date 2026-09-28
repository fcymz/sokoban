package com.ruoyi.web.dto;

/**
 * 跳转 / 载入关卡请求。
 *
 * @param index 关卡下标（从 0 开始）
 * @param delta 相对偏移（例如 -1 表示上一关）；{@code index} 优先
 */
public record LevelChangeRequest(Integer index, Integer delta) {
}
