package com.example.gsb.pfc;

import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * An ordered key/value store that keeps keys with front-coding (prefix compression).
 *
 * <p>For every key {@code k[i]} only two pieces are persisted:
 * <ul>
 *   <li>{@code prefixLen} &mdash; length of the longest common prefix with the
 *       immediately preceding key {@code k[i-1]} ({@code 0} for the first key);</li>
 *   <li>{@code suffix} &mdash; the remaining bytes {@code k[i][prefixLen..]}.</li>
 * </ul>
 * The full key of {@code k[i]} is rebuilt as
 * {@code k[i-1][0..prefixLen) + suffix}; the anchor ({@code prefixLen == 0})
 * stores the complete key.
 *
 * <p>Concurrency model: the whole map is an immutable snapshot published through a
 * {@code volatile} reference. Readers are completely lock-free and always observe a
 * consistent snapshot, so a key read during a write is either the old entry or the
 * new entry, never a torn mix. A mutation copies only the affected slots: an insert
 * or delete changes at most two entries' compression metadata (the removed/inserted
 * entry and its right neighbour), everything else is shared with the old snapshot.
 *
 * @param <V> value type
 */
public final class PrefixCompressedKeyMap<V> {

    /** One compressed entry. Instances are immutable and safe to share across snapshots. */
    public static final class Node<V> {
        final int prefixLen;
        final byte[] suffix;
        final V value;

        Node(int prefixLen, byte[] suffix, V value) {
            this.prefixLen = prefixLen;
            this.suffix = suffix;
            this.value = value;
        }

        /** Length, in bytes, of the common prefix with the preceding key. */
        public int prefixLength() {
            return prefixLen;
        }

        /** The bytes stored after the shared prefix. */
        public byte[] suffix() {
            return suffix.clone();
        }

        /** The value attached to this entry. */
        public V value() {
            return value;
        }
    }

    /** Result of a point lookup: hit/miss plus the key-ordered insertion hint. */
    public static final class LookupResult<V> {
        private final boolean found;
        private final int index;
        private final V value;

        private LookupResult(boolean found, int index, V value) {
            this.found = found;
            this.index = index;
            this.value = value;
        }

        /** Whether the key exists in the map. */
        public boolean found() {
            return found;
        }

        /**
         * Index of the entry when found; otherwise the insertion position in
         * {@code [0, size()]} where the key would keep the map ordered.
         */
        public int index() {
            return index;
        }

        /** Value when found, {@code null} otherwise. */
        public V value() {
            return value;
        }
    }

    /** Immutable statistics snapshot. */
    public static final class Stats {
        private final int entryCount;
        private final double averagePrefixLength;
        private final long compressedBytes;
        private final long uncompressedBytes;

        Stats(int entryCount, double averagePrefixLength, long compressedBytes, long uncompressedBytes) {
            this.entryCount = entryCount;
            this.averagePrefixLength = averagePrefixLength;
            this.compressedBytes = compressedBytes;
            this.uncompressedBytes = uncompressedBytes;
        }

        public int entryCount() {
            return entryCount;
        }

        /** Mean shared-prefix length over all entries (the anchor contributes 0). */
        public double averagePrefixLength() {
            return averagePrefixLength;
        }

        /** Bytes needed for keys under prefix compression (varint(prefixLen) + suffix each). */
        public long compressedBytes() {
            return compressedBytes;
        }

        /** Bytes needed when every key is stored in full (varint(len) + key each). */
        public long uncompressedBytes() {
            return uncompressedBytes;
        }

        /** uncompressed / compressed; values &gt; 1 mean compression wins. */
        public double compressionRatio() {
            return compressedBytes == 0 ? 1.0 : (double) uncompressedBytes / compressedBytes;
        }

        /** Fraction of raw key bytes saved by compression, in [0, 1). */
        public double spaceSavingFraction() {
            return uncompressedBytes == 0 ? 0.0 : 1.0 - (double) compressedBytes / uncompressedBytes;
        }
    }

    @SuppressWarnings("rawtypes")
    private static final Node[] EMPTY = new Node[0];

    /** Only ever assigned a freshly published, internally consistent snapshot. */
    private volatile Node<V>[] table;

    @SuppressWarnings("unchecked")
    public PrefixCompressedKeyMap() {
        this.table = (Node<V>[]) EMPTY;
    }

    // ------------------------------------------------------------------ writes

