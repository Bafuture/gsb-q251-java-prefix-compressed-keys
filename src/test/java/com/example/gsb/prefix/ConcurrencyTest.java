package com.example.gsb.prefix;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ConcurrencyTest {

    private static final int BASE_KEYS = 1_000;
    private static final int WRITER_OPS = 3_000;
    private static final int READER_THREADS = 4;
    private static final Pattern VALID_KEY = Pattern.compile("(key|new)-\\d{6}");

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @Timeout(120)
    void concurrentReadsNeverSeeTornKeysAndValuesAreOldOrNew() throws Exception {
        PrefixCompressedStore<Integer> store = new PrefixCompressedStore<>(8);
        for (int i = 0; i < BASE_KEYS; i++) {
            store.put(utf8(String.format("key-%06d", i)), 1);
        }

        AtomicBoolean writerDone = new AtomicBoolean(false);
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);

        Thread writer = new Thread(() -> {
            awaitQuietly(start);
            try {
                for (int op = 0; op < WRITER_OPS; op++) {
                    switch (op % 3) {
                        case 0 -> {
                            String key = String.format("key-%06d", op % BASE_KEYS);
                            store.put(utf8(key), (op & 1) == 0 ? 2 : 1);
                        }
                        case 1 -> {
                            int n = op / 3 % 200;
                            store.put(utf8(String.format("new-%06d", n)), 7);
                        }
                        default -> {
                            int n = op / 3 % 200;
                            store.delete(utf8(String.format("new-%06d", n)));
                        }
                    }
                }
            } catch (Throwable t) {
                firstError.compareAndSet(null, t);
            } finally {
                writerDone.set(true);
            }
        }, "writer");

        List<Thread> readers = new ArrayList<>();
        for (int r = 0; r < READER_THREADS; r++) {
            Thread reader = new Thread(() -> {
                awaitQuietly(start);
                try {
                    while (!writerDone.get()) {
                        assertSnapshotConsistent(store);
                    }
                    assertSnapshotConsistent(store);
                } catch (Throwable t) {
                    firstError.compareAndSet(null, t);
                }
            }, "reader-" + r);
            readers.add(reader);
        }

        writer.start();
        readers.forEach(Thread::start);
        start.countDown();

        writer.join(60_000);
        for (Thread reader : readers) {
            reader.join(60_000);
        }
        assertThat(writer.isAlive()).as("writer should finish").isFalse();
        for (Thread reader : readers) {
            assertThat(reader.isAlive()).as("reader should finish").isFalse();
        }
        assertThat(firstError.get()).as("concurrent access invariant failure").isNull();
    }

    private static void assertSnapshotConsistent(PrefixCompressedStore<Integer> store) {
        PrefixCompressedStore.StoreSnapshot<Integer> snapshot = store.snapshot();
        byte[] previous = null;
        int seen = 0;
        for (StoreEntry<Integer> entry : snapshot) {
            byte[] key = entry.key();
            assertThat(VALID_KEY.matcher(entry.keyAsUtf8()).matches())
                    .as("torn/garbled key observed: %s", entry.keyAsUtf8())
                    .isTrue();
            if (entry.keyAsUtf8().startsWith("key-")) {
                assertThat(entry.value()).as("value must be old(1) or new(2)").isIn(1, 2);
            } else {
                assertThat(entry.value()).isEqualTo(7);
            }
            if (previous != null) {
                assertThat(Arrays.compareUnsigned(previous, key))
                        .as("iterated keys must be strictly increasing").isNegative();
            }
            previous = key;
            seen++;
        }
        assertThat(seen).isEqualTo(snapshot.size());

        LookupResult<Integer> probe = store.lookup(utf8("key-000500"));
        if (probe.found()) {
            assertThat(probe.value()).isIn(1, 2);
            assertThat(store.get(utf8(String.format("key-%06d", probe.position())))).isPresent();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
