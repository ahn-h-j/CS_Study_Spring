package com.cos.cs_study_spring.lock;

import com.cos.cs_study_spring.repository.LettuceLockRepository;
import com.cos.cs_study_spring.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ============================================================================
 * Case 2: Pure Spin Lock (Lettuce SETNX 사용)
 * ============================================================================
 *
 * [동작 원리]
 * 1. Redis의 SETNX 명령으로 락 획득 시도
 * 2. 락 획득 실패 시, 성공할 때까지 즉시 재시도 (Spin)
 * 3. 락 획득 성공 시, 비즈니스 로직 수행 후 락 해제
 *
 * [장점]
 * - 구현이 매우 단순함
 * - 락 획득 지연시간(Latency)이 가장 짧음 (대기 없이 즉시 재시도)
 * - 추가 의존성 없음 (Spring Data Redis만 사용)
 *
 * [단점]
 * - ⚠️ Redis 서버에 극심한 부하 발생
 *   → 락 획득 실패 시 쉬지 않고 계속 요청 (Busy Waiting)
 *   → 동시 요청이 많을수록 Redis 서버 CPU 사용률 급증
 *
 * [Redis 서버 부하: 매우 높음 ⚠️⚠️⚠️]
 * ============================================================================
 * | 네트워크 호출 횟수: 락 획득까지 N번 + 락 해제 1번                          |
 * | N = 락 경쟁 중인 스레드 수에 비례                                         |
 * |                                                                          |
 * | 예시: 100개 스레드가 동시에 락 요청                                       |
 * | - 1개 스레드: 즉시 획득 (1번 호출)                                        |
 * | - 나머지 99개: 계속 재시도                                               |
 * | → 초당 수만~수십만 건의 Redis 명령 발생 가능                              |
 * |                                                                          |
 * | ⚡ 이 방식은 프로덕션에서 사용 금지!                                       |
 * | → Redis 서버 과부하로 전체 시스템 장애 유발 가능                          |
 * ============================================================================
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpinLockService {

    private final LettuceLockRepository lettuceLockRepository;
    private final StockRepository stockRepository;

    private static final String LOCK_KEY = "stock:lock";

    /**
     * Pure Spin Lock으로 재고 감소
     *
     * [Spin Lock 구조]
     * while (락_획득_실패) {
     *     락_획득_재시도();  // 쉬지 않고 계속 시도 (Busy Waiting)
     * }
     * 비즈니스_로직_수행();
     * 락_해제();
     */
    public void decrease(Long id) {
        // ========================================
        // 1. Spin Lock: 락 획득까지 무한 재시도
        // ========================================
        // ⚠️ 이 while 루프가 Redis 서버에 부하를 유발하는 핵심!
        // 락을 획득할 때까지 쉬지 않고 Redis에 SETNX 명령을 보냄
        while (!lettuceLockRepository.tryLock(LOCK_KEY)) {
            // 락 획득 실패 → 즉시 재시도 (Sleep 없음 = Busy Waiting)
            // 매 반복마다 Redis에 네트워크 요청 발생
        }

        try {
            // ========================================
            // 2. 임계 영역 (Critical Section)
            // ========================================
            // 락을 획득한 스레드만 이 코드를 실행
            actualDecrease(id);
        } finally {
            // ========================================
            // 3. 락 해제 (반드시 finally에서 실행)
            // ========================================
            lettuceLockRepository.unlock(LOCK_KEY);
        }
    }

    /**
     * 실제 재고 감소 로직 (별도 트랜잭션)
     * - 락은 이미 획득된 상태에서 호출됨
     * - @Transactional은 별도 메서드에 적용해야 프록시가 동작
     */
    @Transactional
    public void actualDecrease(Long id) {
        Stock stock = stockRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Stock not found"));
        stock.decrease();
        stockRepository.saveAndFlush(stock);
    }
}
