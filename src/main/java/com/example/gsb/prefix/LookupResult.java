package com.example.gsb.prefix;

/**
 * 点查结果。命中时携带值与条目位置；未命中时携带可插入位置
 * （即若插入该键，它在全局有序序列中应处的下标）。
 */
public final class LookupResult<V> {

    private final boolean found;
    private final V value;
    private final int position;

    private LookupResult(boolean found, V value, int position) {
        this.found = found;
        this.value = value;
        this.position = position;
    }

    public static <V> LookupResult<V> found(V value, int position) {
        return new LookupResult<>(true, value, position);
    }

    public static <V> LookupResult<V> notFound(int insertionPoint) {
        return new LookupResult<>(false, null, insertionPoint);
    }

    /** 是否命中。 */
    public boolean found() {
        return found;
    }

    /** 命中时返回关联值，未命中返回 {@code null}。 */
    public V value() {
        return value;
    }

    /** 命中时为条目在全局有序序列中的下标；未命中时语义同 {@link #insertionPoint()}。 */
    public int position() {
        return position;
    }

    /** 未命中时该键应插入的位置（全局有序下标，范围 [0, size]）。 */
    public int insertionPoint() {
        return position;
    }

    @Override
    public String toString() {
        return found
                ? "LookupResult{found, position=" + position + ", value=" + value + "}"
                : "LookupResult{notFound, insertionPoint=" + position + "}";
    }
}
