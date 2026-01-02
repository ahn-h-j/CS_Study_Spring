package com.cos.cs_study_spring.concurrency.service;

import com.cos.cs_study_spring.concurrency.entity.UserPoint;
import com.cos.cs_study_spring.concurrency.repository.UserPointRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Strategy 1: Java synchronized 키워드를 사용한 동시성 제어
 *
 * synchronized 키워드 특징:
 * - JVM 레벨에서 제공하는 암시적 락(Intrinsic Lock / Monitor Lock)
 * - 한 번에 하나의 스레드만 임계 구역(Critical Section)에 진입 가능
 * - 락을 획득하지 못한 스레드는 BLOCKED 상태로 대기
 *
 * 장점:
 * - 구현이 단순함
 * - JVM이 자동으로 락 해제 (예외 발생 시에도)
 *
 * 단점:
 * - 단일 서버에서만 동작 (분산 환경 불가)
 * - 락 획득 대기 시간 제어 불가 (tryLock 없음)
 * - 공정성(Fairness) 보장 안됨
 *
 * 주의: @Transactional과 synchronized 함께 사용 시 문제
 * - Spring 프록시는 트랜잭션 시작 후 실제 메서드 호출
 * - synchronized는 프록시 내부에서 동작하므로:
 *   트랜잭션 시작 -> synchronized 락 획득 -> 로직 -> synchronized 락 해제 -> 트랜잭션 커밋
 * - 락 해제 후 커밋 전에 다른 스레드가 읽을 수 있어 문제 발생!
 *
 * 해결: TransactionTemplate 사용
 * - synchronized 블록 안에서 프로그래밍 방식으로 트랜잭션 관리
 * - synchronized 락 획득 -> 트랜잭션 시작 -> 로직 -> 트랜잭션 커밋 -> synchronized 락 해제
 */
@Slf4j
@Service
public class SynchronizedPointService {

    private final UserPointRepository userPointRepository;
    private final TransactionTemplate transactionTemplate;

    public SynchronizedPointService(UserPointRepository userPointRepository,
                                     TransactionTemplate transactionTemplate) {
        this.userPointRepository = userPointRepository;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 포인트 충전 - synchronized + TransactionTemplate
     *
     * 순서: synchronized 락 획득 -> 트랜잭션 시작 -> 로직 -> 커밋 -> 락 해제
     * 이렇게 해야 락 해제 전에 커밋이 완료되어 다른 스레드가 올바른 값을 읽음
     */
    public synchronized UserPoint charge(Long userId, Long amount) {
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
