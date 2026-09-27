package com.ruoyi.sokoban;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 作弊码识别：记住玩家最近按过的一串方向，凑齐约定的序列就算触发。
 *
 * <p>当前序列是 <b>上 下 左 右 左 右 上 下</b>。</p>
 *
 * <p>和键盘绑定无关，只负责判断“这串方向是不是作弊码”，方便单独测试。</p>
 */
final class CheatCode {

    /** 触发作弊的方向序列。 */
    private static final SokobanGame.Dir[] SEQUENCE = {
        SokobanGame.Dir.UP,
        SokobanGame.Dir.DOWN,
        SokobanGame.Dir.LEFT,
        SokobanGame.Dir.RIGHT,
        SokobanGame.Dir.LEFT,
        SokobanGame.Dir.RIGHT,
        SokobanGame.Dir.UP,
        SokobanGame.Dir.DOWN
    };

    /** 最近按过的方向，只保留最后 {@link #SEQUENCE} 个。 */
    private final Deque<SokobanGame.Dir> recent = new ArrayDeque<SokobanGame.Dir>();

    /**
     * 记录一次方向输入。
     *
     * @param dir 玩家按下的方向
     * @return 正好凑齐作弊码返回 {@code true}（并清空已记录的方向，避免连按重复触发）
     */
    boolean input(SokobanGame.Dir dir) {
        if (dir == null) {
            return false;
        }
        recent.addLast(dir);
        while (recent.size() > SEQUENCE.length) {
            recent.removeFirst();
        }
        if (recent.size() < SEQUENCE.length) {
            return false;
        }
        int index = 0;
        for (SokobanGame.Dir recorded : recent) {
            if (recorded != SEQUENCE[index]) {
                return false;
            }
            index++;
        }
        recent.clear();
        return true;
    }

    /** 清空已记录的方向。 */
    void reset() {
        recent.clear();
    }

    /** @return 序列长度。 */
    int length() {
        return SEQUENCE.length;
    }
}
