package com.ruoyi.web.dto;

/**
 * 开局请求。
 *
 * @param seedCode 种子展示码（{@code XXXX-XXXX} 形式）；为空表示随机开局
 * @param endless  是否直接从无尽第 1 层开始
 */
public record NewGameRequest(String seedCode, Boolean endless) {
}
