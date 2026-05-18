package com.miniurl.url;

import com.miniurl.url.util.SnowflakeIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SnowflakeIdGenerator Tests")
class SnowflakeIdGeneratorTest {

    private SnowflakeIdGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new SnowflakeIdGenerator(1L);
    }

    @Test
    @DisplayName("nextId generates unique IDs")
    void nextIdGeneratesUniqueIds() {
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            long id = generator.nextId();
            assertTrue(ids.add(id), "ID should be unique: " + id);
        }
    }

    @Test
    @DisplayName("nextId is thread-safe")
    void nextIdIsThreadSafe() throws InterruptedException {
        int threadCount = 10;
        int idsPerThread = 100;
        Set<Long> ids = new ConcurrentSkipListSet<>();
        CountDownLatch latch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < idsPerThread; j++) {
                        ids.add(generator.nextId());
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();
        assertEquals(threadCount * idsPerThread, ids.size(), "All IDs should be unique under concurrent access");
    }

    @Test
    @DisplayName("nextId produces monotonically increasing IDs")
    void nextIdMonotonicallyIncreasing() {
        long prev = generator.nextId();
        for (int i = 0; i < 100; i++) {
            long current = generator.nextId();
            assertTrue(current > prev, "ID should be monotonically increasing");
            prev = current;
        }
    }
}
