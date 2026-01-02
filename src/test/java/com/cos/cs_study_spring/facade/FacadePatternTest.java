package com.cos.cs_study_spring.facade;

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
 * 트랜잭션과 락의 위험한 동거 테스트
 * ============================================================================
 *
 * [테스트 시나리오]
 * - 초기 티켓: 100개
 * - 동시 요청: 100개 스레드
 * - 각 스레드: 티켓 1개씩 감소
 * - 기대 결과: 최종 티켓 0개
 *
 * [비교]
 * 1. WrongLockService: @Transactional 내부에서 락 → 정합성 깨질 수 있음
 * 2. TicketFacade: Facade 패턴으로 분리 → 정합성 보장
 *
 * ============================================================================
 */
@Slf4j
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FacadePatternTest {

    @Autowired
    private WrongLockService wrongLockService;

    @Autowired
    private TicketFacade ticketFacade;

    @Autowired
    private TicketRepository ticketRepository;

    private static final int THREAD_COUNT = 100;
    private static final Long INITIAL_QUANTITY = 100L;

    // 각 테스트 결과 저장
    private static long wrongPatternDuration;
    private static long facadePatternDuration;
    private static Long wrongPatternFinalQuantity;
    private static Long facadePatternFinalQuantity;

    @AfterEach
    void tearDown() {
        ticketRepository.deleteAll();
    }

    /**
     * ========================================================================
     * [잘못된 패턴] @Transactional 내부에서 락 처리
     * ========================================================================
     *
     * 문제: 락 해제 → 커밋 사이에 다른 스레드가 과거 데이터를 읽을 수 있음
     * 결과: 정합성이 깨질 가능성 있음 (0보다 큰 값이 남을 수 있음)
     */
    @Test
    @Order(1)
    @DisplayName("[잘못된 패턴] @Transactional 내부 락 - 정합성 깨질 수 있음")
    void wrongPattern_lockInsideTransaction() throws InterruptedException {
        // Given
        Ticket ticket = ticketRepository.saveAndFlush(new Ticket(INITIAL_QUANTITY));
        Long ticketId = ticket.getId();

        ExecutorService executorService = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);

        // When
        long startTime = System.currentTimeMillis();

        for (int i = 0; i < THREAD_COUNT; i++) {
            executorService.submit(() -> {
                try {
                    wrongLockService.decreaseWithLockInTransaction(ticketId);
                } catch (Exception e) {
                    log.error("Error: {}", e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();

        wrongPatternDuration = System.currentTimeMillis() - startTime;

        // Then
        Ticket result = ticketRepository.findById(ticketId).orElseThrow();
        wrongPatternFinalQuantity = result.getQuantity();

        log.info("");
        log.info("============================================================");
        log.info("[잘못된 패턴] @Transactional 내부 락 결과");
        log.info("============================================================");
        log.info("초기 티켓: {}", INITIAL_QUANTITY);
        log.info("동시 요청 수: {}", THREAD_COUNT);
        log.info("최종 티켓: {} (기대값: 0)", wrongPatternFinalQuantity);
        log.info("정합성: {}", wrongPatternFinalQuantity == 0 ? "✅ 보장" : "❌ 실패 (락 해제~커밋 사이 갭 발생)");
        log.info("소요 시간: {}ms", wrongPatternDuration);
        log.info("============================================================");

        // 정합성이 깨질 수 있음 (반드시 실패하는 건 아님, 타이밍에 따라 다름)
        // assertThat(wrongPatternFinalQuantity).isNotEqualTo(0);

        executorService.shutdown();
    }

    /**
     * ========================================================================
     * [올바른 패턴] Facade 패턴으로 락과 트랜잭션 분리
     * ========================================================================
     *
     * 해결: 트랜잭션 커밋 완료 후에 락 해제
     * 결과: 정합성 보장 (항상 0)
     */
    @Test
    @Order(2)
    @DisplayName("[올바른 패턴] Facade 패턴 - 정합성 보장")
    void correctPattern_facadePattern() throws InterruptedException {
        // Given
        Ticket ticket = ticketRepository.saveAndFlush(new Ticket(INITIAL_QUANTITY));
        Long ticketId = ticket.getId();

        ExecutorService executorService = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);

        // When
        long startTime = System.currentTimeMillis();

        for (int i = 0; i < THREAD_COUNT; i++) {
            executorService.submit(() -> {
                try {
                    ticketFacade.decrease(ticketId);
                } catch (Exception e) {
                    log.error("Error: {}", e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();

        facadePatternDuration = System.currentTimeMillis() - startTime;

        // Then
        Ticket result = ticketRepository.findById(ticketId).orElseThrow();
        facadePatternFinalQuantity = result.getQuantity();

        log.info("");
        log.info("============================================================");
        log.info("[올바른 패턴] Facade 패턴 결과");
        log.info("============================================================");
        log.info("초기 티켓: {}", INITIAL_QUANTITY);
        log.info("동시 요청 수: {}", THREAD_COUNT);
        log.info("최종 티켓: {} (기대값: 0)", facadePatternFinalQuantity);
        log.info("정합성: {}", facadePatternFinalQuantity == 0 ? "✅ 보장" : "❌ 실패");
        log.info("소요 시간: {}ms", facadePatternDuration);
        log.info("============================================================");

        assertThat(facadePatternFinalQuantity).isEqualTo(0);

        executorService.shutdown();
    }

    /**
     * ========================================================================
     * 비교 요약
     * ========================================================================
     */
    @Test
    @Order(3)
    @DisplayName("패턴 비교 요약")
    void comparePatterns() {
        log.info("");
        log.info("╔══════════════════════════════════════════════════════════════════════════╗");
        log.info("║              트랜잭션과 락의 위험한 동거 - 테스트 결과                      ║");
        log.info("╠══════════════════════════════════════════════════════════════════════════╣");
        log.info("║                                                                          ║");
        log.info("║  ┌─────────────────────────┬────────────┬────────────┬────────────────┐ ║");
        log.info("║  │ 패턴                    │ 소요 시간  │ 최종 티켓  │ 정합성         │ ║");
        log.info("║  ├─────────────────────────┼────────────┼────────────┼────────────────┤ ║");
        log.info("║  │ @Transactional 내부 락  │ {}ms   │ {}     │ {}  │ ║",
                String.format("%6d", wrongPatternDuration),
                String.format("%6d", wrongPatternFinalQuantity),
                wrongPatternFinalQuantity == 0 ? "✅ 보장      " : "❌ 실패      ");
        log.info("║  │ Facade 패턴             │ {}ms   │ {}     │ {}  │ ║",
                String.format("%6d", facadePatternDuration),
                String.format("%6d", facadePatternFinalQuantity),
                facadePatternFinalQuantity == 0 ? "✅ 보장      " : "❌ 실패      ");
        log.info("║  └─────────────────────────┴────────────┴────────────┴────────────────┘ ║");
        log.info("║                                                                          ║");
        log.info("╠══════════════════════════════════════════════════════════════════════════╣");
        log.info("║  문제 원인                                                                ║");
        log.info("╠══════════════════════════════════════════════════════════════════════════╣");
        log.info("║  [잘못된 패턴] @Transactional 내부 락                                     ║");
        log.info("║  Thread A: 락획득 → 조회 → 감소 → 락해제 → 커밋대기... → 커밋            ║");
        log.info("║  Thread B:                       락획득 → 조회(과거!) → 감소 → 커밋      ║");
        log.info("║  → 락 해제 ~ 커밋 사이 GAP에서 정합성 깨짐                                ║");
        log.info("║                                                                          ║");
        log.info("║  [올바른 패턴] Facade 패턴                                                ║");
        log.info("║  Thread A: 락획득 → [Service: 조회→감소→커밋완료] → 락해제                ║");
        log.info("║  Thread B:                                        락획득 → 조회(최신!)   ║");
        log.info("║  → 커밋 완료 후 락 해제로 정합성 보장                                     ║");
        log.info("╚══════════════════════════════════════════════════════════════════════════╝");
        log.info("");
    }
}
