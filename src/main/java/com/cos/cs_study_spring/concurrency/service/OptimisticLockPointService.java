package com.cos.cs_study_spring.concurrency.service;

import com.cos.cs_study_spring.concurrency.entity.UserPointWithVersion;
import com.cos.cs_study_spring.concurrency.repository.UserPointWithVersionRepository;
import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Strategy 4: 낙관적 락(Optimistic Lock) - @Version + 재시도
 *
 * 낙관적 락 특징:
 * - 데이터를 읽을 때 락을 잡지 않음
 * - 업데이트 시 version을 확인하여 충돌 감지
 * - "충돌이 거의 없을 것"이라고 낙관적으로 가정
 *
 * JPA가 실행하는 SQL:
 * UPDATE user_point_with_version
 * SET balance = ?, version = version + 1
 * WHERE id = ? AND version = ?
 *
 * 동작 원리:
 * 1. 읽기 시 version=1 함께 읽음
 * 2. 수정 후 저장 시 WHERE version=1 조건으로 UPDATE
 * 3. 다른 트랜잭션이 먼저 version=2로 수정했다면 UPDATE 실패
 * 4. OptimisticLockException 발생 -> 재시도
 *
 * 장점:
 * - 락 없이 높은 동시성
 * - DB 부하 감소
 * - 분산 환경에서도 동작
 *
 * 단점:
 * - 충돌 시 재시도 로직 필수
 * - 충돌이 빈번하면 재시도 비용 증가
 *
 * 적합한 상황:
 * - 읽기가 많고 쓰기가 적은 환경
 * - 충돌 빈도가 낮은 환경
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OptimisticLockPointService {

    private final UserPointWithVersionRepository userPointWithVersionRepository;
    private final TransactionTemplate transactionTemplate; // 주입 필요

    private static final int MAX_RETRIES = 200;
    private static final long RETRY_DELAY_MS = 30;

    // 재시도 횟수 추적 (테스트/모니터링용)
    private final AtomicLong totalRetryCount = new AtomicLong(0);

    /**
     * 포인트 충전 - 낙관적 락 + 재시도 패턴
     *
     * 충돌 발생 시 최대 MAX_RETRIES까지 재시도
     */
    public UserPointWithVersion charge(Long userId, Long amount) {
        int retries = 0;

        while (retries < MAX_RETRIES) {
            try {
                return transactionTemplate.execute(status -> {
                    UserPointWithVersion userPoint = userPointWithVersionRepository.findByUserId(userId)
                            .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));

                    userPoint.setBalance(userPoint.getBalance() + amount);

                    return userPointWithVersionRepository.saveAndFlush(userPoint);
                });
            } catch (OptimisticLockException | ObjectOptimisticLockingFailureException e) {
                retries++;
                totalRetryCount.incrementAndGet();
                log.debug("낙관적 락 충돌 발생. 재시도 {}/{}", retries, MAX_RETRIES);

                if (retries >= MAX_RETRIES) {
                    throw new RuntimeException(
                            "최대 재시도 횟수(" + MAX_RETRIES + ") 초과. userId=" + userId, e);
                }

                // 재시도 전 잠시 대기 (경합 완화)
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("재시도 중 인터럽트 발생", ie);
                }
            }
        }

        throw new RuntimeException("포인트 충전 실패. userId=" + userId);
    }

    /**
     * 포인트 조회
     */
    @Transactional(readOnly = true)
    public Long getBalance(Long userId) {
        return userPointWithVersionRepository.findByUserId(userId)
                .map(UserPointWithVersion::getBalance)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다: " + userId));
    }

    /**
     * 초기화 - 테스트용
     */
    @Transactional
    public void init(Long userId, Long balance) {
        userPointWithVersionRepository.findByUserId(userId)
                .ifPresentOrElse(
                        point -> point.setBalance(balance),
                        () -> userPointWithVersionRepository.save(new UserPointWithVersion(userId, balance))
                );
    }

    /**
     * 재시도 횟수 조회 (테스트/모니터링용)
     */
    public long getTotalRetryCount() {
        return totalRetryCount.get();
    }

    /**
     * 재시도 횟수 초기화
     */
    public void resetRetryCount() {
        totalRetryCount.set(0);
    }
}
