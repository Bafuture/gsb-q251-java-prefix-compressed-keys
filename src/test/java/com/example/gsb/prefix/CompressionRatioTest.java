package com.example.gsb.prefix;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompressionRatioTest {

    private static final int KEY_COUNT = 5_000;
    private static final int[] SHARED_PREFIX_LENGTHS = {0, 8, 16, 32, 64, 128};

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void compressionRatioImprovesWithSharedPrefixLength() {
        List<CompressionReport.Row> rows = new ArrayList<>();
        for (int prefixLength : SHARED_PREFIX_LENGTHS) {
            List<byte[]> keys = generateKeys(prefixLength);
            CompressionReport.Row row = CompressionReport.measure("prefix=" + prefixLength, keys);
            rows.add(row);

            assertThat(row.entryCount()).isEqualTo(KEY_COUNT);
            assertThat(row.compressedBytes()).isPositive();
            assertThat(row.averagePrefixLength()).isBetween(prefixLength * 0.7, prefixLength + 8.0);
            if (prefixLength >= 8) {
                assertThat(row.compressedBytes())
                        .as("prefix=%d must save space", prefixLength)
                        .isLessThan(row.uncompressedBytes());
            }
        }

        System.out.println();
        System.out.println("=== Prefix compression vs raw storage (entries=" + KEY_COUNT + ") ===");
        System.out.println(CompressionReport.table(rows));

        for (int i = 2; i < rows.size(); i++) {
            assertThat(rows.get(i).compressionRatio())
                    .as("ratio must decrease as prefix grows: %s vs %s", rows.get(i - 1), rows.get(i))
                    .isLessThan(rows.get(i - 1).compressionRatio());
        }
        assertThat(rows.get(rows.size() - 1).spaceSavingPercent())
                .as("128-byte shared prefix should save at least 75%%")
                .isGreaterThan(75.0);
    }

    private static List<byte[]> generateKeys(int sharedPrefixLength) {
        String prefix = "p".repeat(sharedPrefixLength);
        List<byte[]> keys = new ArrayList<>(KEY_COUNT);
        for (int i = 0; i < KEY_COUNT; i++) {
            keys.add(utf8(prefix + String.format("%08d", i)));
        }
        return keys;
    }
}
