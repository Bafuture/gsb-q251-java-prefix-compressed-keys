package com.example.gsb.prefix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class PrefixCompressedStoreTest {

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> sortedKeys(int count) {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            keys.add(String.format("user/profile/%08d", i));
        }
        return keys;
    }

    private static PrefixCompressedStore<Integer> storeWith(List<String> keys, boolean shuffle) {
        List<String> insertionOrder = new ArrayList<>(keys);
        if (shuffle) {
            Collections.shuffle(insertionOrder, new java.util.Random(42));
        }
        PrefixCompressedStore<Integer> store = new PrefixCompressedStore<>();
        for (int i = 0; i < insertionOrder.size(); i++) {
            String key = insertionOrder.get(i);
            store.put(utf8(key), keys.indexOf(key));
        }
        return store;
    }

    @Test
    void shuffledInsertThenIterateRestoresFullKeysInOrder() {
        List<String> keys = sortedKeys(1_000);
        PrefixCompressedStore<Integer> store = storeWith(keys, true);

        assertThat(store.size()).isEqualTo(keys.size());

        List<String> iterated = new ArrayList<>();
        List<Integer> indexes = new ArrayList<>();
        for (StoreEntry<Integer> entry : store) {
            iterated.add(entry.keyAsUtf8());
            indexes.add(entry.index());
            assertThat(entry.value()).isEqualTo(keys.indexOf(entry.keyAsUtf8()));
        }
        assertThat(iterated).isEqualTo(keys);
        assertThat(indexes).isEqualTo(java.util.stream.IntStream.range(0, keys.size()).boxed().toList());
    }

    @Test
    void putSameKeyReplacesValueWithoutDuplicating() {
        PrefixCompressedStore<String> store = new PrefixCompressedStore<>();
        store.put(utf8("alpha"), "v1");
        store.put(utf8("alpha"), "v2");
        store.put(utf8("beta"), "v3");

        assertThat(store.size()).isEqualTo(2);
        assertThat(store.get(utf8("alpha"))).contains("v2");
    }

    @Test
    void pointLookupFindsValuesAndPositions() {
        List<String> keys = sortedKeys(500);
        PrefixCompressedStore<Integer> store = storeWith(keys, true);

        for (int i = 0; i < keys.size(); i += 37) {
            LookupResult<Integer> result = store.lookup(utf8(keys.get(i)));
            assertThat(result.found()).isTrue();
            assertThat(result.value()).isEqualTo(i);
            assertThat(result.position()).isEqualTo(i);
        }
    }

    @Test
    void pointLookupMissReportsInsertionPoint() {
        PrefixCompressedStore<Integer> store = new PrefixCompressedStore<>();
        List<String> keys = List.of("apple", "banana", "cherry", "date", "grape");
        for (int i = 0; i < keys.size(); i++) {
            store.put(utf8(keys.get(i)), i);
        }

        assertThat(store.lookup(utf8("aardvark")).found()).isFalse();
        assertThat(store.lookup(utf8("aardvark")).insertionPoint()).isEqualTo(0);

        assertThat(store.lookup(utf8("blueberry")).insertionPoint()).isEqualTo(2);

        assertThat(store.lookup(utf8("c")).insertionPoint()).isEqualTo(2);

        assertThat(store.lookup(utf8("zebra")).insertionPoint()).isEqualTo(5);

        LookupResult<Integer> miss = store.lookup(utf8("blueberry"));
        assertThat(miss.value()).isNull();
    }

    @Test
    void lookupOnEmptyStoreReportsInsertionPointZero() {
        PrefixCompressedStore<Integer> store = new PrefixCompressedStore<>();
        LookupResult<Integer> result = store.lookup(utf8("anything"));
        assertThat(result.found()).isFalse();
        assertThat(result.insertionPoint()).isZero();
    }

    @Test
    void deleteMiddleEntriesRebuildsPrefixRelationships() {
        List<String> keys = sortedKeys(300);
        PrefixCompressedStore<Integer> store = storeWith(keys, false);

        TreeSet<String> expected = new TreeSet<>(keys);
        for (int i = 1; i < keys.size(); i += 3) {
            String victim = keys.get(i);
            assertThat(store.delete(utf8(victim))).isTrue();
            expected.remove(victim);
        }

        assertThat(store.size()).isEqualTo(expected.size());
        List<String> iterated = new ArrayList<>();
        for (StoreEntry<Integer> entry : store) {
            iterated.add(entry.keyAsUtf8());
        }
        assertThat(iterated).containsExactlyElementsOf(expected);

        for (String remaining : expected) {
            assertThat(store.get(utf8(remaining))).as("key %s", remaining).isPresent();
        }
        assertThat(store.delete(utf8(keys.get(1)))).isFalse();
    }

    @Test
    void deleteBlockAnchorPromotesNextEntryToFullKey() {
        PrefixCompressedStore<Integer> store = new PrefixCompressedStore<>(4);
        List<String> keys = List.of(
                "shared-prefix-aaaa",
                "shared-prefix-bbbb",
                "shared-prefix-cccc",
                "shared-prefix-dddd",
                "shared-prefix-eeee");
        for (int i = 0; i < keys.size(); i++) {
            store.put(utf8(keys.get(i)), i);
        }

        assertThat(store.delete(utf8("shared-prefix-aaaa"))).isTrue();

        List<String> iterated = new ArrayList<>();
        for (StoreEntry<Integer> entry : store) {
            iterated.add(entry.keyAsUtf8());
        }
        assertThat(iterated).containsExactly(
                "shared-prefix-bbbb", "shared-prefix-cccc", "shared-prefix-dddd", "shared-prefix-eeee");
        assertThat(store.lookup(utf8("shared-prefix-aaaa")).insertionPoint()).isZero();
    }

    @Test
    void deleteEverythingLeavesEmptyStore() {
        List<String> keys = sortedKeys(50);
        PrefixCompressedStore<Integer> store = storeWith(keys, true);
        for (String key : keys) {
            assertThat(store.delete(utf8(key))).isTrue();
        }
        assertThat(store.isEmpty()).isTrue();
        assertThat(store.iterator().hasNext()).isFalse();
        assertThat(store.lookup(utf8(keys.get(0))).insertionPoint()).isZero();
    }

    @Test
    void deleteMissingKeyReturnsFalse() {
        PrefixCompressedStore<Integer> store = new PrefixCompressedStore<>();
        store.put(utf8("present"), 1);
        assertThat(store.delete(utf8("absent"))).isFalse();
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void statsReflectEntriesPrefixesAndBytes() {
        List<String> keys = sortedKeys(1_000);
        PrefixCompressedStore<Integer> store = storeWith(keys, false);

        StorageStats stats = store.stats();
        assertThat(stats.entryCount()).isEqualTo(1_000);
        assertThat(stats.averagePrefixLength()).isBetween(10.0, 20.0);
        assertThat(stats.compressedBytes()).isLessThan(stats.uncompressedBytes());
        assertThat(stats.compressionRatio()).isBetween(0.0, 1.0);

        long expectedUncompressed = 4 + keys.stream().mapToLong(k -> 4L + k.length()).sum();
        assertThat(stats.uncompressedBytes()).isEqualTo(expectedUncompressed);
    }

    @Test
    void rejectsNullArgumentsAndTinyBlocks() {
        PrefixCompressedStore<Integer> store = new PrefixCompressedStore<>();
        assertThatThrownBy(() -> store.put(null, 1)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> store.put(utf8("k"), null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PrefixCompressedStore<Integer>(1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
