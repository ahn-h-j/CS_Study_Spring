package com.cos.cs_study_spring.facade;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

/**
 * ============================================================================
 * [잘못된 패턴] @Transactional 내부에서 락 획득/해제
 * ============================================================================
 *
 * [문제점]
 * Spring AOP 기반 @Transactional은 메서드 리턴 후에 커밋됨.
 * 락이 메서드 내부에서 해제되면, 커밋 전에 다른 스레드가 락을 획득할 수 있음.
 *
 * [문제 발생 시나리오]
 *
 * 시간 →
 *
 * Thread A: [락 획득] → [조회: 100] → [감소: 99] → [락 해제] → [커밋 대기...] → [커밋 완료]
 *                                                      ↓
 * Thread B:                                    [락 획득] → [조회: 100 ← 아직 커밋 안됨!]
 *                                                         → [감소: 99] → [커밋]
 *
 * 결과: 둘 다 99로 저장 → 1개만 감소됨 (2개가 감소되어야 함)
 *
 * ============================================================================
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WrongLockService {

    private final TicketRepository ticketRepository;
    private final RedissonClient redissonClient;

    private static final String LOCK_KEY = "ticket:lock:wrong";

    /**
     * [잘못된 구현] @Transactional 내부에서 락 처리
     *
     * 문제:
     * 1. 락 해제 시점: 메서드 내부 finally 블록
     * 2. 커밋 시점: 메서드 리턴 후 AOP 프록시에서
     * 3. 갭(Gap): 락 해제 ~ 커밋 사이에 다른 스레드 진입 가능
     */
    @Transactional
    public void decreaseWithLockInTransaction(Long id) throws InterruptedException {
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
            // 2. 비즈니스 로직 수행
            // ========================================
            Ticket ticket = ticketRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Ticket not found"));
            ticket.decrease();
            ticketRepository.saveAndFlush(ticket);

            // ========================================
            // 3. 락 해제 ⚠️ 여기가 문제!
            // ========================================
            // 락은 여기서 해제되지만...
            // 트랜잭션 커밋은 이 메서드가 리턴된 후 AOP에서 수행됨!
            // → 락 해제 ~ 커밋 사이에 다른 스레드가 과거 데이터를 읽을 수 있음

        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                // 이 시점: 락 해제됨, 하지만 트랜잭션은 아직 커밋 안됨!
            }
        }
        // 메서드 리턴 후 → Spring AOP가 트랜잭션 커밋 수행
    }
}
