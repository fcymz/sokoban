package com.ruoyi.sokoban;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

/**
 * 存档槽的读写。
 *
 * <p>每个槽一个文件，放在用户目录下的 {@code .sokoban-saves/slot-N.properties}。
 * 所有读写异常都会被静默吞掉：即使目录只读、沙箱受限或磁盘写入失败，
 * 游戏也只是“存不下、读不出”，不会报错、不会弹窗。</p>
 */
public final class SaveManager {

    /** 槽位数量。 */
    public static final int SLOT_COUNT = 8;

    /** 自动存档占用的槽位。 */
    public static final int AUTO_SLOT = 0;

    /** 存档目录名。 */
    public static final String DIR_NAME = ".sokoban-saves";

    private static final String FILE_PREFIX = "slot-";
    private static final String FILE_SUFFIX = ".properties";

    private final File dir;

    /** 使用用户目录下的默认存档目录。 */
    public SaveManager() {
        this(defaultDir());
    }

    /**
     * 使用指定目录（便于测试）。
     *
     * @param dir 存档目录，可为 {@code null}
     */
    public SaveManager(File dir) {
        this.dir = dir;
    }

    private static File defaultDir() {
        try {
            String home = System.getProperty("user.home");
            if (home == null || home.trim().isEmpty()) {
                return null;
            }
            return new File(home, DIR_NAME);
        } catch (Throwable t) {
            return null;
        }
    }

    /** @return 存档目录；拿不到时为 {@code null}。 */
    public File getDirectory() {
        return dir;
    }

    /**
     * 读取一个槽。
     *
     * @param slot 槽位编号
     * @return 槽内容；没有存档或读不出来时返回空槽
     */
    public SaveSlot read(int slot) {
        if (dir == null || slot < 0 || slot >= SLOT_COUNT) {
            return SaveSlot.empty(slot);
        }
        File file = fileOf(slot);
        if (!file.isFile()) {
            return SaveSlot.empty(slot);
        }
        InputStream in = null;
        try {
            Properties props = new Properties();
            in = new FileInputStream(file);
            props.load(in);

            String boxesText = props.getProperty("boxes", "").trim();
            String[] parts = boxesText.isEmpty() ? new String[0] : boxesText.split(",");
            int[] boxes = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                boxes[i] = Integer.parseInt(parts[i].trim());
            }
            return SaveSlot.of(slot,
                    Integer.parseInt(props.getProperty("level", "0").trim()),
                    Integer.parseInt(props.getProperty("player", "0").trim()),
                    boxes,
                    Integer.parseInt(props.getProperty("steps", "0").trim()),
                    Integer.parseInt(props.getProperty("pushes", "0").trim()),
                    Integer.parseInt(props.getProperty("unlocked", "0").trim()),
                    Long.parseLong(props.getProperty("seed", "0").trim()),
                    Long.parseLong(props.getProperty("time", "0").trim()));
        } catch (Throwable t) {
            // 文件损坏一律当成空槽，不要让游戏起不来
            return SaveSlot.empty(slot);
        } finally {
            closeQuietly(in);
        }
    }

    /**
     * 写入一个槽。
     *
     * @param slot 槽位编号
     * @param data 存档内容
     * @return 写入成功返回 {@code true}
     */
    public boolean write(int slot, SaveSlot data) {
        if (dir == null || data == null || slot < 0 || slot >= SLOT_COUNT) {
            return false;
        }
        OutputStream out = null;
        try {
            if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory()) {
                return false;
            }
            StringBuilder boxes = new StringBuilder();
            int[] cells = data.getBoxes();
            for (int i = 0; i < cells.length; i++) {
                if (i > 0) {
                    boxes.append(',');
                }
                boxes.append(cells[i]);
            }

            Properties props = new Properties();
            props.setProperty("level", String.valueOf(data.getLevelIndex()));
            props.setProperty("player", String.valueOf(data.getPlayer()));
            props.setProperty("boxes", boxes.toString());
            props.setProperty("steps", String.valueOf(data.getSteps()));
            props.setProperty("pushes", String.valueOf(data.getPushes()));
            props.setProperty("unlocked", String.valueOf(data.getUnlocked()));
            props.setProperty("seed", String.valueOf(data.getSeedBase()));
            props.setProperty("time", String.valueOf(data.getSavedAt()));

            out = new FileOutputStream(fileOf(slot));
            props.store(out, "sokoban save slot " + slot);
            out.flush();
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            closeQuietly(out);
        }
    }

    /**
     * 删除一个槽。
     *
     * @param slot 槽位编号
     * @return 该槽原本有存档并删除成功返回 {@code true}
     */
    public boolean delete(int slot) {
        if (dir == null || slot < 0 || slot >= SLOT_COUNT) {
            return false;
        }
        File file = fileOf(slot);
        return file.isFile() && file.delete();
    }

    /**
     * 找出最近保存的那个槽。
     *
     * @return 最近存档；一个都没有时返回 {@code null}
     */
    public SaveSlot latest() {
        SaveSlot newest = null;
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            SaveSlot data = read(slot);
            if (!data.exists()) {
                continue;
            }
            if (newest == null || data.getSavedAt() > newest.getSavedAt()) {
                newest = data;
            }
        }
        return newest;
    }

    /** @return 是否一个存档都没有。 */
    public boolean isEmpty() {
        return latest() == null;
    }

    private File fileOf(int slot) {
        return new File(dir, FILE_PREFIX + slot + FILE_SUFFIX);
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
