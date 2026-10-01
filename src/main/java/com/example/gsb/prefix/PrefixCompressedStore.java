package com.example.gsb.prefix;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 有序键的前缀压缩存储（块式 front coding）。
 *
 * <p>键按无符号字节序排列并分块：每块首条（锚点）保存完整键，块内其余条目只保存
 * 与前一条键的公共前缀长度和剩余后缀。每块的目标容量为 {@code maxBlockEntries}；
 * 插入时允许瞬时膨胀到约 2 倍目标容量后再分裂为两个接近全满的块，
 * 从而即使是单调顺序加载，稳态块也接近全满。点查只需一次锚点二分加一次块内顺序扫描。
 *
 * <p>并发模型：写操作由一把写锁串行化，并以 copy-on-write 方式只替换受影响的块，
 * 然后通过 {@code volatile} 快照整体发布；读操作（点查 / 遍历 / 统计）完全无锁，
 * 读到的是某一时刻的一致快照，键与值要么是旧值要么是新值，不会出现撕裂。
 *
 * <p>注意：键与值在存入后不得被调用方修改（键数组不会被再次拷贝）。
 */
public final class PrefixCompressedStore<V> implements Iterable<StoreEntry<V>> {

    /** 默认每块最大条目数。 */
    public static final int DEFAULT_MAX_BLOCK_ENTRIES = 8;

    private final int maxBlockEntries;
    private final ReentrantLock writeLock = new ReentrantLock();
    private volatile Snapshot<V> snapshot;

    public PrefixCompressedStore() {
        this(DEFAULT_MAX_BLOCK_ENTRIES);
    }

    public PrefixCompressedStore(int maxBlockEntries) {
        if (maxBlockEntries < 2) {
            throw new IllegalArgumentException("maxBlockEntries must be >= 2, got " + maxBlockEntries);
        }
        this.maxBlockEntries = maxBlockEntries;
        this.snapshot = Snapshot.empty();
    }

