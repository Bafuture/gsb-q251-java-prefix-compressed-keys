package com.example.gsb.prefix;

import java.util.List;
import java.util.StringJoiner;

/**
 * 压缩率测量工具：把一组键装入 {@link PrefixCompressedStore}，
 * 对比前缀压缩存储与逐键完整存储的空间占用。
 */
public final class CompressionReport {

    private CompressionReport() {
    }

    /**
     * 一组键的压缩对比数据。
     *
     * @param label               场景标签
     * @param entryCount          条目数
     * @param averagePrefixLength 平均已保存前缀长度
     * @param uncompressedBytes   不压缩时键数据总字节数
     * @param compressedBytes     前缀压缩后键数据总字节数
     */
    public record Row(
            String label,
            int entryCount,
            double averagePrefixLength,
            long uncompressedBytes,
            long compressedBytes) {

        /** 压缩后 / 未压缩 字节比。 */
        public double compressionRatio() {
            return uncompressedBytes == 0 ? 1.0 : (double) compressedBytes / uncompressedBytes;
        }

        /** 节省空间百分比。 */
        public double spaceSavingPercent() {
            return (1.0 - compressionRatio()) * 100.0;
        }
    }

    /** 把给定键（任意顺序）装入一个新存储并测量压缩效果。 */
    public static Row measure(String label, List<byte[]> keys) {
        PrefixCompressedStore<Integer> store = new PrefixCompressedStore<>();
        for (int i = 0; i < keys.size(); i++) {
            store.put(keys.get(i), i);
        }
        StorageStats stats = store.stats();
        return new Row(
                label,
                stats.entryCount(),
                stats.averagePrefixLength(),
                stats.uncompressedBytes(),
                stats.compressedBytes());
    }

    /** 把多行测量结果渲染为对齐的文本表格。 */
    public static String table(List<Row> rows) {
        String header = String.format(
                "%-16s %8s %14s %16s %16s %10s %10s",
                "scenario", "entries", "avgPrefixLen", "uncompressed(B)", "compressed(B)", "ratio", "saving");
        StringJoiner joiner = new StringJoiner(System.lineSeparator());
        joiner.add(header);
        for (Row row : rows) {
            joiner.add(String.format(
                    "%-16s %8d %14.1f %16d %16d %10.3f %9.1f%%",
                    row.label(),
                    row.entryCount(),
                    row.averagePrefixLength(),
                    row.uncompressedBytes(),
                    row.compressedBytes(),
                    row.compressionRatio(),
                    row.spaceSavingPercent()));
        }
        return joiner.toString();
    }
}
