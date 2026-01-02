package com.cos.cs_study_spring.service;

import com.cos.cs_study_spring.domain.Stock;
import com.cos.cs_study_spring.repository.StockRepository;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ============================================================================
 * Jitter 전략 비교 테스트
 * ============================================================================
 *
 * [테스트 시나리오]
 * - 초기 재고: 1000개
 * - 동시 요청: 1000개 스레드
 * - 각 스레드: 재고 1개씩 감소
 * - 기대 결과: 최종 재고 0개
 *
 * [비교 대상]
 * 1. Full Jitter:        sleep = random(0, base × 2^attempt)
 * 2. Equal Jitter:       sleep = half + random(0, half)  where half = (base × 2^attempt) / 2
 * 3. Decorrelated Jitter: sleep = min(cap, random(base, sleep_prev × 3))
 *
 * ============================================================================
 */
@Slf4j
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JitterComparisonTest {

    @Autowired
    private SpinLockWithBackoffService fullJitterService;  // Full Jitter

    @Autowired
    private EqualJitterService equalJitterService;

    @Autowired
    private DecorrelatedJitterService decorrelatedJitterService;

    @Autowired
    private StockRepository stockRepository;

    private static final int THREAD_COUNT = 1000;
    private static final Long INITIAL_STOCK = 1000L;
    private Long stockId;

    // 각 테스트 결과 저장
    private static long fullJitterDuration;
    private static long equalJitterDuration;
    private static long decorrelatedJitterDuration;

    private static long fullJitterRetryCount;
    private static long equalJitterRetryCount;
    private static long decorrelatedJitterRetryCount;

    @BeforeEach
    void setUp() {
        Stock stock = new Stock(INITIAL_STOCK);
        stockId = stockRepository.saveAndFlush(stock).getId();

        // 재시도 카운터 리셋
        fullJitterService.resetRetryCount();
        equalJitterService.resetRetryCount();
        decorrelatedJitterService.resetRetryCount();
    }

    @AfterEach
    void tearDown() {
        stockRepository.deleteAll();
    }

    /**
     * ========================================================================
     * Case 1: Full Jitter
     * ========================================================================
     * sleep = random(0, base × 2^attempt)
     *
     * - 대기 시간을 완전히 랜덤화
     * - AWS 권장 전략
     * - Thundering Herd 분산에 가장 효과적
     */
    @Test
    @Order(1)
    @DisplayName("Full Jitter: sleep = random(0, base × 2^attempt)")
    void fullJitter_test() throws InterruptedException {
        // Given
        ExecutorService executorService = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);

        // When
        long startTime = System.currentTimeMillis();

        for (int i = 0; i < THREAD_COUNT; i++) {
            executorService.submit(() -> {
                try {
                    fullJitterService.decrease(stockId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();

        fullJitterDuration = System.currentTimeMillis() - startTime;
        fullJitterRetryCount = fullJitterService.getRetryCount();

        // Then
        Stock stock = stockRepository.findById(stockId).orElseThrow();
        Long finalQuantity = stock.getQuantity();

        log.info("");
        log.info("╔═══════════════════════════════════════════════════════════════╗");
        log.info("║  Full Jitter 결과                                              ║");
        log.info("╠═══════════════════════════════════════════════════════════════╣");
        log.info("║  공식: sleep = random(0, base × 2^attempt)                     ║");
        log.info("╠═══════════════════════════════════════════════════════════════╣");
        log.info("║  초기 재고: {}                                               ║", String.format("%-4d", INITIAL_STOCK));
        log.info("║  동시 요청: {}                                               ║", String.format("%-4d", THREAD_COUNT));
        log.info("║  최종 재고: {} (기대값: 0)                                    ║", String.format("%-4d", finalQuantity));
        log.info("║  정합성: {}                                                  ║", finalQuantity == 0 ? "✅ 보장" : "❌ 실패");
        log.info("║  소요 시간: {}ms                                            ║", String.format("%-5d", fullJitterDuration));
        log.info("║  재시도 횟수: {}                                            ║", String.format("%-7d", fullJitterRetryCount));
        log.info("╚═══════════════════════════════════════════════════════════════╝");

        assertThat(finalQuantity).isEqualTo(0);
        executorService.shutdown();
    }

    /**
     * ========================================================================
     * Case 2: Equal Jitter
     * ========================================================================
     * sleep = (base × 2^attempt)/2 + random(0, (base × 2^attempt)/2)
     *
     * - 절반은 고정, 절반은 랜덤
     * - 최소 대기 시간 보장
     * - 예측 가능한 최소 지연
     */
    @Test
    @Order(2)
    @DisplayName("Equal Jitter: sleep = half + random(0, half)")
    void equalJitter_test() throws InterruptedException {
        // Given
        ExecutorService executorService = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);

        // When
        long startTime = System.currentTimeMillis();

        for (int i = 0; i < THREAD_COUNT; i++) {
            executorService.submit(() -> {
                try {
                    equalJitterService.decrease(stockId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();

        equalJitterDuration = System.currentTimeMillis() - startTime;
        equalJitterRetryCount = equalJitterService.getRetryCount();

        // Then
        Stock stock = stockRepository.findById(stockId).orElseThrow();
        Long finalQuantity = stock.getQuantity();

        log.info("");
        log.info("╔═══════════════════════════════════════════════════════════════╗");
        log.info("║  Equal Jitter 결과                                             ║");
        log.info("╠═══════════════════════════════════════════════════════════════╣");
        log.info("║  공식: sleep = half + random(0, half)                          ║");
        log.info("║        where half = (base × 2^attempt) / 2                     ║");
        log.info("╠═══════════════════════════════════════════════════════════════╣");
        log.info("║  초기 재고: {}                                               ║", String.format("%-4d", INITIAL_STOCK));
        log.info("║  동시 요청: {}                                               ║", String.format("%-4d", THREAD_COUNT));
        log.info("║  최종 재고: {} (기대값: 0)                                    ║", String.format("%-4d", finalQuantity));
        log.info("║  정합성: {}                                                  ║", finalQuantity == 0 ? "✅ 보장" : "❌ 실패");
        log.info("║  소요 시간: {}ms                                            ║", String.format("%-5d", equalJitterDuration));
        log.info("║  재시도 횟수: {}                                            ║", String.format("%-7d", equalJitterRetryCount));
        log.info("╚═══════════════════════════════════════════════════════════════╝");

        assertThat(finalQuantity).isEqualTo(0);
        executorService.shutdown();
    }

    /**
     * ========================================================================
     * Case 3: Decorrelated Jitter
     * ========================================================================
     * sleep = min(cap, random(base, sleep_prev × 3))
     *
     * - 이전 대기 시간 기반
     * - 비단조적 (증가/감소 모두 가능)
     * - 시도 횟수와 상관관계 없음
     */
    @Test
    @Order(3)
    @DisplayName("Decorrelated Jitter: sleep = min(cap, random(base, sleep_prev × 3))")
    void decorrelatedJitter_test() throws InterruptedException {
        // Given
        ExecutorService executorService = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);

        // When
        long startTime = System.currentTimeMillis();

        for (int i = 0; i < THREAD_COUNT; i++) {
            executorService.submit(() -> {
                try {
                    decorrelatedJitterService.decrease(stockId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();

        decorrelatedJitterDuration = System.currentTimeMillis() - startTime;
        decorrelatedJitterRetryCount = decorrelatedJitterService.getRetryCount();

        // Then
        Stock stock = stockRepository.findById(stockId).orElseThrow();
        Long finalQuantity = stock.getQuantity();

        log.info("");
        log.info("╔═══════════════════════════════════════════════════════════════╗");
        log.info("║  Decorrelated Jitter 결과                                      ║");
        log.info("╠═══════════════════════════════════════════════════════════════╣");
        log.info("║  공식: sleep = min(cap, random(base, sleep_prev × 3))          ║");
        log.info("╠═══════════════════════════════════════════════════════════════╣");
        log.info("║  초기 재고: {}                                               ║", String.format("%-4d", INITIAL_STOCK));
        log.info("║  동시 요청: {}                                               ║", String.format("%-4d", THREAD_COUNT));
        log.info("║  최종 재고: {} (기대값: 0)                                    ║", String.format("%-4d", finalQuantity));
        log.info("║  정합성: {}                                                  ║", finalQuantity == 0 ? "✅ 보장" : "❌ 실패");
        log.info("║  소요 시간: {}ms                                            ║", String.format("%-5d", decorrelatedJitterDuration));
        log.info("║  재시도 횟수: {}                                            ║", String.format("%-7d", decorrelatedJitterRetryCount));
        log.info("╚═══════════════════════════════════════════════════════════════╝");

        assertThat(finalQuantity).isEqualTo(0);
        executorService.shutdown();
    }

    /**
     * ========================================================================
     * 전체 Jitter 전략 비교 요약
     * ========================================================================
     */
    @Test
    @Order(4)
    @DisplayName("Jitter 전략 비교 요약")
    void compareJitterStrategies() {
        // 초당 재시도율 계산
        double fullRetryPerSec = fullJitterDuration > 0 ? (fullJitterRetryCount * 1000.0 / fullJitterDuration) : 0;
        double equalRetryPerSec = equalJitterDuration > 0 ? (equalJitterRetryCount * 1000.0 / equalJitterDuration) : 0;
        double decorrelatedRetryPerSec = decorrelatedJitterDuration > 0 ? (decorrelatedJitterRetryCount * 1000.0 / decorrelatedJitterDuration) : 0;

        log.info("");
        log.info("╔════════════════════════════════════════════════════════════════════════════════════════╗");
        log.info("║                          Jitter 전략 비교 - 테스트 결과                                  ║");
        log.info("╠════════════════════════════════════════════════════════════════════════════════════════╣");
        log.info("║                                                                                        ║");
        log.info("║  ┌─────────────────────┬────────────┬──────────────┬──────────────────────────────┐   ║");
        log.info("║  │ 전략                │ 소요 시간  │ 재시도 횟수  │ 초당 재시도율 (낮을수록 good) │   ║");
        log.info("║  ├─────────────────────┼────────────┼──────────────┼──────────────────────────────┤   ║");
        log.info("║  │ Full Jitter         │ {}ms   │ {}   │ {}/s   │   ║",
                String.format("%6d", fullJitterDuration),
                String.format("%10d", fullJitterRetryCount),
                String.format("%18.1f", fullRetryPerSec));
        log.info("║  │ Equal Jitter        │ {}ms   │ {}   │ {}/s   │   ║",
                String.format("%6d", equalJitterDuration),
                String.format("%10d", equalJitterRetryCount),
                String.format("%18.1f", equalRetryPerSec));
        log.info("║  │ Decorrelated Jitter │ {}ms   │ {}   │ {}/s   │   ║",
                String.format("%6d", decorrelatedJitterDuration),
                String.format("%10d", decorrelatedJitterRetryCount),
                String.format("%18.1f", decorrelatedRetryPerSec));
        log.info("║  └─────────────────────┴────────────┴──────────────┴──────────────────────────────┘   ║");
        log.info("║                                                                                        ║");
        log.info("╠════════════════════════════════════════════════════════════════════════════════════════╣");
        log.info("║  전략별 특성                                                                            ║");
        log.info("╠════════════════════════════════════════════════════════════════════════════════════════╣");
        log.info("║  • Full Jitter:        sleep = random(0, base × 2^attempt)  → 분산 최대, AWS 권장      ║");
        log.info("║  • Equal Jitter:       sleep = half + random(0, half)       → 최소 대기 보장           ║");
        log.info("║  • Decorrelated Jitter: sleep = min(cap, random(base, prev×3)) → 이전 값 기반          ║");
        log.info("╚════════════════════════════════════════════════════════════════════════════════════════╝");
        log.info("");
    }
}
