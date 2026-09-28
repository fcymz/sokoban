package com.ruoyi.sokoban;

import java.util.Random;

/**
 * 种子编码。
 *
 * <h3>格式规范</h3>
 * <ul>
 *   <li>固定 <b>8 位</b>字符，取值范围是 {@code 0-9} 与 {@code A-Z}，
 *       但剔除容易看错的 {@code I}、{@code L}、{@code O}、{@code U}（共 32 个字符，正好 5 位一组）。</li>
 *   <li>展示时按 4 位一组、中间用短横线分开，例如 {@code 7K3M-9QPZ}。</li>
 *   <li>输入时忽略短横线与空格，也接受把 {@code I}、{@code L} 当成 {@code 1}、
 *       把 {@code O} 当成 {@code 0}，方便手抄和口述。</li>
 *   <li>8 位 × 5 位 = 40 位，正好藏进一个 long，编解码完全无损。</li>
 * </ul>
 */
public final class SeedCode {

    /** 种子字符个数。 */
    public static final int LENGTH = 8;

    /** 字符表：0-9 与 A-Z 去掉 I、L、O、U。 */
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    /** 40 位掩码。 */
    private static final long MASK = (1L << 40) - 1L;

    private SeedCode() {
    }

    /**
     * 把任意 long 规整成合法的种子值。
     *
     * <p>只保留低 40 位，这样 {@code parse(format(x)) == normalize(x)} 恒成立，
     * 界面上显示出来的种子一定能还原出同一套地图。</p>
     *
     * @param seed 原始种子
     * @return 规整后的种子
     */
    public static long normalize(long seed) {
        return seed & MASK;
    }

    /**
     * 生成一个随机种子。
     *
     * @return 规整后的随机种子
     */
    public static long randomSeed() {
        return normalize(new Random().nextLong());
    }

    /**
     * 把种子编码成可读字符串。
     *
     * @param seed 种子
     * @return 形如 {@code 7K3M-9QPZ} 的编码
     */
    public static String format(long seed) {
        long value = normalize(seed);
        char[] out = new char[LENGTH + 1];
        for (int i = 0; i < LENGTH; i++) {
            int shift = 5 * (LENGTH - 1 - i);
            out[i < 4 ? i : i + 1] = ALPHABET.charAt((int) ((value >>> shift) & 0x1FL));
        }
        out[4] = '-';
        return new String(out);
    }

    /**
     * 解析用户输入的种子。
     *
     * @param text 用户输入，可以带短横线、空格、大小写混排
     * @return 解析出的种子；格式非法时返回 {@code null}
     */
    public static Long parse(String text) {
        if (text == null) {
            return null;
        }
        StringBuilder cleaned = new StringBuilder(LENGTH);
        for (int i = 0; i < text.length(); i++) {
            char c = Character.toUpperCase(text.charAt(i));
            if (c == '-' || c == ' ' || c == '\t' || c == '_') {
                continue;
            }
            if (c == 'I' || c == 'L') {
                c = '1';
            } else if (c == 'O') {
                c = '0';
            }
            if (ALPHABET.indexOf(c) < 0) {
                return null;
            }
            cleaned.append(c);
            if (cleaned.length() > LENGTH) {
                return null;
            }
        }
        if (cleaned.length() != LENGTH) {
            return null;
        }
        long value = 0L;
        for (int i = 0; i < LENGTH; i++) {
            value = (value << 5) | ALPHABET.indexOf(cleaned.charAt(i));
        }
        return Long.valueOf(value);
    }

    /** @return 给界面用的格式说明。 */
    public static String hint() {
        return "种子格式：8 位字符（0-9 与 A-Z，不含 I / L / O / U），例如 7K3M-9QPZ";
    }
}
