package com.cos.cs_study_spring.concurrency.service;

import com.cos.cs_study_spring.concurrency.entity.UserPoint;
import com.cos.cs_study_spring.concurrency.repository.UserPointRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Strategy 2: Java ReentrantLock을 사용한 동시성 제어
 *
 * ReentrantLock 특징:
 * - java.util.concurrent.locks 패키지의 명시적 락
 * - synchronized보다 더 세밀한 제어 가능
 * - 재진입(Reentrant) 가능: 같은 스레드가 이미 락을 보유 중이면 다시 획득 가능
 *
 * synchronized 대비 장점:
 * - tryLock(): 락 획득 시도 후 즉시 반환 (대기 시간 지정 가능)
 * - lockInterruptibly(): 인터럽트 가능한 락 획득
 * - Condition: 여러 대기 조건 생성 가능
 * - 공정성(Fairness) 설정 가능: new ReentrantLock(true)
 *
 * 단점:
 * - 명시적으로 unlock() 호출 필요 (try-finally 필수)
 * - 단일 서버에서만 동작 (분산 환경 불가)
 *
 * 주의: @Transactional과 ReentrantLock 함께 사용 시 문제
 * - TransactionTemplate을 사용하여 락 안에서 트랜잭션을 직접 관리해야 함
 * - 락 획득 -> 트랜잭션 시작 -> 로직 -> 커밋 -> 락 해제 순서 보장
 */
@Slf4j
@Service
public class ReentrantLockPointService {

    private final UserPointRepository userPointRepository;
    private final TransactionTemplate transactionTemplate;

    /**
     * ReentrantLock 인스턴스
     * - fair=false (기본값): 순서 보장 안됨, 높은 처리량
     * - fair=true: FIFO 순서 보장, 처리량 감소
     */
    private final ReentrantLock lock = new ReentrantLock();

    public ReentrantLockPointService(UserPointRepository userPointRepository,
                                      TransactionTemplate transactionTemplate) {
        this.userPointRepository = userPointRepository;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 포인트 충전 - ReentrantLock + TransactionTemplate
     *
     * try-finally 패턴으로 반드시 락 해제 보장
     * 순서: 락 획득 -> 트랜잭션 시작 -> 로직 -> 커밋 -> 락 해제
     */
    public UserPoint charge(Long userId, Long amount) {
        lock.lock();  // 락 획득 (블로킹)
        try {
            return transactionTemplate.execute(status -> {
                // 1. 현재 포인트 조회
                UserPoint userPoint = userPointRepository.findByUserId(userId)
                        .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다: " + userId));

                // 2. 현재 잔액 확인
                Long currentBalance = userPoint.getBalance();

                // 3. 로직 수행 시간 시뮬레이션
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                // 4. 잔액 업데이트
                userPoint.setBalance(currentBalance + amount);

                return userPointRepository.save(userPoint);
            });
        } finally {
            lock.unlock();  // 반드시 락 해제
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
