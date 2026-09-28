package com.ruoyi.web.dto;

/**
 * 存档槽信息。
 *
 * @param slot         槽位编号
 * @param exists       该槽是否有存档
 * @param levelIndex   关卡下标
 * @param title        关卡标题
 * @param shortProgress 进度短文本
 * @param steps        已走步数
 * @param pushes       已推箱次数
 * @param unlocked     该存档记录的解锁进度
 * @param seedCode     无尽关卡的种子展示码
 * @param savedAt      保存时间（毫秒时间戳），没有存档时为 0
 */
public record SaveSlotDto(
        int slot,
        boolean exists,
        int levelIndex,
        String title,
        String shortProgress,
        int steps,
        int pushes,
        int unlocked,
        String seedCode,
        long savedAt) {
}
