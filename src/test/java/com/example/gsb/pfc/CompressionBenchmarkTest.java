package com.example.gsb.pfc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Compression-ratio data: the same number of keys, same total key length shape,
 * with the common prefix varying from 0 to ~40 bytes.
 */
class CompressionBenchmarkTest {

    @Test
    void compressionRatioAcrossPrefixLengths() {
        int entries = 5_000;
        int suffixLength = 12; // unique tail per key

        System.out.println();
        System.out.println("prefix-compressed key storage: " + entries + " keys, unique tail "
                + suffixLength + " bytes (UTF-8)");
        System.out.printf(
                "%-12s %-16s %-16s %-10s %-9s%n",
                "prefixLen", "compressed(B)", "raw(B)", "ratio", "saved");
        System.out.println("-".repeat(70));

        int[] prefixLengths = {0, 8, 16, 24, 32, 40};
        java.util.List<Double> ratios = new java.util.ArrayList<>();
        for (int prefixLength : prefixLengths) {
            PrefixCompressedKeyMap<Integer> map = new PrefixCompressedKeyMap<>();
            for (int i = 0; i < entries; i++) {
                String key = makeKey(prefixLength, suffixLength, i);
                map.put(key, i);
            }
            PrefixCompressedKeyMap.Stats s = map.stats();

            // sanity: stats describe exactly the entries stored
            assertThat(s.entryCount()).isEqualTo(entries);
            assertThat(s.averagePrefixLength()).isGreaterThanOrEqualTo(0.0);

            System.out.printf(
                    "%-12d %-16d %-16d %-10.2f %-8.1f%%%n",
                    prefixLength,
                    s.compressedBytes(),
                    s.uncompressedBytes(),
                    s.compressionRatio(),
                    s.spaceSavingFraction() * 100.0);

            ratios.add(s.compressionRatio());
            // the longer the engineered common prefix, the bigger the win;
            // at prefix 0 the spread tails share only ~2 incidental chars
            if (prefixLength == 0) {
                assertThat(s.compressionRatio()).isLessThan(1.4);
            } else {
                assertThat(s.compressionRatio()).isGreaterThan(1.0);
                assertThat(s.averagePrefixLength()).isGreaterThan(prefixLength - 2.0);
            }
        }
        // savings grow monotonically with the shared-prefix length
        for (int i = 1; i < ratios.size(); i++) {
            assertThat(ratios.get(i)).isGreaterThan(ratios.get(i - 1));
        }
        System.out.println();

        // concrete data point at a 32-byte prefix
        PrefixCompressedKeyMap<Integer> sample = new PrefixCompressedKeyMap<>();
        for (int i = 0; i < entries; i++) {
            sample.put(makeKey(32, suffixLength, i), i);
        }
        PrefixCompressedKeyMap.Stats s32 = sample.stats();
        // raw key = 44 bytes each; compressed stores ~12-byte suffix + 1 byte varint
        assertThat(s32.uncompressedBytes()).isEqualTo((long) entries * (1 + 32 + suffixLength));
        assertThat((double) s32.compressedBytes() / s32.uncompressedBytes()).isLessThan(0.35);
    }

    private static String makeKey(int prefixLength, int suffixLength, int i) {
        StringBuilder sb = new StringBuilder(prefixLength + suffixLength);
        for (int p = 0; p < prefixLength; p++) {
            sb.append((char) ('a' + (p % 5))); // "abcde..." deterministic shared body
        }
        // unique tail spread over the whole base-36 value space (multiplication by a
        // factor coprime to 36 is a permutation), so sorted-adjacent tails share
        // essentially no prefix unless one was engineered above.
        java.math.BigInteger space = java.math.BigInteger.valueOf(36).pow(suffixLength);
        // multiplier ~ 0.618 * space, forced coprime to 36 so i -> i*m mod space
        // is a bijection spreading tails over the whole value range
        java.math.BigInteger m = space.multiply(java.math.BigInteger.valueOf(2654435761L))
                .divide(java.math.BigInteger.valueOf(4294967296L));
        while (m.mod(java.math.BigInteger.valueOf(6)).equals(java.math.BigInteger.ZERO)) {
            m = m.add(java.math.BigInteger.ONE);
        }
        java.math.BigInteger spread = java.math.BigInteger.valueOf(i).multiply(m).mod(space);
        String tail = spread.toString(36);
        sb.append("0".repeat(suffixLength - tail.length()));
        sb.append(tail);
        return sb.toString();
    }
}
