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
 * Decorrelated Jitter 전략
 * ============================================================================
 *
 * [공식]
 * sleep = min(cap, random(base, sleep_prev × 3))
 *
 * [특징]
 * - 이전 대기 시간(sleep_prev)을 기반으로 다음 대기 시간 계산
 * - attempt 횟수와 무관하게 이전 sleep 값에 의존
 * - 대기 시간이 "decorrelated" - 시도 횟수와 상관관계가 끊어짐
 *
 * [동작 예시] (base=10ms, cap=100ms)
 * ───────────────────────────────────────────────────────────────────────────
 * 초기값:    sleep_prev = 10 (base)
 * attempt=1: random(10, 30)  → 예: 18ms → sleep_prev = 18
 * attempt=2: random(10, 54)  → 예: 42ms → sleep_prev = 42
 * attempt=3: random(10, 100) → 예: 85ms → sleep_prev = 85 (cap 적용)
 * attempt=4: random(10, 100) → 예: 63ms → sleep_prev = 63
 * attempt=5: random(10, 100) → 예: 28ms → sleep_prev = 28 (감소 가능!)
 * ───────────────────────────────────────────────────────────────────────────
 *
 * [핵심 차이점]
 * ───────────────────────────────────────────────────────────────────────────
 *              │ Full/Equal Jitter           │ Decorrelated Jitter
 * ─────────────┼─────────────────────────────┼────────────────────────────
 * 의존성       │ attempt 횟수에 의존          │ 이전 sleep 값에 의존
 * 증가 패턴    │ 지수적 증가 (단조 증가)      │ 비단조적 (증가/감소 모두 가능)
 * 예측성       │ attempt로 예측 가능          │ 예측 불가 (히스토리 의존)
 * 리셋        │ attempt=0이면 항상 작은 값   │ 이전 값에 따라 다름
 * ───────────────────────────────────────────────────────────────────────────
 *
 * [장점]
 * - AWS에서 분석 결과, Full Jitter보다 완료 시간이 더 빠른 경우가 있음
 * - 대기 시간이 감소할 수 있어 운 좋으면 빠르게 획득
 * - 시도 간의 상관관계가 없어 Thundering Herd 분산에 효과적
 *
 * [단점]
 * - 구현이 상대적으로 복잡 (상태 유지 필요)
 * - 최악의 경우 계속 긴 대기 시간이 유지될 수 있음
 *
 * [Redis 서버 부하: 낮음~중간]
 * ============================================================================
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DecorrelatedJitterService {

    private final LettuceLockRepository lettuceLockRepository;
    private final StockRepository stockRepository;

    private static final String LOCK_KEY = "stock:lock:decorrelated-jitter";
    private static final long BASE_MS = 10;       // 기본/최소 대기 시간
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
     * Decorrelated Jitter 전략으로 재고 감소
     *
     * [Decorrelated Jitter 공식]
     * sleep = min(cap, random(base, sleep_prev × 3))
     *
     * 이전 대기 시간의 3배를 상한으로 랜덤 선택, cap으로 제한
     */
    public void decrease(Long id) throws InterruptedException {
        long sleepPrev = BASE_MS;  // 이전 대기 시간 (초기값 = base)

        // ========================================
        // 1. Spin Lock with Decorrelated Jitter
        // ========================================
        while (!lettuceLockRepository.tryLock(LOCK_KEY)) {
            retryCount.incrementAndGet();  // 재시도 횟수 증가

            // ────────────────────────────────────────
            // Decorrelated Jitter: min(cap, random(base, sleep_prev × 3))
            // ────────────────────────────────────────
            long maxBound = sleepPrev * 3;

            // random(base, sleep_prev × 3)
            // ThreadLocalRandom.nextLong(origin, bound)는 origin 이상, bound 미만
            long sleepTime;
            if (maxBound <= BASE_MS) {
                sleepTime = BASE_MS;
            } else {
                sleepTime = ThreadLocalRandom.current().nextLong(BASE_MS, maxBound + 1);
            }

            // cap 적용
            sleepTime = Math.min(CAP_MS, sleepTime);

            Thread.sleep(sleepTime);

            // 다음 반복을 위해 이전 sleep 값 갱신
            sleepPrev = sleepTime;
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
