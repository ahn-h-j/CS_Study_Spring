package com.cos.cs_study_spring.concurrency.service;

import com.cos.cs_study_spring.concurrency.entity.UserPoint;
import com.cos.cs_study_spring.concurrency.lock.FakeRedisLock;
import com.cos.cs_study_spring.concurrency.repository.UserPointRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.TimeUnit;

/**
 * Strategy 5: 분산 락(Distributed Lock) - FakeRedisLock 사용
 *
 * 분산 락 특징:
 * - 여러 서버(인스턴스)에서 공유 가능한 락
 * - 중앙 저장소(Redis, ZooKeeper 등)를 통해 락 상태 공유
 * - 수평 확장(Scale-out) 환경에서 동시성 제어 가능
 *
 * 실제 Redis 분산 락 구현 방식:
 * 1. Lettuce: SETNX + EXPIRE 조합 (Spin Lock)
 * 2. Redisson: RLock (Pub/Sub 기반, 권장)
 *
 * Lettuce (SETNX) 방식:
 *   SET lock:point:1 "owner" NX EX 30
 *   - NX: 키가 없을 때만 설정
 *   - EX 30: 30초 후 만료 (데드락 방지)
 *
 * Redisson (RLock) 방식:
 *   - Lua 스크립트로 원자적 락 획득
 *   - Pub/Sub으로 락 해제 알림 (Spin Lock 대비 효율적)
 *   - Watch Dog: 락 갱신으로 긴 작업 지원
 *
 * 이 클래스는 FakeRedisLock으로 Redisson RLock의 동작을 시뮬레이션합니다.
 *
 * 장점:
 * - 분산 환경에서 동시성 제어 가능
 * - TTL로 데드락 방지
 * - DB 락 대비 부하 분산
 *
 * 단점:
 * - Redis 장애 시 서비스 영향
 * - 네트워크 지연 추가
 * - 구현 복잡도 증가
 *
 * 주의: 분산 락과 @Transactional 함께 사용 시 문제
 * - 락 획득 -> 트랜잭션 시작 -> 로직 -> 커밋 -> 락 해제 순서 보장 필요
 * - TransactionTemplate을 사용하여 락 안에서 트랜잭션 관리
 */
@Slf4j
@Service
public class DistributedLockPointService {

    private final UserPointRepository userPointRepository;
    private final FakeRedisLock fakeRedisLock;
    private final TransactionTemplate transactionTemplate;

    private static final String LOCK_KEY_PREFIX = "lock:point:";
    private static final long LOCK_WAIT_TIMEOUT_SECONDS = 30;

    public DistributedLockPointService(UserPointRepository userPointRepository,
                                        FakeRedisLock fakeRedisLock,
                                        TransactionTemplate transactionTemplate) {
        this.userPointRepository = userPointRepository;
        this.fakeRedisLock = fakeRedisLock;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 포인트 충전 - 분산 락 + TransactionTemplate
     *
     * 순서: 락 획득 -> 트랜잭션 시작 -> 로직 -> 커밋 -> 락 해제
     */
    public UserPoint charge(Long userId, Long amount) {
        String lockKey = LOCK_KEY_PREFIX + userId;

        try {
            // 1. 락 획득 (블로킹, 타임아웃 적용)
            boolean acquired = fakeRedisLock.tryLock(
                    lockKey,
                    LOCK_WAIT_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            );

            if (!acquired) {
                throw new RuntimeException(
                        "락 획득 실패 (타임아웃). lockKey=" + lockKey +
                                ", timeout=" + LOCK_WAIT_TIMEOUT_SECONDS + "s"
                );
            }

            // 2. 트랜잭션 내에서 비즈니스 로직 실행
            return transactionTemplate.execute(status -> {
                // 2-1. 현재 포인트 조회
                UserPoint userPoint = userPointRepository.findByUserId(userId)
                        .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다: " + userId));

                // 2-2. 현재 잔액 확인
                Long currentBalance = userPoint.getBalance();

                // 2-3. 로직 수행 시간 시뮬레이션
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                // 2-4. 잔액 업데이트
                userPoint.setBalance(currentBalance + amount);

                // 2-5. 저장
                return userPointRepository.save(userPoint);
            });

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("락 획득 중 인터럽트 발생. lockKey=" + lockKey, e);
        } finally {
            // 3. 락 해제 (반드시 실행)
            if (fakeRedisLock.isLocked(lockKey)) {
                fakeRedisLock.unlock(lockKey);
            }
        }
    }

    /**
     * 포인트 조회
     */
    @Transactional(readOnly = true)
    public Long getBalance(Long userId) {
        return userPointRepository.findByUserId(userId)
                .map(UserPoint::getBalance)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다: " + userId));
    }

    /**
     * 초기화 - 테스트용
     */
    @Transactional
    public void init(Long userId, Long balance) {
        userPointRepository.findByUserId(userId)
                .ifPresentOrElse(
                        point -> point.setBalance(balance),
                        () -> userPointRepository.save(new UserPoint(userId, balance))
                );
    }
}
