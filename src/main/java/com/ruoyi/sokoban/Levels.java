package com.ruoyi.sokoban;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 内置关卡表。
 *
 * <p>10 个关卡难度由易到难，全部经过广度优先搜索（BFS）验证有解，
 * 每关的最少步数见 {@code SokobanSelfTest} 的输出。</p>
 */
public final class Levels {

    private Levels() {
        // 工具类，不允许实例化
    }

    /**
     * 构建全部内置关卡。
     *
     * @return 只读的关卡列表
     */
    public static List<Level> createDefault() {
        List<Level> list = new ArrayList<Level>();

        list.add(new Level("入门",
                "#######",
                "#     #",
                "# $ . #",
                "#  @  #",
                "#     #",
                "#######"));

        list.add(new Level("双箱",
                "##########",
                "#        #",
                "#  $  .  #",
                "#   @    #",
                "#  $  .  #",
                "#        #",
                "##########"));

        list.add(new Level("穿廊",
                "#########",
                "#   #   #",
                "# $   . #",
                "#   #   #",
                "# @ #   #",
                "#########"));

        list.add(new Level("同推",
                "##########",
                "#        #",
                "#  .  .  #",
                "#        #",
                "#  $  $  #",
                "#   @    #",
                "#        #",
                "##########"));

        list.add(new Level("转角",
                "########",
                "#      #",
                "# .    #",
                "#  #   #",
                "#  $   #",
                "#  @   #",
                "#      #",
                "########"));

        list.add(new Level("密室",
                "##########",
                "#        #",
                "#  ##### #",
                "#  #   # #",
                "#  # $ # #",
                "#  #   # #",
                "#  ##.## #",
                "#   @    #",
                "##########"));

        list.add(new Level("三连",
                "###########",
                "#         #",
                "#  . . .  #",
                "#         #",
                "#  $ $ $  #",
                "#         #",
                "#    @    #",
                "###########"));

        list.add(new Level("绕行",
                "##########",
                "#    #   #",
                "# $  #   #",
                "#    #   #",
                "#  ###   #",
                "#      . #",
                "#  @     #",
                "##########"));

        list.add(new Level("夹道",
                "##########",
                "#  #     #",
                "#  # $   #",
                "#  #     #",
                "#  ###   #",
                "#      . #",
                "#  @     #",
                "##########"));

        list.add(new Level("慎推",
                "###########",
                "#         #",
                "#  ## ##  #",
                "#  #   #  #",
                "#  # $ #  #",
                "#  # . #  #",
                "#  ## ##  #",
                "#    @    #",
                "###########"));

        return Collections.unmodifiableList(list);
    }
}
