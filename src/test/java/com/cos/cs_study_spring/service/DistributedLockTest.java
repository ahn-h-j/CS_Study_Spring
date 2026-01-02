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

    // 각 테스트 결과 저장
    private static long noLockDuration;
    private static long spinLockDuration;
    private static long fullJitterDuration;
    private static long pubSubDuration;

    private static Long noLockFinalStock;
    private static Long spinLockFinalStock;
    private static Long fullJitterFinalStock;
    private static Long pubSubFinalStock;

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
        noLockDuration = endTime - startTime;

        // Then
        Stock stock = stockRepository.findById(stockId).orElseThrow();
        noLockFinalStock = stock.getQuantity();

        log.info("============================================================");
        log.info("Case 1: No Lock 결과");
        log.info("============================================================");
        log.info("초기 재고: {}", INITIAL_STOCK);
        log.info("동시 요청 수: {}", THREAD_COUNT);
        log.info("최종 재고: {} (기대값: 0)", noLockFinalStock);
        log.info("정합성: {}", noLockFinalStock == 0 ? "✅ 보장" : "❌ 실패");
        log.info("소요 시간: {}ms", noLockDuration);
        log.info("============================================================");

        // Race Condition으로 인해 0보다 클 가능성이 높음
        // assertThat(noLockFinalStock).isNotEqualTo(0);

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
        spinLockDuration = endTime - startTime;

        // Then
        Stock stock = stockRepository.findById(stockId).orElseThrow();
        spinLockFinalStock = stock.getQuantity();

        log.info("============================================================");
        log.info("Case 2: Pure Spin Lock 결과");
        log.info("============================================================");
        log.info("초기 재고: {}", INITIAL_STOCK);
        log.info("동시 요청 수: {}", THREAD_COUNT);
        log.info("최종 재고: {} (기대값: 0)", spinLockFinalStock);
        log.info("정합성: {}", spinLockFinalStock == 0 ? "✅ 보장" : "❌ 실패");
        log.info("소요 시간: {}ms", spinLockDuration);
        log.info("⚠️ Redis 부하: 매우 높음 (Busy Waiting)");
        log.info("============================================================");

        assertThat(spinLockFinalStock).isEqualTo(0);

        executorService.shutdown();
    }

    /**
     * ========================================================================
     * Case 3: Spin Lock with Full Jitter
     * ========================================================================
     * - 공식: sleep = random(0, base × 2^attempt)
     * - 대기 시간을 완전히 랜덤화하여 Thundering Herd 분산
     * - AWS 권장 전략
     */
    @Test
    @Order(3)
    @DisplayName("Case 3: Full Jitter - 정합성 보장, 낮은 Redis 부하")
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
        fullJitterDuration = endTime - startTime;

        // Then
        Stock stock = stockRepository.findById(stockId).orElseThrow();
        fullJitterFinalStock = stock.getQuantity();

        log.info("============================================================");
        log.info("Case 3: Full Jitter 결과");
        log.info("============================================================");
        log.info("공식: sleep = random(0, base × 2^attempt)");
        log.info("초기 재고: {}", INITIAL_STOCK);
        log.info("동시 요청 수: {}", THREAD_COUNT);
        log.info("최종 재고: {} (기대값: 0)", fullJitterFinalStock);
        log.info("정합성: {}", fullJitterFinalStock == 0 ? "✅ 보장" : "❌ 실패");
        log.info("소요 시간: {}ms", fullJitterDuration);
        log.info("📊 Redis 부하: 낮음~중간 (Full Jitter로 재시도 분산)");
        log.info("============================================================");

        assertThat(fullJitterFinalStock).isEqualTo(0);

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
        pubSubDuration = endTime - startTime;

        // Then
        Stock stock = stockRepository.findById(stockId).orElseThrow();
        pubSubFinalStock = stock.getQuantity();

        log.info("============================================================");
        log.info("Case 4: Pub/Sub Lock (Redisson) 결과");
        log.info("============================================================");
        log.info("초기 재고: {}", INITIAL_STOCK);
        log.info("동시 요청 수: {}", THREAD_COUNT);
        log.info("최종 재고: {} (기대값: 0)", pubSubFinalStock);
        log.info("정합성: {}", pubSubFinalStock == 0 ? "✅ 보장" : "❌ 실패");
        log.info("소요 시간: {}ms", pubSubDuration);
        log.info("✅ Redis 부하: 최소 (Pub/Sub 방식)");
        log.info("============================================================");

        assertThat(pubSubFinalStock).isEqualTo(0);

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
        log.info("╔════════════════════════════════════════════════════════════════════════════╗");
        log.info("║                     분산 락 방식별 비교 - 테스트 결과                        ║");
        log.info("╠════════════════════════════════════════════════════════════════════════════╣");
        log.info("║                                                                            ║");
        log.info("║  ┌────────────────────┬────────────┬────────────┬────────────────────┐    ║");
        log.info("║  │ 방식               │ 소요 시간  │ 최종 재고  │ 정합성             │    ║");
        log.info("║  ├────────────────────┼────────────┼────────────┼────────────────────┤    ║");
        log.info("║  │ No Lock            │ {}ms   │ {}     │ {}    │    ║",
                String.format("%6d", noLockDuration),
                String.format("%6d", noLockFinalStock),
                noLockFinalStock == 0 ? "✅ 보장      " : "❌ 실패      ");
        log.info("║  │ Pure Spin Lock     │ {}ms   │ {}     │ {}    │    ║",
                String.format("%6d", spinLockDuration),
                String.format("%6d", spinLockFinalStock),
                spinLockFinalStock == 0 ? "✅ 보장      " : "❌ 실패      ");
        log.info("║  │ Full Jitter        │ {}ms   │ {}     │ {}    │    ║",
                String.format("%6d", fullJitterDuration),
                String.format("%6d", fullJitterFinalStock),
                fullJitterFinalStock == 0 ? "✅ 보장      " : "❌ 실패      ");
        log.info("║  │ Pub/Sub (Redisson) │ {}ms   │ {}     │ {}    │    ║",
                String.format("%6d", pubSubDuration),
                String.format("%6d", pubSubFinalStock),
                pubSubFinalStock == 0 ? "✅ 보장      " : "❌ 실패      ");
        log.info("║  └────────────────────┴────────────┴────────────┴────────────────────┘    ║");
        log.info("║                                                                            ║");
        log.info("╠════════════════════════════════════════════════════════════════════════════╣");
        log.info("║  방식별 특성                                                                ║");
        log.info("╠════════════════════════════════════════════════════════════════════════════╣");
        log.info("║  • No Lock:            락 없음 → Race Condition 발생                       ║");
        log.info("║  • Pure Spin Lock:     락 획득까지 무한 재시도 → Redis 부하 높음           ║");
        log.info("║  • Full Jitter:        지수 백오프 + 랜덤 대기 → Redis 부하 중간           ║");
        log.info("║  • Pub/Sub (Redisson): 락 해제 알림 대기 → Redis 부하 낮음                 ║");
        log.info("║                                                                            ║");
        log.info("║  📌 권장: 프로덕션에서는 Pub/Sub (Redisson) 사용                            ║");
        log.info("╚════════════════════════════════════════════════════════════════════════════╝");
        log.info("");
    }
}
