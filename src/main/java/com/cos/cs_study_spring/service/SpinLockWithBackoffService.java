package com.cos.cs_study_spring.service;

import com.cos.cs_study_spring.domain.Stock;
import com.cos.cs_study_spring.repository.LettuceLockRepository;
import com.cos.cs_study_spring.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.ThreadLocalRandom;

/**
 * ============================================================================
 * Case 3: Spin Lock with Exponential Backoff + Jitter
 * ============================================================================
 *
 * [동작 원리]
 * 1. Redis의 SETNX 명령으로 락 획득 시도
 * 2. 락 획득 실패 시, 일정 시간 대기 후 재시도
 * 3. 재시도할 때마다 대기 시간을 지수적으로 증가 (Exponential Backoff)
 * 4. ⭐ Jitter: 대기 시간에 랜덤 값을 추가하여 동시 재시도 분산
 *
 * [Jitter가 필요한 이유 - Thundering Herd 문제]
 * ─────────────────────────────────────────────
 * Jitter 없이 Backoff만 사용하면:
 *
 *   시간 →
 *   Thread A: [실패]────[20ms 대기]────[재시도]
 *   Thread B: [실패]────[20ms 대기]────[재시도]  ← 동시에 재시도!
 *   Thread C: [실패]────[20ms 대기]────[재시도]  ← 동시에 재시도!
 *                                      ↑
 *                           모든 스레드가 정확히 같은 시간에 재시도
 *                           → Redis에 순간적으로 부하 집중 (Thundering Herd)
 *
 * Jitter를 추가하면:
 *
 *   시간 →
 *   Thread A: [실패]──[18ms]──[재시도]
 *   Thread B: [실패]────[23ms]────[재시도]     ← 분산된 재시도!
 *   Thread C: [실패]──────[25ms]──────[재시도] ← 분산된 재시도!
 *                    └─────────────────────┘
 *                    랜덤 Jitter로 재시도 시점이 분산됨
 *                    → Redis 부하가 시간에 걸쳐 분산
 *
 * [장점]
 * - Pure Spin Lock 대비 Redis 서버 부하 대폭 감소
 * - Jitter로 Thundering Herd 문제 방지
 * - 구현이 비교적 단순함 (Pub/Sub 대비)
 *
 * [단점]
 * - 락 획득 지연시간(Latency) 증가
 * - 여전히 Polling 방식이므로 네트워크 호출 발생
 *
 * [Redis 서버 부하: 중간 ⚠️]
 * ============================================================================
 * | Backoff + Jitter 전략:                                                   |
 * | 실제 대기 시간 = backoff + random(0, backoff/2)                          |
 * |                                                                          |
 * | 예시 (backoff = 20ms, jitter 범위 = 0~10ms):                             |
 * | Thread A: 20 + 3 = 23ms                                                  |
 * | Thread B: 20 + 8 = 28ms                                                  |
 * | Thread C: 20 + 1 = 21ms                                                  |
 * | → 재시도 시점이 21ms ~ 28ms 사이로 분산됨                                 |
 * ============================================================================
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpinLockWithBackoffService {

    private final LettuceLockRepository lettuceLockRepository;
    private final StockRepository stockRepository;

    private static final String LOCK_KEY = "stock:lock:backoff";
    private static final long INITIAL_BACKOFF_MS = 10;  // 초기 대기 시간
    private static final long MAX_BACKOFF_MS = 100;     // 최대 대기 시간
    private static final double JITTER_FACTOR = 0.5;    // Jitter 비율 (50%)

    /**
     * Spin Lock with Exponential Backoff + Jitter로 재고 감소
     *
     * [Backoff + Jitter 전략]
     * - 재시도할 때마다 대기 시간을 2배씩 증가 (Exponential Backoff)
     * - 대기 시간에 랜덤 값을 추가하여 재시도 시점 분산 (Jitter)
     * - 최대 대기 시간(100ms) 제한으로 과도한 지연 방지
     */
    public void decrease(Long id) throws InterruptedException {
        long backoff = INITIAL_BACKOFF_MS;

        // ========================================
        // 1. Spin Lock with Backoff + Jitter
        // ========================================
        while (!lettuceLockRepository.tryLock(LOCK_KEY)) {
            // ────────────────────────────────────────
            // Jitter 계산: 0 ~ (backoff * 0.5) 사이의 랜덤 값
            // ────────────────────────────────────────
            // ThreadLocalRandom: 멀티스레드 환경에서 성능이 좋은 난수 생성기
            long jitter = ThreadLocalRandom.current().nextLong((long) (backoff * JITTER_FACTOR));

            // 실제 대기 시간 = 기본 backoff + 랜덤 jitter
            long sleepTime = backoff + jitter;
            Thread.sleep(sleepTime);

            // Exponential Backoff: 대기 시간 2배 증가
            backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
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
