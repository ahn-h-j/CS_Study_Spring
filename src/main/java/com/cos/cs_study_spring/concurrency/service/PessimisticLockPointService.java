package com.cos.cs_study_spring.concurrency.service;

import com.cos.cs_study_spring.concurrency.entity.UserPoint;
import com.cos.cs_study_spring.concurrency.repository.UserPointRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Strategy 3: 비관적 락(Pessimistic Lock) - SELECT FOR UPDATE
 *
 * 비관적 락 특징:
 * - 데이터를 읽을 때 DB 레벨에서 배타적 락(X-Lock)을 획득
 * - 트랜잭션이 끝날 때까지 다른 트랜잭션이 해당 행을 수정할 수 없음
 * - "충돌이 발생할 것"이라고 비관적으로 가정하고 미리 락을 잡음
 *
 * MySQL에서 실행되는 SQL:
 * SELECT * FROM user_point WHERE user_id = ? FOR UPDATE
 *
 * 장점:
 * - 데이터 정합성 강력하게 보장
 * - 분산 환경에서도 동작 (DB가 단일 지점인 경우)
 * - 재시도 로직 불필요
 *
 * 단점:
 * - DB에 락이 걸려 다른 트랜잭션 대기
 * - 데드락(Deadlock) 발생 가능성
 * - 처리량(Throughput) 저하
 *
 * 적합한 상황:
 * - 충돌이 빈번한 환경
 * - 재시도 비용이 큰 작업
 * - 데이터 정합성이 매우 중요한 경우 (금융 등)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PessimisticLockPointService {

    private final UserPointRepository userPointRepository;

    /**
     * 포인트 충전 - 비관적 락 사용
     *
     * @Transactional 범위 내에서 락이 유지됨
     * 락 획득 -> 조회 -> 수정 -> 저장 -> 커밋 -> 락 해제
     */
    @Transactional
    public UserPoint charge(Long userId, Long amount) {
        // 1. 비관적 락으로 조회 (SELECT ... FOR UPDATE)
        //    이 시점에서 해당 행에 대한 배타적 락 획득
        UserPoint userPoint = userPointRepository.findByUserIdWithPessimisticLock(userId)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다: " + userId));

        // 2. 현재 잔액 확인
        Long currentBalance = userPoint.getBalance();

        // 3. 로직 수행 시간 시뮬레이션
        //    다른 트랜잭션은 이 시간 동안 해당 행을 읽을 수 없음 (BLOCKED)
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 4. 잔액 업데이트
        userPoint.setBalance(currentBalance + amount);

        // 5. 저장 (트랜잭션 커밋 시 락 해제)
        return userPointRepository.save(userPoint);
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
