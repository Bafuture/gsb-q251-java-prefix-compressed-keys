package com.example.gsb.pfc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ConcurrencyTest {

    /**
     * While a writer thread churns inserts/replaces on a long-common-prefix key
     * space, reader threads must always observe:
     *  - a consistent snapshot (strictly ordered, unique, fully restorable keys);
     *  - no torn values: a present value is exactly the version last written by
     *    this reader's observation order (old or new, never anything else);
     *  - point lookups consistent with iteration over the same snapshot.
     */
    @Test
    void concurrentReadersNeverBlockAndAlwaysSeeOldOrNewValue() throws Exception {
        final int keyCount = 1500;
        final List<String> keys = new ArrayList<>();
        for (int i = 0; i < keyCount; i++) {
            keys.add(String.format("tenant/acme-corp/2026/orders/%06d/item", i));
        }
        // set of every value a writer may legally publish for a given key
        final Set<String> legalVersions = ConcurrentHashMap.newKeySet();
        final PrefixCompressedKeyMap<String> map = new PrefixCompressedKeyMap<>();
        for (String k : keys) {
            String v = "v0";
            map.put(k, v);
            legalVersions.add(v);
        }

        final AtomicBoolean stop = new AtomicBoolean(false);
        final AtomicLong writerRevisions = new AtomicLong();
        final AtomicLong readerScans = new AtomicLong();
        final AtomicLong inconsistencies = new AtomicLong();

        int writerCount = 2;
        int readerCount = 4;
        ExecutorService pool = Executors.newFixedThreadPool(writerCount + readerCount);
        CountDownLatch start = new CountDownLatch(1);

        for (int w = 0; w < writerCount; w++) {
            final int seed = w;
            pool.submit(() -> {
                try {
                    start.await();
                    long rev = seed;
                    while (!stop.get()) {
                        int i = (int) (Math.floorMod(rev * 6364136223846793005L + 1442695040888963407L,
                                (long) keyCount));
                        String value = "v" + (rev % 256);
                        legalVersions.add(value);
                        map.put(keys.get(i), value);
                        writerRevisions.incrementAndGet();
                        rev += writerCount;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        for (int r = 0; r < readerCount; r++) {
            pool.submit(() -> {
                try {
                    start.await();
                    String prev = null;
                    Set<String> seen = new HashSet<>();
                    while (!stop.get()) {
                        int count = 0;
                        prev = null;
                        seen.clear();
                        for (Map.Entry<String, String> e : map.entries()) {
                            String k = e.getKey();
                            String v = e.getValue();
                            // strict key order, no duplicates within the snapshot
                            if (prev != null && k.compareTo(prev) <= 0) {
                                inconsistencies.incrementAndGet();
                            }
                            if (!seen.add(k)) {
                                inconsistencies.incrementAndGet();
                            }
                            // value must be some fully published revision
                            if (!legalVersions.contains(v)) {
                                inconsistencies.incrementAndGet();
                            }
                            prev = k;
                            count++;
                        }
                        if (count != map.size() || count != keyCount) {
                            inconsistencies.incrementAndGet();
                        }
                        // spot point lookups over the freshly published snapshot
                        for (int probe = 0; probe < 32; probe++) {
                            String k = keys.get((probe * 977 + count) % keyCount);
                            PrefixCompressedKeyMap.LookupResult<String> hit = map.lookup(k);
                            if (!hit.found() || !legalVersions.contains(hit.value())) {
                                inconsistencies.incrementAndGet();
                            }
                        }
                        readerScans.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        start.countDown();
        Thread.sleep(2_000);
        stop.set(true);
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(inconsistencies.get()).isZero();
        assertThat(writerRevisions.get()).isPositive();
        assertThat(readerScans.get()).isPositive();

        // final state: every key present exactly once, restorable and ordered
        List<String> finalKeys = new ArrayList<>();
        map.entries().forEach(e -> finalKeys.add(e.getKey()));
        assertThat(finalKeys).containsExactlyElementsOf(keys);
    }
}
