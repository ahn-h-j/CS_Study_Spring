package com.cos.cs_study_spring.service;

import com.cos.cs_study_spring.domain.Stock;
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
 * Equal Jitter 전략
 * ============================================================================
 *
 * [공식]
 * sleep = (base × 2^attempt)/2 + random(0, (base × 2^attempt)/2)
 *
 * [특징]
 * - 대기 시간의 절반은 고정, 절반은 랜덤
 * - Full Jitter보다 최소 대기 시간이 보장됨
 * - 지수적 증가와 랜덤성의 균형
 *
 * [동작 예시] (base=10ms, cap=100ms)
 * ───────────────────────────────────────────────────────────────────────────
 * attempt=0: 10/2 + random(0, 5)  = 5 + random(0,5)  → 범위: 5~10ms
 * attempt=1: 20/2 + random(0, 10) = 10 + random(0,10) → 범위: 10~20ms
 * attempt=2: 40/2 + random(0, 20) = 20 + random(0,20) → 범위: 20~40ms
 * attempt=3: 80/2 + random(0, 40) = 40 + random(0,40) → 범위: 40~80ms
 * attempt=4: 100/2 + random(0, 50) = 50 + random(0,50) → 범위: 50~100ms (cap)
 * ───────────────────────────────────────────────────────────────────────────
 *
 * [Full Jitter vs Equal Jitter 비교]
 * ───────────────────────────────────────────────────────────────────────────
 *         │ Full Jitter              │ Equal Jitter
 * ────────┼──────────────────────────┼──────────────────────────────
 * 범위    │ 0 ~ maxSleep             │ maxSleep/2 ~ maxSleep
 * 최소값  │ 0 (짧은 대기 가능)        │ maxSleep/2 (최소 보장)
 * 평균    │ maxSleep/2               │ maxSleep * 0.75
 * 분산    │ 매우 넓음                 │ 좁음 (위쪽 절반에 집중)
 * ───────────────────────────────────────────────────────────────────────────
 *
 * [장점]
 * - 최소 대기 시간이 보장되어 재시도 간격이 너무 짧아지지 않음
 * - Backoff 효과를 유지하면서 랜덤성 추가
 * - 예측 가능한 최소 지연시간
 *
 * [단점]
 * - Full Jitter보다 Thundering Herd 분산 효과가 약함
 * - 대기 시간이 상대적으로 길어질 수 있음
 *
 * [Redis 서버 부하: 중간]
 * ============================================================================
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EqualJitterService {

    private final LettuceLockRepository lettuceLockRepository;
    private final StockRepository stockRepository;

    private static final String LOCK_KEY = "stock:lock:equal-jitter";
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
     * Equal Jitter 전략으로 재고 감소
     *
     * [Equal Jitter 공식]
     * sleep = (base × 2^attempt)/2 + random(0, (base × 2^attempt)/2)
     *
     * 절반은 고정값으로 최소 대기를 보장하고, 나머지 절반을 랜덤화
     */
    public void decrease(Long id) throws InterruptedException {
        int attempt = 0;

        // ========================================
        // 1. Spin Lock with Equal Jitter
        // ========================================
        while (!lettuceLockRepository.tryLock(LOCK_KEY)) {
            retryCount.incrementAndGet();  // 재시도 횟수 증가

            // ────────────────────────────────────────
            // Equal Jitter: half + random(0, half)
            // ────────────────────────────────────────
            // attempt가 너무 크면 오버플로우 방지
            int safeAttempt = Math.min(attempt, 6);  // 2^6 = 64, base(10) * 64 = 640 > cap(100)
            long exponentialBackoff = Math.min(CAP_MS, BASE_MS * (1L << safeAttempt));  // base × 2^attempt
            long half = exponentialBackoff / 2;
            long jitter = ThreadLocalRandom.current().nextLong(half + 1);  // 0 ~ half
            long sleepTime = half + jitter;  // half + random(0, half)

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
