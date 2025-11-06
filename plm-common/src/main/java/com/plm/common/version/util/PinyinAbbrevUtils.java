package com.plm.common.version.util;

import net.sourceforge.pinyin4j.PinyinHelper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 拼音缩写工具：生成中文/混合字符串的首字母大写缩写；用于属性与 LOV 编码。
 * 规则：
 *  - 汉字：取第一个拼音方案，去除声调数字，取首字母并转大写。
 *  - 英文字母：取自身的大写。
 *  - 数字：直接保留（可用于区分例如 "版本2" -> V2）。
 *  - 其它符号：忽略。
 * 回退：若结果为空，使用 HASH 前 8 位作为缩写。
 */
public final class PinyinAbbrevUtils {
    private PinyinAbbrevUtils() {}

    public static String initials(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isHan(c)) {
                String[] arr = PinyinHelper.toHanyuPinyinStringArray(c);
                if (arr != null && arr.length > 0) {
                    String py = arr[0];
                    // 去掉声调数字与非字母
                    py = py.replaceAll("[^a-zA-Z]", "");
                    if (!py.isEmpty()) {
                        sb.append(Character.toUpperCase(py.charAt(0)));
                        continue;
                    }
                }
                // 汉字无法转换时占位 X
                sb.append('X');
            } else if (Character.isLetter(c)) {
                sb.append(Character.toUpperCase(c));
            } else if (Character.isDigit(c)) {
                sb.append(c); // 保留数字
            } else {
                // 忽略符号/空白
            }
        }
        if (sb.length() == 0) {
            return hash8(text); // 兜底
        }
        return sb.toString();
    }

    private static boolean isHan(char c) {
        // 基本汉字 & 拓展 (常用范围) 判断
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_C
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_D
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_E
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_F
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
    }

    private static String hash8(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(s.getBytes());
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 4; i++) { // 前 4 字节 = 8 hex
                int v = h[i] & 0xFF;
                hex.append(Integer.toHexString(v | 0x100).substring(1));
            }
            return hex.toString().toUpperCase();
        } catch (NoSuchAlgorithmException e) {
            return "HASHFAIL"; // 极少发生
        }
    }
}
