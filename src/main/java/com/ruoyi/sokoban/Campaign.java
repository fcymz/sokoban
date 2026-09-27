package com.ruoyi.sokoban;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 关卡来源：前若干关是内置关卡，之后的关卡由 {@link EndlessGenerator} 随机生成。
 *
 * <p>同一个下标只会生成一次并缓存，因此来回切关看到的始终是同一张地图，
 * 本次运行内保持一致（重启后无尽关卡会重新随机）。</p>
 */
public final class Campaign {

    private final List<Level> builtIn;
    private final EndlessGenerator generator;
    private final Random random;
    private final Map<Integer, EndlessGenerator.Generated> endless =
            new HashMap<Integer, EndlessGenerator.Generated>();

    /**
     * @param builtInLevels 内置关卡，不能为空
     * @param generator     无尽关卡生成器，为 {@code null} 时使用默认实现
     * @param random        随机源，为 {@code null} 时使用默认实现
     */
    public Campaign(List<Level> builtInLevels, EndlessGenerator generator, Random random) {
        if (builtInLevels == null || builtInLevels.isEmpty()) {
            throw new IllegalArgumentException("内置关卡不能为空");
        }
        this.builtIn = Collections.unmodifiableList(new ArrayList<Level>(builtInLevels));
        this.generator = generator == null ? new EndlessGenerator() : generator;
        this.random = random == null ? new Random() : random;
    }

    /**
     * 用固定随机种子创建战役（便于复现与测试）。
     *
     * @param seed 随机种子
     * @return 战役
     */
    public static Campaign createSeeded(long seed) {
        return new Campaign(Levels.createDefault(), new EndlessGenerator(), new Random(seed));
    }

    /**
     * 创建默认战役。
     *
     * @return 战役
     */
    public static Campaign createDefault() {
        return new Campaign(Levels.createDefault(), new EndlessGenerator(), new Random());
    }

    /** @return 内置关卡数量；下标大于等于它的都是无尽关卡。 */
    public int getBuiltInCount() {
        return builtIn.size();
    }

    /**
     * 判断某下标是否属于无尽模式。
     *
     * @param index 全局关卡下标
     * @return 是无尽关卡返回 {@code true}
     */
    public boolean isEndless(int index) {
        return index >= builtIn.size();
    }

    /**
     * 无尽层号（从 1 开始）。
     *
     * @param index 全局关卡下标
     * @return 层号
     */
    public int getEndlessNumber(int index) {
        if (!isEndless(index)) {
            throw new IllegalArgumentException("下标 " + index + " 不是无尽关卡");
        }
        return index - builtIn.size() + 1;
    }

    /**
     * 取关卡（无尽关卡按需生成并缓存）。
     *
     * @param index 全局关卡下标
     * @return 关卡
     */
    public Level getLevel(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("关卡下标不能为负数：" + index);
        }
        if (!isEndless(index)) {
            return builtIn.get(index);
        }
        return endlessFor(index).getLevel();
    }

    /**
     * 取该关的已知通关步骤（只有无尽关卡才有）。
     *
     * @param index 全局关卡下标
     * @return 通关步骤；内置关卡返回 {@code null}
     */
    public List<SokobanGame.Dir> getKnownSolution(int index) {
        if (!isEndless(index)) {
            return null;
        }
        return endlessFor(index).getSolution();
    }

    /**
     * 关卡标题。
     *
     * @param index 全局关卡下标
     * @return 例如 {@code 第 3 关 · 穿廊} 或 {@code 无尽第 12 层}
     */
    public String getTitle(int index) {
        if (!isEndless(index)) {
            return "第 " + (index + 1) + " 关 · " + builtIn.get(index).getName();
        }
        return "无尽第 " + getEndlessNumber(index) + " 层";
    }

    /**
     * 进度显示，例如 {@code 3/10} 或 {@code 无尽 12}。
     *
     * @param index 全局关卡下标
     * @return 简短进度文本
     */
    public String getShortProgress(int index) {
        if (!isEndless(index)) {
            return (index + 1) + "/" + builtIn.size();
        }
        return "无尽 " + getEndlessNumber(index);
    }

    private EndlessGenerator.Generated endlessFor(int index) {
        Integer key = Integer.valueOf(index);
        EndlessGenerator.Generated generated = endless.get(key);
        if (generated == null) {
            generated = generator.generate(getEndlessNumber(index), random);
            endless.put(key, generated);
        }
        return generated;
    }
}
