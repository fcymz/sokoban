package com.ruoyi.sokoban;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 存档：每关最佳步数 + 已解锁到的关卡进度。
 *
 * <p>保存在用户目录下的 {@code .sokoban-save.properties}。所有读写异常都会被静默吞掉：
 * 即使目录只读、沙箱受限或磁盘写入失败，游戏也只是“记不住成绩、记不住进度”，
 * 不会报错、不会弹窗、也不会刷日志。</p>
 */
public final class SaveData {

    /** 默认存档文件名。 */
    public static final String DEFAULT_FILE_NAME = ".sokoban-save.properties";

    private static final String KEY_BEST_PREFIX = "best.";
    private static final String KEY_UNLOCKED = "unlocked";

    private final Map<Integer, Integer> best = new HashMap<Integer, Integer>();

    /** 已解锁的最高关卡下标；0 表示只有第 1 关可进。 */
    private int maxUnlockedLevel;

    /** 存档文件；为 {@code null} 表示拿不到用户目录，只能内存保存。 */
    private final File file;

    /** 使用用户目录下的默认存档文件。 */
    public SaveData() {
        this(defaultFile());
    }

    /**
     * 使用指定文件（便于测试）。
     *
     * @param file 存档文件，可为 {@code null}
     */
    public SaveData(File file) {
        this.file = file;
        if (file != null && file.isFile()) {
            load(file);
        }
    }

    private static File defaultFile() {
        try {
            String home = System.getProperty("user.home");
            if (home == null || home.trim().isEmpty()) {
                return null;
            }
            return new File(home, DEFAULT_FILE_NAME);
        } catch (Throwable t) {
            return null;
        }
    }

    /* ---------------- 最佳步数 ---------------- */

    /**
     * 读取某关的最佳步数。
     *
     * @param levelIndex 关卡下标
     * @return 最佳步数；没有记录时返回 -1
     */
    public int getBest(int levelIndex) {
        Integer value = best.get(Integer.valueOf(levelIndex));
        return value == null ? -1 : value.intValue();
    }

    /** @return 是否一条成绩记录都没有。 */
    public boolean hasNoScores() {
        return best.isEmpty();
    }

    /**
     * 记录成绩，仅在优于已有记录时写入。
     *
     * @param levelIndex 关卡下标
     * @param steps      本次步数
     * @return 刷新了纪录（或首次记录）返回 {@code true}
     */
    public boolean submit(int levelIndex, int steps) {
        int current = getBest(levelIndex);
        if (current >= 0 && steps >= current) {
            return false;
        }
        best.put(Integer.valueOf(levelIndex), Integer.valueOf(steps));
        save();
        return true;
    }

    /* ---------------- 关卡解锁进度 ---------------- */

    /**
     * 已解锁的最高关卡下标。
     *
     * @return 下标；0 表示只有第 1 关可进
     */
    public int getMaxUnlockedLevel() {
        return maxUnlockedLevel;
    }

    /**
     * 提升解锁进度，只增不减。
     *
     * @param levelIndex 新的最高可进入下标
     * @return 进度确实被推进返回 {@code true}
     */
    public boolean unlockLevel(int levelIndex) {
        if (levelIndex <= maxUnlockedLevel) {
            return false;
        }
        maxUnlockedLevel = levelIndex;
        save();
        return true;
    }

    /* ---------------- 清空 ---------------- */

    /** 只清空成绩记录，保留关卡进度。 */
    public void clearScores() {
        best.clear();
        save();
    }

    /** 清空成绩并重置关卡进度。 */
    public void resetAll() {
        best.clear();
        maxUnlockedLevel = 0;
        if (file != null && file.isFile() && !file.delete()) {
            save();
        }
    }

    /* ---------------- 文件读写（全部静默失败） ---------------- */

    private void load(File source) {
        InputStream in = null;
        try {
            Properties props = new Properties();
            in = new FileInputStream(source);
            props.load(in);
            for (String name : props.stringPropertyNames()) {
                if (KEY_UNLOCKED.equals(name)) {
                    maxUnlockedLevel = Math.max(0, parseInt(props.getProperty(name), 0));
                } else if (name.startsWith(KEY_BEST_PREFIX)) {
                    try {
                        int levelIndex = Integer.parseInt(name.substring(KEY_BEST_PREFIX.length()));
                        int steps = Integer.parseInt(props.getProperty(name).trim());
                        if (levelIndex >= 0 && steps >= 0) {
                            best.put(Integer.valueOf(levelIndex), Integer.valueOf(steps));
                        }
                    } catch (NumberFormatException ignored) {
                        // 跳过脏数据
                    }
                }
            }
        } catch (Throwable ignored) {
            // 读不到就当没有存档
        } finally {
            closeQuietly(in);
        }
    }

    private void save() {
        if (file == null) {
            return;
        }
        OutputStream out = null;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            Properties props = new Properties();
            props.setProperty(KEY_UNLOCKED, String.valueOf(maxUnlockedLevel));
            for (Map.Entry<Integer, Integer> entry : best.entrySet()) {
                props.setProperty(KEY_BEST_PREFIX + entry.getKey(),
                        String.valueOf(entry.getValue()));
            }
            out = new FileOutputStream(file);
            props.store(out, "sokoban save data");
            out.flush();
        } catch (Throwable ignored) {
            // 写不进去就只保留内存中的状态
        } finally {
            closeQuietly(out);
        }
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Throwable ignored) {
            // 忽略
        }
    }
}
