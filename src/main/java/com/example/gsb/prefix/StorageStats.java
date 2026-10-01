package com.example.gsb.prefix;

/**
 * 存储的键空间统计（仅统计键本身的编码开销，不含值的大小）。
 *
 * @param entryCount          条目数
 * @param averagePrefixLength 平均已保存前缀长度（块首锚点的前缀长度记为 0）
 * @param compressedBytes     前缀压缩后键数据的总字节数
 * @param uncompressedBytes   不做压缩、逐键完整存储时键数据的总字节数
 */
public record StorageStats(
        int entryCount,
        double averagePrefixLength,
        long compressedBytes,
        long uncompressedBytes) {

    /** 压缩后 / 未压缩 的字节比，小于 1 表示压缩有效。 */
    public double compressionRatio() {
        return uncompressedBytes == 0 ? 1.0 : (double) compressedBytes / uncompressedBytes;
    }

    /** 相比不压缩存储节省的空间百分比。 */
    public double spaceSavingPercent() {
        return (1.0 - compressionRatio()) * 100.0;
    }
}
