package com.cos.cs_study_spring.facade;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * ============================================================================
 * [Facade Layer] 락의 획득/해제만 담당
 * ============================================================================
 *
 * [해결 원리]
 * 락과 트랜잭션의 책임을 물리적으로 분리!
 *
 * Facade (이 클래스): 락 획득 → Service 호출 → 락 해제
 * Service (TicketService): @Transactional로 비즈니스 로직 + 커밋
 *
 * [핵심]
 * Service의 @Transactional 메서드가 리턴되면 → 트랜잭션 커밋 완료
 * 그 후에 Facade에서 락 해제 → 다른 스레드가 최신 데이터 조회 가능
 *
 * [올바른 시나리오]
 *
 * 시간 →
 *
 * Thread A: [락 획득] → [Service.decrease() 호출]
 *                              ↓
 *                       [조회: 100] → [감소: 99] → [커밋 완료!] → [리턴]
 *                                                                  ↓
 *           [락 해제] ←←←←←←←←←←←←←←←←←←←←←←←←←←←←←←←←←←←←←←←←←←←←←←
 *                 ↓
 * Thread B: [락 획득] → [조회: 99 ← 커밋된 최신 데이터!] → [감소: 98] → [커밋]
 *
 * 결과: 정확하게 2개 감소됨 (100 → 99 → 98)
 *
 * ============================================================================
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketFacade {

    private final TicketService ticketService;  // 트랜잭션 담당
    private final RedissonClient redissonClient;

    private static final String LOCK_KEY = "ticket:lock:facade";

    /**
     * [올바른 구현] Facade에서 락, Service에서 트랜잭션
     *
     * 순서:
     * 1. 락 획득 (Facade)
     * 2. Service.decrease() 호출 → 트랜잭션 시작 → 로직 → 커밋 완료 → 리턴
     * 3. 락 해제 (Facade)
     *
     * 핵심: 커밋이 완료된 후에 락이 해제됨!
     */
    public void decrease(Long id) throws InterruptedException {
        RLock lock = redissonClient.getLock(LOCK_KEY);

        try {
            // ========================================
            // 1. 락 획득
            // ========================================
            boolean acquired = lock.tryLock(10, 3, TimeUnit.SECONDS);
            if (!acquired) {
                throw new RuntimeException("락 획득 실패");
            }

            // ========================================
            // 2. Service 호출 (트랜잭션 처리)
            // ========================================
            // ticketService.decrease()가 리턴되면:
            // → @Transactional 프록시가 커밋을 완료한 상태
            // → DB에 변경사항이 반영됨
            ticketService.decrease(id);

        } finally {
            // ========================================
            // 3. 락 해제 (커밋 완료 후!)
            // ========================================
            // 이 시점에서는 트랜잭션이 이미 커밋된 상태
            // 다음 스레드는 커밋된 최신 데이터를 조회하게 됨
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    public Long getQuantity(Long id) {
        return ticketService.getQuantity(id);
    }
}