    /**
     * Inserts or replaces a value. Keys may be inserted in any order; the entry is
     * placed so that key order is preserved. Only the new node and its successor's
     * compression metadata are (re)built; every other node is shared.
     */
    public V put(String key, V value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        byte[] full = encode(key);
        synchronized (this) {
            Node<V>[] cur = table;
            int idx = binarySearch(cur, full);
            if (idx < 0) {
                int ins = -idx - 1;
                int n = cur.length;
                @SuppressWarnings("unchecked")
                Node<V>[] next = (Node<V>[]) new Node<?>[n + 1];
                System.arraycopy(cur, 0, next, 0, ins);
                System.arraycopy(cur, ins, next, ins + 1, n - ins);

                if (ins == 0) {
                    next[0] = new Node<>(0, full, value);
                    if (n > 0) {
                        // old anchor becomes an ordinary entry relative to the new anchor
                        byte[] oldKey = cur[0].suffix.clone();
                        int p = commonPrefix(full, oldKey);
                        next[1] = new Node<>(p, Arrays.copyOfRange(oldKey, p, oldKey.length), cur[0].value);
                    }
                } else {
                    byte[] prevKey = rebuildKey(cur, ins - 1);
                    int p = commonPrefix(prevKey, full);
                    next[ins] = new Node<>(p, Arrays.copyOfRange(full, p, full.length), value);
                    if (ins < n) {
                        // successor may now share a longer prefix with the new predecessor
                        byte[] succKey = rebuildKey(cur, ins);
                        int ps = commonPrefix(full, succKey);
                        if (ps != cur[ins].prefixLen) {
                            next[ins + 1] =
                                    new Node<>(ps, Arrays.copyOfRange(succKey, ps, succKey.length), cur[ins].value);
                        }
                    }
                }
                table = next;
                return null;
            }
            V old = cur[idx].value;
            if (old != value) {
                Node<V>[] next = cur.clone();
                Node<V> c = cur[idx];
                next[idx] = new Node<>(c.prefixLen, c.suffix, value);
                table = next;
            }
            return old;
        }
    }

    /**
     * Removes a key and rebuilds compression around the hole. The successor of the
     * removed entry is re-encoded relative to its new predecessor (or becomes the
     * anchor); every other node keeps its (still valid) metadata.
     *
     * @return the removed value, or {@code null} if the key was absent
     */
    public V remove(String key) {
        Objects.requireNonNull(key, "key");
        byte[] full = encode(key);
        synchronized (this) {
            Node<V>[] cur = table;
            int idx = binarySearch(cur, full);
            if (idx < 0) {
                return null;
            }
            V removed = cur[idx].value;
            int n = cur.length;
            @SuppressWarnings("unchecked")
            Node<V>[] next = (Node<V>[]) new Node<?>[n - 1];
            System.arraycopy(cur, 0, next, 0, idx);
            System.arraycopy(cur, idx + 1, next, idx, n - idx - 1);
            if (idx < n - 1) {
                if (idx == 0) {
                    byte[] succKey = rebuildKey(cur, idx + 1);
                    next[0] = new Node<>(0, succKey, cur[idx + 1].value);
                } else {
                    byte[] prevKey = rebuildKey(cur, idx - 1);
                    byte[] succKey = rebuildKey(cur, idx + 1);
                    int p = commonPrefix(prevKey, succKey);
                    next[idx] =
                            new Node<>(p, Arrays.copyOfRange(succKey, p, succKey.length), cur[idx + 1].value);
                }
            }
            table = next;
            return removed;
        }
    }

    // ------------------------------------------------------------------ reads

    public int size() {
        return table.length;
    }

    public boolean isEmpty() {
        return table.length == 0;
    }

    /** Point lookup with insertion hint; never blocks against writers. */
    public LookupResult<V> lookup(String key) {
        Objects.requireNonNull(key, "key");
        Node<V>[] cur = table;
        int idx = binarySearch(cur, encode(key));
        if (idx >= 0) {
            return new LookupResult<>(true, idx, cur[idx].value);
        }
        return new LookupResult<>(false, -idx - 1, null);
    }

    /** Convenience accessor; returns {@code null} when absent. */
    public V get(String key) {
        return lookup(key).value();
    }

    public boolean containsKey(String key) {
        return lookup(key).found();
    }

    /**
     * Restores the full key at an index by walking back through the prefix chain
     * to the nearest anchor ({@code prefixLen == 0}).
     */
    public String keyAt(int index) {
        Node<V>[] cur = table;
        if (index < 0 || index >= cur.length) {
            throw new IndexOutOfBoundsException(index);
        }
        return decode(rebuildKey(cur, index));
    }

    /**
     * Exposes the compressed representation of the entry at {@code index}
     * (shared-prefix length + stored suffix), e.g. for diagnostics.
     */
    public Node<V> compressedNodeAt(int index) {
        Node<V>[] cur = table;
        if (index < 0 || index >= cur.length) {
            throw new IndexOutOfBoundsException(index);
        }
        return cur[index];
    }

