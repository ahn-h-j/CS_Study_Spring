package com.cos.cs_study_spring.lock;

import com.cos.cs_study_spring.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ============================================================================
 * Case 1: No Lock (비교군)
 * ============================================================================
 *
 * [문제점]
 * - 락 없이 공통 자원(재고)을 업데이트
 * - 여러 스레드가 동시에 decrease()를 호출하면 Race Condition 발생
 * - 예: 100개 재고에서 100개 스레드가 1개씩 감소시키면 0이 되어야 하지만,
 *       실제로는 0보다 큰 값이 남을 수 있음
 *
 * [Race Condition 발생 원인]
 * decrease() 연산은 3단계로 구성:
 *   1. DB에서 Stock 조회 (SELECT)
 *   2. quantity - 1 계산 (Java)
 *   3. DB에 저장 (UPDATE)
 *
 * 스레드 A와 B가 동시에 실행되면:
 *   Thread A: SELECT(100) -> COMPUTE(99) -> UPDATE(99)
 *   Thread B: SELECT(100) -> COMPUTE(99) -> UPDATE(99)  <- A의 UPDATE 전에 SELECT!
 *   결과: 100 -> 99 (1개만 감소, 2개가 감소해야 함)
 *
 * [Redis 서버 부하]
 * - Redis 호출 없음
 * - 네트워크 오버헤드 없음
 * ============================================================================
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NoLockService {

    private final StockRepository stockRepository;

    /**
     * 락 없이 재고 감소
     * - Race Condition으로 인해 정합성 보장 불가
     */
    @Transactional
    public void decrease(Long id) {
        Stock stock = stockRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Stock not found"));
        stock.decrease();
        stockRepository.saveAndFlush(stock);
    }
}