    /** 当前条目数。 */
    public int size() {
        return snapshot.size();
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    /**
     * 获取当前时刻的一致快照视图：视图内的 size 与遍历来自同一个不可变版本，
     * 完全无锁，后续写入不影响该视图。
     */
    public StoreSnapshot<V> snapshot() {
        return new StoreSnapshot<>(snapshot);
    }

    /** 点查便捷方法：命中返回值，未命中返回空。 */
    public Optional<V> get(byte[] key) {
        LookupResult<V> result = lookup(key);
        return result.found() ? Optional.of(result.value()) : Optional.empty();
    }

    /**
     * 点查。命中时结果携带值与条目全局下标；未命中时结果携带可插入位置。
     * 全程无锁，基于当前快照。
     */
    public LookupResult<V> lookup(byte[] key) {
        Objects.requireNonNull(key, "key");
        Snapshot<V> snap = this.snapshot;
        Block<V>[] blocks = snap.blocks();
        if (blocks.length == 0) {
            return LookupResult.notFound(0);
        }
        int blockIndex = findBlock(blocks, key);
        Block<V> block = blocks[blockIndex];
        int base = snap.offsets()[blockIndex];
        byte[] prev = block.firstKey;
        for (int i = 0; i < block.entries.length; i++) {
            Entry<V> entry = block.entries[i];
            byte[] keyAt = i == 0 ? prev : reconstruct(prev, entry);
            int cmp = Arrays.compareUnsigned(keyAt, key);
            if (cmp == 0) {
                return LookupResult.found(entry.value(), base + i);
            }
            if (cmp > 0) {
                return LookupResult.notFound(base + i);
            }
            prev = keyAt;
        }
        return LookupResult.notFound(base + block.entries.length);
    }

    /** 插入或覆盖一个键值对，保持全局有序；仅重建受影响的块。 */
    public void put(byte[] key, V value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        writeLock.lock();
        try {
            Snapshot<V> snap = this.snapshot;
            List<Block<V>> blocks = new ArrayList<>(Arrays.asList(snap.blocks()));
            if (blocks.isEmpty()) {
                blocks.add(encode(List.of(key), List.of(value), 0, 1));
                publish(blocks);
                return;
            }
            int blockIndex = findBlock(snap.blocks(), key);
            Block<V> block = snap.blocks()[blockIndex];
            List<byte[]> keys = decodeKeys(block);
            List<V> values = valuesOf(block);
            int pos = Collections.binarySearch(keys, key, Arrays::compareUnsigned);
            if (pos >= 0) {
                values.set(pos, value);
            } else {
                int insertionPoint = -pos - 1;
                keys.add(insertionPoint, key);
                values.add(insertionPoint, value);
            }
            replaceBlock(blocks, blockIndex, keys, values);
            publish(blocks);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 删除一个键。删除后所在块会整体重编码，后续条目的前缀关系随之重建；
     * 若块被删空则整块移除。返回是否确实删除了一个条目。
     */
    public boolean delete(byte[] key) {
        Objects.requireNonNull(key, "key");
        writeLock.lock();
        try {
            Snapshot<V> snap = this.snapshot;
            Block<V>[] blocks = snap.blocks();
            if (blocks.length == 0) {
                return false;
            }
            int blockIndex = findBlock(blocks, key);
            Block<V> block = blocks[blockIndex];
            List<byte[]> keys = decodeKeys(block);
            List<V> values = valuesOf(block);
            int pos = Collections.binarySearch(keys, key, Arrays::compareUnsigned);
            if (pos < 0) {
                return false;
            }
            keys.remove(pos);
            values.remove(pos);
            List<Block<V>> newBlocks = new ArrayList<>(Arrays.asList(blocks));
            replaceBlock(newBlocks, blockIndex, keys, values);
            publish(newBlocks);
            return true;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 按键序遍历，逐条还原完整键并携带全局下标。
     * 迭代器基于创建时的快照，不受后续写操作影响。
     */
    @Override
    public Iterator<StoreEntry<V>> iterator() {
        return snapshot().iterator();
    }

    /** 当前快照的键空间统计。 */
    public StorageStats stats() {
        Snapshot<V> snap = this.snapshot;
        long compressed = 4;
        long uncompressed = 4;
        long prefixLengthSum = 0;
        for (Block<V> block : snap.blocks()) {
            compressed += 4L + 4L + block.firstKey.length;
            for (int i = 0; i < block.entries.length; i++) {
                Entry<V> entry = block.entries[i];
                prefixLengthSum += entry.prefixLen();
                uncompressed += 4L + entry.prefixLen() + entry.suffix().length;
                compressed += 4L + (i == 0 ? 0L : entry.suffix().length);
            }
        }
        int count = snap.size();
        double averagePrefix = count == 0 ? 0.0 : (double) prefixLengthSum / count;
        return new StorageStats(count, averagePrefix, compressed, uncompressed);
    }

    private void replaceBlock(List<Block<V>> blocks, int blockIndex, List<byte[]> keys, List<V> values) {
        blocks.remove(blockIndex);
        if (keys.isEmpty()) {
            return;
        }
        if (keys.size() <= 2 * maxBlockEntries) {
            blocks.add(blockIndex, encode(keys, values, 0, keys.size()));
        } else {
            int half = maxBlockEntries;
            blocks.add(blockIndex, encode(keys, values, 0, half));
            blocks.add(blockIndex + 1, encode(keys, values, half, keys.size()));
        }
    }

    private void publish(List<Block<V>> blocks) {
        @SuppressWarnings("unchecked")
        Block<V>[] array = blocks.toArray(new Block[0]);
        int[] offsets = new int[array.length];
        int total = 0;
        for (int i = 0; i < array.length; i++) {
            offsets[i] = total;
            total += array[i].entries.length;
        }
        this.snapshot = new Snapshot<>(array, offsets, total);
    }

    private static int findBlock(Block<?>[] blocks, byte[] key) {
        int lo = 0;
        int hi = blocks.length - 1;
        int result = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (Arrays.compareUnsigned(blocks[mid].firstKey, key) <= 0) {
                result = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return result;
    }

    private static <V> Block<V> encode(List<byte[]> keys, List<V> values, int from, int to) {
        byte[] firstKey = keys.get(from);
        @SuppressWarnings("unchecked")
        Entry<V>[] entries = new Entry[to - from];
        entries[0] = new Entry<>(0, firstKey, values.get(from));
        byte[] prev = firstKey;
        for (int i = from + 1; i < to; i++) {
            byte[] key = keys.get(i);
            int prefixLen = commonPrefix(prev, key);
            entries[i - from] = new Entry<>(prefixLen, Arrays.copyOfRange(key, prefixLen, key.length), values.get(i));
            prev = key;
        }
        return new Block<>(firstKey, entries);
    }

    private static <V> List<byte[]> decodeKeys(Block<V> block) {
        List<byte[]> keys = new ArrayList<>(block.entries.length);
        byte[] prev = block.firstKey;
        keys.add(prev);
        for (int i = 1; i < block.entries.length; i++) {
            prev = reconstruct(prev, block.entries[i]);
            keys.add(prev);
        }
        return keys;
    }

    private static <V> List<V> valuesOf(Block<V> block) {
        List<V> values = new ArrayList<>(block.entries.length);
        for (Entry<V> entry : block.entries) {
            values.add(entry.value());
        }
        return values;
    }

    private static <V> byte[] reconstruct(byte[] prevKey, Entry<V> entry) {
        byte[] key = Arrays.copyOf(prevKey, entry.prefixLen() + entry.suffix().length);
        System.arraycopy(entry.suffix(), 0, key, entry.prefixLen(), entry.suffix().length);
        return key;
    }

    private static int commonPrefix(byte[] a, byte[] b) {
        int limit = Math.min(a.length, b.length);
        int i = 0;
        while (i < limit && a[i] == b[i]) {
            i++;
        }
        return i;
    }

    private record Entry<V>(int prefixLen, byte[] suffix, V value) {
    }

    private static final class Block<V> {
        final byte[] firstKey;
        final Entry<V>[] entries;

        Block(byte[] firstKey, Entry<V>[] entries) {
            this.firstKey = firstKey;
            this.entries = entries;
        }
    }

    private record Snapshot<V>(Block<V>[] blocks, int[] offsets, int size) {
        @SuppressWarnings("unchecked")
        static <V> Snapshot<V> empty() {
            return new Snapshot<>(new Block[0], new int[0], 0);
        }
    }

    /** 某一时刻不可变存储版本的视图。 */
    public static final class StoreSnapshot<V> implements Iterable<StoreEntry<V>> {

        private final Snapshot<V> snap;

        StoreSnapshot(Snapshot<V> snap) {
            this.snap = snap;
        }

        /** 该版本的条目数。 */
        public int size() {
            return snap.size();
        }

        @Override
        public Iterator<StoreEntry<V>> iterator() {
            return new Iterator<>() {
                private int blockIndex;
                private int entryIndex;
                private byte[] prevKey = snap.blocks().length == 0 ? null : snap.blocks()[0].firstKey;

                @Override
                public boolean hasNext() {
                    return blockIndex < snap.blocks().length;
                }

                @Override
                public StoreEntry<V> next() {
                    if (!hasNext()) {
                        throw new NoSuchElementException();
                    }
                    Block<V> block = snap.blocks()[blockIndex];
                    Entry<V> entry = block.entries[entryIndex];
                    byte[] key = entryIndex == 0 ? block.firstKey : reconstruct(prevKey, entry);
                    prevKey = key;
                    StoreEntry<V> result =
                            new StoreEntry<>(key, entry.value(), snap.offsets()[blockIndex] + entryIndex);
                    if (++entryIndex == block.entries.length) {
                        entryIndex = 0;
                        if (++blockIndex < snap.blocks().length) {
                            prevKey = snap.blocks()[blockIndex].firstKey;
                        }
                    }
                    return result;
                }
            };
        }
    }
}
