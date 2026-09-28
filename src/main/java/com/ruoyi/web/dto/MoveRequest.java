package com.ruoyi.web.dto;

/**
 * 一次移动请求。
 *
 * @param dir 方向：{@code UP} / {@code DOWN} / {@code LEFT} / {@code RIGHT}
 */
public record MoveRequest(String dir) {
}
