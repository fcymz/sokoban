package com.ruoyi.web;

import com.ruoyi.sokoban.Campaign;
import com.ruoyi.sokoban.Level;
import com.ruoyi.sokoban.SaveSlot;
import com.ruoyi.sokoban.SeedCode;
import com.ruoyi.sokoban.SokobanGame;
import com.ruoyi.web.dto.GameStateDto;
import com.ruoyi.web.dto.LevelDto;
import com.ruoyi.web.dto.LevelInfoDto;
import com.ruoyi.web.dto.SaveSlotDto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 把游戏内核的对象翻译成对外的 DTO。
 *
 * <p>内核用一维下标表示格子，这里原样透出（前端按 {@code width} 换算行列），
 * 不做任何坐标变换，避免两边各算一套导致对不上。</p>
 */
public final class GameMapper {

    private GameMapper() {
    }

    /**
     * 把当前局面映射成快照。
     *
     * @param sessionId 会话编号
     * @param game      游戏
     * @param campaign  关卡来源（用于取标题、进度、种子码）
     * @return 快照
     */
    public static GameStateDto toState(String sessionId, SokobanGame game, Campaign campaign) {
        int[] rawBoxes = game.getBoxes();
        List<Integer> boxes = new ArrayList<>(rawBoxes.length);
        List<Integer> boxLabels = new ArrayList<>(rawBoxes.length);
        List<Boolean> boxOnTarget = new ArrayList<>(rawBoxes.length);
        for (int i = 0; i < rawBoxes.length; i++) {
            boxes.add(rawBoxes[i]);
            boxLabels.add(game.getLevel().getBoxLabel(i));
            boxOnTarget.add(game.getLevel().isBoxPlaced(i, rawBoxes[i]));
        }
        return new GameStateDto(
                sessionId,
                toLevel(game.getLevel(), game.getLevelTitle()),
                game.getLevelIndex(),
                game.getBuiltInCount(),
                game.isEndless(),
                game.getEndlessNumber(),
                game.getShortProgress(),
                game.getPlayer(),
                Collections.unmodifiableList(boxes),
                Collections.unmodifiableList(boxLabels),
                Collections.unmodifiableList(boxOnTarget),
                game.getFacing().name(),
                game.getSteps(),
                game.getPushes(),
                game.getUndoCount(),
                game.isWon(),
                game.isDeadlocked(),
                game.getMaxUnlockedLevel(),
                game.canAdvance(),
                game.isEndlessSkipUnlocked(),
                campaign.getSeedCode());
    }

    /**
     * 映射关卡静态信息。
     *
     * @param level 关卡
     * @param title 标题
     * @return 关卡 DTO
     */
    public static LevelDto toLevel(Level level, String title) {
        List<Integer> walls = new ArrayList<>();
        List<Integer> goals = new ArrayList<>();
        int cells = level.getWidth() * level.getHeight();
        List<Integer> labels = new ArrayList<>(Collections.nCopies(cells, 0));
        for (int y = 0; y < level.getHeight(); y++) {
            for (int x = 0; x < level.getWidth(); x++) {
                int cell = y * level.getWidth() + x;
                if (level.isWall(x, y)) {
                    walls.add(cell);
                } else if (level.isGoal(cell)) {
                    goals.add(cell);
                    labels.set(cell, level.getGoalLabel(cell));
                }
            }
        }
        return new LevelDto(level.getName(), title, level.getWidth(), level.getHeight(),
                walls, goals, level.isPaired(), labels);
    }

    /**
     * 映射关卡列表项。
     *
     * @param campaign 关卡来源
     * @param index    关卡下标
     * @return 列表项
     */
    public static LevelInfoDto toLevelInfo(Campaign campaign, int index) {
        // 注意：内置关卡调用 getEndlessNumber 会抛异常，必须先判断是不是无尽关卡
        boolean endless = campaign.isEndless(index);
        return new LevelInfoDto(index,
                campaign.getTitle(index),
                campaign.getShortProgress(index),
                endless,
                endless ? campaign.getEndlessNumber(index) : 0,
                index < campaign.getBuiltInCount());
    }

    /**
     * 映射存档槽。
     *
     * @param data         槽内容
     * @param title        该存档关卡标题
     * @param shortProgress 该存档进度短文本
     * @return 存档槽 DTO
     */
    public static SaveSlotDto toSaveSlot(SaveSlot data, String title, String shortProgress) {
        return new SaveSlotDto(data.getIndex(), data.exists(), data.getLevelIndex(),
                title, shortProgress, data.getSteps(), data.getPushes(),
                data.getUnlocked(), SeedCode.format(data.getSeedBase()), data.getSavedAt());
    }
}
