package com.example.gsb.pfc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class PrefixCompressedKeyMapTest {

    @Test
    void storesOnlyPrefixLengthAndSuffix() {
        PrefixCompressedKeyMap<String> map = new PrefixCompressedKeyMap<>();
        map.put("/api/v1/users/123/profile", "a");
        map.put("/api/v1/users/123/settings", "b");
        map.put("/api/v1/users/456/profile", "c");

        // anchor stores the whole key (prefix length 0)
        assertThat(map.keyAt(0)).isEqualTo("/api/v1/users/123/profile");

        // node 1: prefix shared with predecessor, only trailing bytes stored
        List<String> keys = new ArrayList<>();
        List<Integer> prefixes = new ArrayList<>();
        for (var e : map.entries()) {
            keys.add(e.getKey());
        }
        for (int i = 0; i < map.size(); i++) {
            prefixes.add(map.compressedNodeAt(i).prefixLength());
        }
        assertThat(keys).containsExactly(
                "/api/v1/users/123/profile",
                "/api/v1/users/123/settings",
                "/api/v1/users/456/profile");
        assertThat(prefixes).containsExactly(0, 18, 14);
        // "/api/v1/users/123/".length() == 18 ; "/api/v1/users/".length() == 14
    }

    @Test
    void insertionIsOrderedRegardlessOfArrivalOrder() {
        PrefixCompressedKeyMap<Integer> map = new PrefixCompressedKeyMap<>();
        List<String> sorted = new ArrayList<>();
        // pseudo-random but deterministic insertion order
        String[] keys = {
            "banana", "apple", "cherry", "apricot", "blueberry", "avocado",
            "banjo", "ape", "cherimoya", "apocalypse"
        };
        for (int i = 0; i < keys.length; i++) {
            map.put(keys[i], i);
            sorted.add(keys[i]);
        }
        java.util.Collections.sort(sorted);

        List<String> seen = new ArrayList<>();
        List<Integer> values = new ArrayList<>();
        for (Map.Entry<String, Integer> e : map.entries()) {
            seen.add(e.getKey());
            values.add(e.getValue());
        }
        assertThat(seen).isEqualTo(sorted);
        assertThat(seen).doesNotHaveDuplicates();
        // each restored key must match a point lookup
        for (String k : seen) {
            assertThat(map.lookup(k).found()).isTrue();
            assertThat(map.get(k)).isNotNull();
        }
        // value mapping preserved
        for (int i = 0; i < keys.length; i++) {
            assertThat(map.get(keys[i])).isEqualTo(i);
        }
    }

    @Test
    void iterationRestoresEveryFullKeyCorrectly() {
        TreeMap<String, Integer> reference = new TreeMap<>();
        PrefixCompressedKeyMap<Integer> map = new PrefixCompressedKeyMap<>();
        for (int i = 0; i < 2000; i++) {
            String key = String.format("order/event/%04d/type-%02d/payload", i / 7, i % 13);
            reference.put(key, i);
            map.put(key, i);
        }
        List<String> got = new ArrayList<>();
        map.entries().forEach(e -> {
            got.add(e.getKey());
            assertThat(e.getValue()).isEqualTo(reference.get(e.getKey()));
        });
        assertThat(got).containsExactlyElementsOf(new ArrayList<>(reference.keySet()));
    }

    @Test
    void pointLookupReturnsInsertionHintWhenAbsent() {
        PrefixCompressedKeyMap<String> map = new PrefixCompressedKeyMap<>();
        map.put("k10", "x");
        map.put("k20", "y");
        map.put("k30", "z");

        // empty map: hint 0
        PrefixCompressedKeyMap<String> empty = new PrefixCompressedKeyMap<>();
        PrefixCompressedKeyMap.LookupResult<String> r0 = empty.lookup("anything");
        assertThat(r0.found()).isFalse();
        assertThat(r0.index()).isZero();
        assertThat(r0.value()).isNull();

        PrefixCompressedKeyMap.LookupResult<String> before = map.lookup("k00");
        assertThat(before.found()).isFalse();
        assertThat(before.index()).isZero();

        PrefixCompressedKeyMap.LookupResult<String> middle = map.lookup("k15");
        assertThat(middle.found()).isFalse();
        assertThat(middle.index()).isEqualTo(1);
        // inserting at the hinted slot keeps order and lookup then succeeds
        map.put("k15", "q");
        assertThat(map.lookup("k15").found()).isTrue();
        assertThat(map.get("k15")).isEqualTo("q");

        PrefixCompressedKeyMap.LookupResult<String> after = map.lookup("k99");
        assertThat(after.found()).isFalse();
        assertThat(after.index()).isEqualTo(map.size());

        assertThat(map.lookup("k20").value()).isEqualTo("y");
    }

    @Test
    void putUpdatesValueWithoutChangingKeyMetadata() {
        PrefixCompressedKeyMap<String> map = new PrefixCompressedKeyMap<>();
        map.put("same/key/a", "1");
        map.put("same/key/b", "2");
        assertThat(map.put("same/key/a", "3")).isEqualTo("1");
        assertThat(map.size()).isEqualTo(2);
        assertThat(map.get("same/key/a")).isEqualTo("3");
        assertThat(map.get("same/key/b")).isEqualTo("2");
        List<String> keys = new ArrayList<>();
        map.entries().forEach(e -> keys.add(e.getKey()));
        assertThat(keys).containsExactly("same/key/a", "same/key/b");
    }

    @Test
    void removeRebuildsCompressionForSurvivors() {
        TreeMap<String, Integer> reference = new TreeMap<>();
        PrefixCompressedKeyMap<Integer> map = new PrefixCompressedKeyMap<>();
        String[] keys = {
            "site/2026/01/a", "site/2026/01/b", "site/2026/02/a",
            "site/2026/02/c", "site/2026/03/a", "site/2027/01/a"
        };
        for (int i = 0; i < keys.length; i++) {
            reference.put(keys[i], i);
            map.put(keys[i], i);
        }

        // remove a middle element whose successors shared prefixes through it
        assertThat(map.remove("site/2026/02/a")).isEqualTo(2);
        reference.remove("site/2026/02/a");
        assertRestoredKeysMatch(map, reference);

        // remove anchor (first element): next becomes new anchor
        assertThat(map.remove(keys[0])).isZero();
        reference.remove(keys[0]);
        assertRestoredKeysMatch(map, reference);

        // remove last element: no successor to rebuild
        String last = reference.lastKey();
        map.remove(last);
        reference.remove(last);
        assertRestoredKeysMatch(map, reference);

        // remove every remaining element
        for (String k : new ArrayList<>(reference.keySet())) {
            assertThat(map.remove(k)).isNotNull();
            reference.remove(k);
            assertRestoredKeysMatch(map, reference);
        }
        assertThat(map.isEmpty()).isTrue();
        assertThat(map.remove("nope")).isNull();
    }

    @Test
    void reinsertAfterDeleteProducesValidChain() {
        PrefixCompressedKeyMap<Integer> map = new PrefixCompressedKeyMap<>();
        TreeMap<String, Integer> reference = new TreeMap<>();
        for (int round = 0; round < 5; round++) {
            for (int i = 0; i < 300; i++) {
                String k = String.format("ns/%d/item-%03d", i % 5, i);
                map.put(k, i);
                reference.put(k, i);
            }
            for (int i = round; i < 300; i += 3) {
                String k = String.format("ns/%d/item-%03d", i % 5, i);
                map.remove(k);
                reference.remove(k);
            }
            assertRestoredKeysMatch(map, reference);
        }
    }

    private static void assertRestoredKeysMatch(
            PrefixCompressedKeyMap<Integer> map, TreeMap<String, Integer> reference) {
        List<String> keys = new ArrayList<>();
        map.entries().forEach(e -> {
            keys.add(e.getKey());
            assertThat(e.getValue()).isEqualTo(reference.get(e.getKey()));
        });
        assertThat(keys).containsExactlyElementsOf(new ArrayList<>(reference.keySet()));
        for (String k : reference.keySet()) {
            assertThat(map.containsKey(k)).as("point lookup after rebuild: %s", k).isTrue();
        }
    }

    @Test
    void statsReportCountsAveragePrefixAndBytes() {
        PrefixCompressedKeyMap<String> map = new PrefixCompressedKeyMap<>();
        assertThat(map.stats().entryCount()).isZero();
        assertThat(map.stats().compressedBytes()).isZero();

        map.put("aaaaaaaaaa", "v"); // 10 bytes, prefix 0
        map.put("aaaaaaaabb", "v"); // 8-byte shared prefix
        map.put("aaaaaaaabc", "v"); // 9-byte shared prefix
        PrefixCompressedKeyMap.Stats s = map.stats();
        assertThat(s.entryCount()).isEqualTo(3);
        assertThat(s.averagePrefixLength()).isEqualTo((0 + 8 + 9) / 3.0);
        // compressed: (varint0 + 10) + (varint8 + 2) + (varint9 + 1)
        assertThat(s.compressedBytes()).isEqualTo(11 + 3 + 2);
        // uncompressed: 3 * (varint10 + 10)
        assertThat(s.uncompressedBytes()).isEqualTo(33L);
        assertThat(s.compressionRatio()).isGreaterThan(1.0);
    }

    @Test
    void emptyMapBehaves() {
        PrefixCompressedKeyMap<String> map = new PrefixCompressedKeyMap<>();
        assertThat(map.size()).isZero();
        assertThat(map.isEmpty()).isTrue();
        assertThat(map.entries().iterator().hasNext()).isFalse();
        assertThatThrownBy(() -> map.entries().iterator().next())
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> map.keyAt(0)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void rejectsNullKeysAndValues() {
        PrefixCompressedKeyMap<String> map = new PrefixCompressedKeyMap<>();
        assertThatThrownBy(() -> map.put(null, "v")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> map.put("k", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> map.lookup(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> map.remove(null)).isInstanceOf(NullPointerException.class);
    }
}
