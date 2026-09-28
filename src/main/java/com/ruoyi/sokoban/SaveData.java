package com.ruoyi.sokoban;

import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

/**
 * 存档：只记录关卡解锁进度。
 *
 * <p>保存在用户目录下的 {@code .sokoban-save.properties}。所有读写异常都会被静默吞掉：
 * 即使目录只读、沙箱受限或磁盘写入失败，游戏也只是“记不住进度”，
 * 不会报错、不会弹窗、也不会刷日志。</p>
 *
 * <h3>为什么不记成绩</h3>
 * <p>这里曾经记录“每关最佳步数”，但这个指标本身就站不住脚：无尽关卡是按种子随机生成的，
 * 同一个「无尽第 21 层」在不同种子下是两张完全不同的地图，拿它们的步数互相比较没有意义。
 * 现在只保留解锁进度。</p>
 *
 * <p>旧存档文件里的 {@code best.*} 键会被<b>自动忽略</b>，既不会报错，
 * 也不会在下次保存时被写回（等于顺手清理掉）。</p>
 */
@Component
public final class SaveData {

    /** 默认存档文件名。 */
    public static final String DEFAULT_FILE_NAME = ".sokoban-save.properties";

    /** 旧版本用来记成绩的键前缀；现在只用于“读到就跳过”。 */
    private static final String LEGACY_KEY_BEST_PREFIX = "best.";

    private static final String KEY_UNLOCKED = "unlocked";

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

    /** 重置解锁进度：回到“只有第 1 关可进”，并删掉存档文件。 */
    public void resetAll() {
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
                }
                // 旧存档里的 best.* 一律忽略，保证旧文件也能正常读
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
