package com.example.gsb.prefix;

import java.nio.charset.StandardCharsets;

/**
 * 遍历存储时还原出的一条完整条目。
 *
 * @param key   还原后的完整键（调用方不得修改该数组）
 * @param value 关联的值
 * @param index 该条目在全局有序序列中的位置（从 0 开始），可用于定位原始条目
 */
public record StoreEntry<V>(byte[] key, V value, int index) {

    /** 以 UTF-8 解码键，便于日志与断言。 */
    public String keyAsUtf8() {
        return new String(key, StandardCharsets.UTF_8);
    }
}
