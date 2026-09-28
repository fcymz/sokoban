package com.ruoyi.web.dto;

/**
 * 一局游戏快照 + 这次请求附带的消息（提示文案、自动存档结果等）。
 *
 * @param state   游戏快照
 * @param message 给玩家看的提示文案，可为 {@code null}
 */
public record ActionResponse(GameStateDto state, String message) {

    /**
     * 只有快照、没有额外文案。
     *
     * @param state 游戏快照
     * @return 响应
     */
    public static ActionResponse of(GameStateDto state) {
        return new ActionResponse(state, null);
    }
}
