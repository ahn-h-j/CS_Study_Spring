package com.cos.cs_study_spring.latency;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@SpringBootTest
class LatencyTest {

    @Autowired
    private PointService pointService;

    @Autowired
    private PointRepository pointRepository;

    private static final int THREAD_COUNT = 100;
    private static final Long USER_ID = 1L;

    @BeforeEach
    void setUp() {
        pointRepository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        pointRepository.deleteAll();
    }

    @Test
    @DisplayName("P99 지연시간 측정 - 포인트 충전")
    void measureP99() throws InterruptedException {
        // Given
        LatencyRecorder recorder = new LatencyRecorder("PointCharge");
        ExecutorService executorService = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);

        // When
        for (int i = 0; i < THREAD_COUNT; i++) {
            final long userId = i % 10;  // 10명의 유저
            executorService.submit(() -> {
                try {
                    long start = System.currentTimeMillis();

                    pointService.charge(userId, 100L);

                    long elapsed = System.currentTimeMillis() - start;
                    recorder.record(elapsed);
                } catch (Exception e) {
                    log.error("Error: {}", e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();

        // Then
        log.info("");
        log.info("============================================================");
        log.info("[PointCharge] Latency Statistics");
        log.info("============================================================");
        log.info("Total Requests: {}", recorder.getCount());
        log.info("Min:  {} ms", recorder.getMin());
        log.info("Max:  {} ms", recorder.getMax());
        log.info("Avg:  {} ms", String.format("%.2f", recorder.getAvg()));
        log.info("P50:  {} ms", recorder.getP50());
        log.info("P99:  {} ms", recorder.getP99());
        log.info("P999: {} ms", recorder.getP999());
        log.info("============================================================");

        // 최종 포인트 확인
        for (long i = 0; i < 10; i++) {
            Long amount = pointService.getAmount(i);
            log.info("User {} 포인트: {}", i, amount);
        }

        executorService.shutdown();
    }

    @Test
    @DisplayName("LatencyRecorder 단위 테스트")
    void latencyRecorderTest() {
        // Given
        LatencyRecorder recorder = new LatencyRecorder("Test");

        // 1~100까지 기록
        for (int i = 1; i <= 100; i++) {
            recorder.record(i);
        }

        // Then
        assertThat(recorder.getCount()).isEqualTo(100);
        assertThat(recorder.getMin()).isEqualTo(1);
        assertThat(recorder.getMax()).isEqualTo(100);
        assertThat(recorder.getP50()).isEqualTo(50);
        assertThat(recorder.getP99()).isEqualTo(99);

        log.info("P50: {}", recorder.getP50());
        log.info("P99: {}", recorder.getP99());
        log.info("P999: {}", recorder.getP999());
    }
}