    public V valueAt(int index) {
        Node<V>[] cur = table;
        if (index < 0 || index >= cur.length) {
            throw new IndexOutOfBoundsException(index);
        }
        return cur[index].value;
    }

    /**
     * Ordered iteration over fully restored keys. The iterator is backed by the
     * snapshot taken at creation time and never sees concurrent mutations.
     */
    public Iterable<Map.Entry<String, V>> entries() {
        return () -> {
            final Node<V>[] snapshot = table;
            final Iterator<Map.Entry<String, V>> it = new Iterator<>() {
                private int pos = 0;
                private byte[] prev = EMPTY_BYTES;

                @Override
                public boolean hasNext() {
                    return pos < snapshot.length;
                }

                @Override
                public Map.Entry<String, V> next() {
                    if (pos >= snapshot.length) {
                        throw new NoSuchElementException();
                    }
                    Node<V> node = snapshot[pos];
                    byte[] key = appendPrefix(prev, node.prefixLen, node.suffix);
                    prev = key;
                    Map.Entry<String, V> e = new AbstractMap.SimpleImmutableEntry<>(decode(key), node.value);
                    pos++;
                    return e;
                }
            };
            return it;
        };
    }

    // ------------------------------------------------------------- statistics

    public Stats stats() {
        Node<V>[] cur = table;
        long prefixSum = 0;
        long compressed = 0;
        long uncompressed = 0;
        byte[] prev = EMPTY_BYTES;
        for (Node<V> node : cur) {
            prefixSum += node.prefixLen;
            compressed += varintLength(node.prefixLen) + node.suffix.length;
            byte[] full = appendPrefix(prev, node.prefixLen, node.suffix);
            uncompressed += varintLength(full.length) + full.length;
            prev = full;
        }
        double avg = cur.length == 0 ? 0.0 : (double) prefixSum / cur.length;
        return new Stats(cur.length, avg, compressed, uncompressed);
    }

    // -------------------------------------------------------------- internals

    private static final byte[] EMPTY_BYTES = new byte[0];

    private static byte[] encode(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String decode(byte[] b) {
        return new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }

    static int commonPrefix(byte[] a, byte[] b) {
        int max = Math.min(a.length, b.length);
        int i = 0;
        while (i < max && a[i] == b[i]) {
            i++;
        }
        return i;
    }

    /** Rebuilds the full key at {@code idx} from the compressed chain. */
    private static <V> byte[] rebuildKey(Node<V>[] t, int idx) {
        int anchor = idx;
        while (anchor > 0 && t[anchor].prefixLen != 0) {
            anchor--;
        }
        byte[] key = t[anchor].suffix.clone();
        for (int i = anchor + 1; i <= idx; i++) {
            key = appendPrefix(key, t[i].prefixLen, t[i].suffix);
        }
        return key;
    }

    private static byte[] appendPrefix(byte[] prev, int prefixLen, byte[] suffix) {
        byte[] out = new byte[prefixLen + suffix.length];
        System.arraycopy(prev, 0, out, 0, prefixLen);
        System.arraycopy(suffix, 0, out, prefixLen, suffix.length);
        return out;
    }

    static int unsignedByte(byte b) {
        return b & 0xFF;
    }

    /**
     * Binary search over compressed keys.
     *
     * <p>A candidate key is reconstructed on demand by walking the prefix chain
     * back to its anchor, so the search needs no full-key storage.
     *
     * @return index of the match, or {@code -(insertionPoint + 1)}
     */
    private static <V> int binarySearch(Node<V>[] t, byte[] key) {
        int low = 0;
        int high = t.length - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            byte[] midKey = rebuildKey(t, mid);
            int cmp = compareUnsigned(key, midKey);
            if (cmp > 0) {
                low = mid + 1;
            } else if (cmp < 0) {
                high = mid - 1;
            } else {
                return mid;
            }
        }
        return -(low + 1);
    }

    private static int compareUnsigned(byte[] a, byte[] b) {
        int max = Math.min(a.length, b.length);
        for (int i = 0; i < max; i++) {
            int d = unsignedByte(a[i]) - unsignedByte(b[i]);
            if (d != 0) {
                return d;
            }
        }
        return Integer.compare(a.length, b.length);
    }

    /** Bytes taken by an LEB128 unsigned varint. */
    static int varintLength(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("negative length: " + value);
        }
        int n = 1;
        while ((value >>>= 7) != 0) {
            n++;
        }
        return n;
    }
}
