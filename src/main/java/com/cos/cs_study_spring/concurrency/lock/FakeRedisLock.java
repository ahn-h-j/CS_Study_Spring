package com.cos.cs_study_spring.concurrency.lock;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * FakeRedisLock - Redis 분산 락 시뮬레이션
 *
 * 실제 Redis 분산 락(Redisson RLock 또는 Lettuce SETNX)의 동작을 모방합니다.
 * 실제 Redis 없이 분산 락의 개념과 동작을 테스트할 수 있습니다.
 *
 * 시뮬레이션하는 Redis 명령어:
 * - SETNX (SET if Not eXists): 키가 없으면 설정
 * - EXPIRE: 키에 TTL 설정
 * - DEL: 키 삭제
 *
 * 실제 Redis 분산 락의 특징:
 * - 여러 서버(인스턴스)에서 동일한 락 공유 가능
 * - TTL로 데드락 방지 (락 획득한 서버가 죽어도 자동 해제)
 * - 원자적 연산 (SETNX)으로 동시성 안전
 */
@Slf4j
@Component
public class FakeRedisLock {

    /**
     * 락 저장소 (Redis의 Key-Value 저장소 시뮬레이션)
     * Key: 락 이름 (예: "lock:point:1")
     * Value: 락 소유자 스레드 ID
     */
    private final ConcurrentHashMap<String, Long> lockStore = new ConcurrentHashMap<>();

    /**
     * 락별 조건 변수 (락 해제 시 대기 중인 스레드에게 알림)
     */
    private final ConcurrentHashMap<String, LockInfo> lockInfoMap = new ConcurrentHashMap<>();

    /**
     * 락 정보 (ReentrantLock + Condition for wait/notify)
     */
    private static class LockInfo {
        final ReentrantLock lock = new ReentrantLock();
        final Condition condition = lock.newCondition();
        final AtomicLong waitingThreads = new AtomicLong(0);
    }

    /**
     * 락 획득 시도 (Non-blocking)
     *
     * Redis SETNX 명령어와 유사
     * 키가 없으면 설정하고 true 반환, 있으면 false 반환
     *
     * @param lockKey 락 키 (예: "lock:point:userId")
     * @return 락 획득 성공 여부
     */
    public boolean tryLock(String lockKey) {
        Long currentThreadId = Thread.currentThread().getId();
        Long previousOwner = lockStore.putIfAbsent(lockKey, currentThreadId);

        if (previousOwner == null) {
            log.debug("[FakeRedis] 락 획득 성공: {} (thread={})", lockKey, currentThreadId);
            return true;
        } else if (previousOwner.equals(currentThreadId)) {
            // 재진입 (같은 스레드가 이미 락 보유)
            log.debug("[FakeRedis] 락 재진입: {} (thread={})", lockKey, currentThreadId);
            return true;
        }

        log.debug("[FakeRedis] 락 획득 실패: {} (owner={}, requester={})",
                lockKey, previousOwner, currentThreadId);
        return false;
    }

    /**
     * 락 획득 시도 (Blocking with timeout)
     *
     * Redis BLPOP과 유사하게 타임아웃까지 대기
     *
     * @param lockKey  락 키
     * @param timeout  최대 대기 시간
     * @param timeUnit 시간 단위
     * @return 락 획득 성공 여부
     */
    public boolean tryLock(String lockKey, long timeout, TimeUnit timeUnit) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + timeUnit.toNanos(timeout);

        // 즉시 획득 시도
        if (tryLock(lockKey)) {
            return true;
        }

        // 락 정보 가져오기 (없으면 생성)
        LockInfo lockInfo = lockInfoMap.computeIfAbsent(lockKey, k -> new LockInfo());

        lockInfo.lock.lock();
        try {
            lockInfo.waitingThreads.incrementAndGet();

            // 타임아웃까지 반복 시도
            while (true) {
                if (tryLock(lockKey)) {
                    return true;
                }

                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    log.debug("[FakeRedis] 락 타임아웃: {} (thread={})",
                            lockKey, Thread.currentThread().getId());
                    return false;
                }

                // 락 해제 신호 대기
                lockInfo.condition.await(
                        Math.min(remainingNanos, TimeUnit.MILLISECONDS.toNanos(50)),
                        TimeUnit.NANOSECONDS
                );
            }
        } finally {
            lockInfo.waitingThreads.decrementAndGet();
            lockInfo.lock.unlock();
        }
    }

    /**
     * 락 해제
     *
     * Redis DEL 명령어와 유사
     * 락을 소유한 스레드만 해제 가능
     *
     * @param lockKey 락 키
     */
    public void unlock(String lockKey) {
        Long currentThreadId = Thread.currentThread().getId();
        Long owner = lockStore.get(lockKey);

        if (owner == null) {
            log.warn("[FakeRedis] 존재하지 않는 락 해제 시도: {}", lockKey);
            return;
        }

        if (!owner.equals(currentThreadId)) {
            log.warn("[FakeRedis] 락 소유자가 아닌 스레드가 해제 시도: {} (owner={}, requester={})",
                    lockKey, owner, currentThreadId);
            return;
        }

        lockStore.remove(lockKey);
        log.debug("[FakeRedis] 락 해제: {} (thread={})", lockKey, currentThreadId);

        // 대기 중인 스레드들에게 알림
        LockInfo lockInfo = lockInfoMap.get(lockKey);
        if (lockInfo != null && lockInfo.waitingThreads.get() > 0) {
            lockInfo.lock.lock();
            try {
                lockInfo.condition.signalAll();
            } finally {
                lockInfo.lock.unlock();
            }
        }
    }

    /**
     * 현재 락 보유 여부 확인
     *
     * @param lockKey 락 키
     * @return 현재 스레드가 락을 보유하고 있는지 여부
     */
    public boolean isLocked(String lockKey) {
        Long owner = lockStore.get(lockKey);
        return owner != null && owner.equals(Thread.currentThread().getId());
    }

    /**
     * 모든 락 초기화 (테스트용)
     */
    public void clearAll() {
        lockStore.clear();
        lockInfoMap.clear();
        log.debug("[FakeRedis] 모든 락 초기화");
    }

}
