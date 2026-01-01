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
 * 분산 락 성능 및 정합성 테스트
 * ============================================================================
 *
 * [테스트 시나리오]
 * - 초기 재고: 100개
 * - 동시 요청: 100개 스레드
 * - 각 스레드: 재고 1개씩 감소
 * - 기대 결과: 최종 재고 0개
 *
 * [측정 항목]
 * 1. 정합성(Consistency): 최종 재고가 정확히 0인지
 * 2. 성능(Performance): 전체 작업 소요 시간
 *
 * ⚠️ 테스트 실행 전 Docker로 MySQL과 Redis가 실행 중이어야 합니다.
 * $ docker-compose up -d
 * ============================================================================
 */
@Slf4j
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DistributedLockTest {

    @Autowired
    private NoLockService noLockService;

    @Autowired
    private SpinLockService spinLockService;

    @Autowired
    private SpinLockWithBackoffService spinLockWithBackoffService;

    @Autowired
    private PubSubLockService pubSubLockService;

    @Autowired
    private StockRepository stockRepository;

    private static final int THREAD_COUNT = 1000;
    private static final Long INITIAL_STOCK = 1000L;

    private Long stockId;

    @BeforeEach
    void setUp() {
        Stock stock = new Stock(INITIAL_STOCK);
        stockId = stockRepository.saveAndFlush(stock).getId();
    }

    @AfterEach
    void tearDown() {
        stockRepository.deleteAll();
    }

    /**
     * ========================================================================
     * Case 1: No Lock (비교군)
     * ========================================================================
     * - 락 없이 동시 접근 시 Race Condition 발생
     * - 최종 재고가 0보다 클 것으로 예상 (정합성 실패)
     */
    @Test
    @Order(1)
    @DisplayName("Case 1: No Lock - Race Condition 발생 확인")
    void noLock_raceCondition() throws InterruptedException {
        // Given
        ExecutorService executorService = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);

        // When
        long startTime = System.currentTimeMillis();

        for (int i = 0; i < THREAD_COUNT; i++) {
            executorService.submit(() -> {
                try {
                    noLockService.decrease(stockId);
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        // Then
        Stock stock = stockRepository.findById(stockId).orElseThrow();
        Long finalQuantity = stock.getQuantity();

        log.info("============================================================");
        log.info("Case 1: No Lock 결과");
        log.info("============================================================");
        log.info("초기 재고: {}", INITIAL_STOCK);
        log.info("동시 요청 수: {}", THREAD_COUNT);
        log.info("최종 재고: {} (기대값: 0)", finalQuantity);
        log.info("정합성: {}", finalQuantity == 0 ? "✅ 보장" : "❌ 실패");
        log.info("소요 시간: {}ms", duration);
        log.info("============================================================");

        // Race Condition으로 인해 0보다 클 가능성이 높음
        // assertThat(finalQuantity).isNotEqualTo(0);

        executorService.shutdown();
    }

    /**
     * ========================================================================
     * Case 2: Pure Spin Lock (Lettuce SETNX)
     * ========================================================================
     * - 락 획득까지 무한 재시도 (Busy Waiting)
     * - 정합성 보장되지만 Redis 서버 부하 매우 높음
     */
    @Test
    @Order(2)
    @DisplayName("Case 2: Pure Spin Lock - 정합성 보장, 높은 Redis 부하")
    void spinLock_consistency() throws InterruptedException {
        // Given
        ExecutorService executorService = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);

        // When
        long startTime = System.currentTimeMillis();

        for (int i = 0; i < THREAD_COUNT; i++) {
            executorService.submit(() -> {
                try {
                    spinLockService.decrease(stockId);
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        // Then
        Stock stock = stockRepository.findById(stockId).orElseThrow();
        Long finalQuantity = stock.getQuantity();

        log.info("============================================================");
        log.info("Case 2: Pure Spin Lock 결과");
        log.info("============================================================");
        log.info("초기 재고: {}", INITIAL_STOCK);
        log.info("동시 요청 수: {}", THREAD_COUNT);
        log.info("최종 재고: {} (기대값: 0)", finalQuantity);
        log.info("정합성: {}", finalQuantity == 0 ? "✅ 보장" : "❌ 실패");
        log.info("소요 시간: {}ms", duration);
        log.info("⚠️ Redis 부하: 매우 높음 (Busy Waiting)");
        log.info("============================================================");

        assertThat(finalQuantity).isEqualTo(0);

        executorService.shutdown();
    }

    /**
     * ========================================================================
     * Case 3: Spin Lock with Exponential Backoff
     * ========================================================================
     * - 락 획득 실패 시 지수적으로 증가하는 대기 시간
     * - 정합성 보장, Redis 부하 감소
     */
    @Test
    @Order(3)
    @DisplayName("Case 3: Spin Lock with Backoff - 정합성 보장, 중간 Redis 부하")
    void spinLockWithBackoff_consistency() throws InterruptedException {
        // Given
        ExecutorService executorService = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);

        // When
        long startTime = System.currentTimeMillis();

        for (int i = 0; i < THREAD_COUNT; i++) {
            executorService.submit(() -> {
                try {
                    spinLockWithBackoffService.decrease(stockId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        // Then
        Stock stock = stockRepository.findById(stockId).orElseThrow();
        Long finalQuantity = stock.getQuantity();

        log.info("============================================================");
        log.info("Case 3: Spin Lock with Backoff 결과");
        log.info("============================================================");
        log.info("초기 재고: {}", INITIAL_STOCK);
        log.info("동시 요청 수: {}", THREAD_COUNT);
        log.info("최종 재고: {} (기대값: 0)", finalQuantity);
        log.info("정합성: {}", finalQuantity == 0 ? "✅ 보장" : "❌ 실패");
        log.info("소요 시간: {}ms", duration);
        log.info("📊 Redis 부하: 중간 (Backoff로 재시도 간격 증가)");
        log.info("============================================================");

        assertThat(finalQuantity).isEqualTo(0);

        executorService.shutdown();
    }

    /**
     * ========================================================================
     * Case 4: Pub/Sub Lock (Redisson)
     * ========================================================================
     * - Pub/Sub 기반으로 락 해제 시 알림
     * - 정합성 보장, Redis 부하 최소화
     */
    @Test
    @Order(4)
    @DisplayName("Case 4: Pub/Sub Lock (Redisson) - 정합성 보장, 최소 Redis 부하")
    void pubSubLock_consistency() throws InterruptedException {
        // Given
        ExecutorService executorService = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);

        // When
        long startTime = System.currentTimeMillis();

        for (int i = 0; i < THREAD_COUNT; i++) {
            executorService.submit(() -> {
                try {
                    pubSubLockService.decrease(stockId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        // Then
        Stock stock = stockRepository.findById(stockId).orElseThrow();
        Long finalQuantity = stock.getQuantity();

        log.info("============================================================");
        log.info("Case 4: Pub/Sub Lock (Redisson) 결과");
        log.info("============================================================");
        log.info("초기 재고: {}", INITIAL_STOCK);
        log.info("동시 요청 수: {}", THREAD_COUNT);
        log.info("최종 재고: {} (기대값: 0)", finalQuantity);
        log.info("정합성: {}", finalQuantity == 0 ? "✅ 보장" : "❌ 실패");
        log.info("소요 시간: {}ms", duration);
        log.info("✅ Redis 부하: 최소 (Pub/Sub 방식)");
        log.info("============================================================");

        assertThat(finalQuantity).isEqualTo(0);

        executorService.shutdown();
    }

    /**
     * ========================================================================
     * 전체 방식 비교 테스트
     * ========================================================================
     */
    @Test
    @Order(5)
    @DisplayName("전체 분산 락 방식 비교")
    void compareAllMethods() {
        log.info("");
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║              분산 락 방식별 특성 비교 요약                          ║");
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║  방식               │ 정합성  │ Redis 부하 │ 반응성 │ 구현 복잡도  ║");
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║  No Lock            │  ❌     │  없음      │  N/A   │  ★☆☆☆☆    ║");
        log.info("║  Pure Spin Lock     │  ✅     │  ⚠️⚠️⚠️    │  빠름  │  ★★☆☆☆    ║");
        log.info("║  Spin + Backoff     │  ✅     │  ⚠️        │  느림  │  ★★★☆☆    ║");
        log.info("║  Pub/Sub (Redisson) │  ✅     │  ✅        │  빠름  │  ★★☆☆☆    ║");
        log.info("╚══════════════════════════════════════════════════════════════════╝");
        log.info("");
        log.info("📌 권장 사항:");
        log.info("   - 프로덕션 환경: Pub/Sub (Redisson) 사용 권장");
        log.info("   - 간단한 테스트: Spin Lock with Backoff 사용 가능");
        log.info("   - Pure Spin Lock: 절대 프로덕션에서 사용 금지!");
        log.info("");
    }
}
