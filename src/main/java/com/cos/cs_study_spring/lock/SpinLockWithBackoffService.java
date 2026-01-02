package com.cos.cs_study_spring.lock;

import com.cos.cs_study_spring.repository.LettuceLockRepository;
import com.cos.cs_study_spring.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ============================================================================
 * Full Jitter 전략
 * ============================================================================
 *
 * [공식]
 * sleep = random(0, base × 2^attempt)
 *
 * [특징]
 * - 대기 시간을 완전히 랜덤화
 * - 0부터 최대값 사이의 어느 값이든 동일한 확률로 선택
 * - 재시도 시점이 매우 넓은 범위로 분산됨
 *
 * [동작 예시] (base=10ms, cap=100ms)
 * ───────────────────────────────────────────────────────────
 * attempt=0: random(0, 10)  → 예: 3ms, 7ms, 2ms
 * attempt=1: random(0, 20)  → 예: 5ms, 18ms, 1ms
 * attempt=2: random(0, 40)  → 예: 12ms, 35ms, 8ms
 * attempt=3: random(0, 80)  → 예: 45ms, 22ms, 70ms
 * attempt=4: random(0, 100) → cap 적용
 * ───────────────────────────────────────────────────────────
 *
 * [장점]
 * - Thundering Herd 문제 해결에 가장 효과적
 * - 재시도가 시간 축에서 균등하게 분포
 * - AWS 권장 전략 (AWS Architecture Blog)
 *
 * [단점]
 * - 평균 대기 시간이 짧아질 수 있음 (0에 가까운 값도 선택 가능)
 * - 운이 나쁘면 계속 짧은 대기 시간이 선택될 수 있음
 *
 * [Redis 서버 부하: 낮음~중간]
 * ============================================================================
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpinLockWithBackoffService {

    private final LettuceLockRepository lettuceLockRepository;
    private final StockRepository stockRepository;

    private static final String LOCK_KEY = "stock:lock:full-jitter";
    private static final long BASE_MS = 10;       // 기본 대기 시간
    private static final long CAP_MS = 100;       // 최대 대기 시간

    // 재시도 횟수 카운터
    private final AtomicLong retryCount = new AtomicLong(0);

    public long getRetryCount() {
        return retryCount.get();
    }

    public void resetRetryCount() {
        retryCount.set(0);
    }

    /**
     * Full Jitter 전략으로 재고 감소
     *
     * [Full Jitter 공식]
     * sleep = random(0, base × 2^attempt)
     *
     * 대기 시간을 0부터 지수적으로 증가하는 최대값 사이에서 완전 랜덤 선택
     */
    public void decrease(Long id) throws InterruptedException {
        int attempt = 0;

        // ========================================
        // 1. Spin Lock with Full Jitter
        // ========================================
        while (!lettuceLockRepository.tryLock(LOCK_KEY)) {
            retryCount.incrementAndGet();  // 재시도 횟수 증가

            // ────────────────────────────────────────
            // Full Jitter: random(0, base × 2^attempt)
            // ────────────────────────────────────────
            // attempt가 너무 크면 오버플로우 방지 (cap에서 이미 제한되므로 최대 시프트 제한)
            int safeAttempt = Math.min(attempt, 6);  // 2^6 = 64, base(10) * 64 = 640 > cap(100)
            long maxSleep = Math.min(CAP_MS, BASE_MS * (1L << safeAttempt));  // base × 2^attempt, cap 적용
            long sleepTime = ThreadLocalRandom.current().nextLong(maxSleep + 1);  // 0 ~ maxSleep

            Thread.sleep(sleepTime);
            attempt++;
        }

        try {
            // ========================================
            // 2. 임계 영역 (Critical Section)
            // ========================================
            actualDecrease(id);
        } finally {
            // ========================================
            // 3. 락 해제
            // ========================================
            lettuceLockRepository.unlock(LOCK_KEY);
        }
    }

    /**
     * 실제 재고 감소 로직 (별도 트랜잭션)
     */
    @Transactional
    public void actualDecrease(Long id) {
        Stock stock = stockRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Stock not found"));
        stock.decrease();
        stockRepository.saveAndFlush(stock);
    }
}
