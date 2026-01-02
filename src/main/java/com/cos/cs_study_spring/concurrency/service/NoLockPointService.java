package com.cos.cs_study_spring.concurrency.service;

import com.cos.cs_study_spring.concurrency.entity.UserPoint;
import com.cos.cs_study_spring.concurrency.repository.UserPointRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 동시성 제어가 없는 포인트 서비스 (Race Condition 발생)
 *
 * 문제점:
 * 1. Thread A가 balance=1000 읽음
 * 2. Thread B가 balance=1000 읽음 (같은 값)
 * 3. Thread A가 balance=1100으로 저장 (+100)
 * 4. Thread B가 balance=1100으로 저장 (+100)
 * 결과: 200이 충전되어야 하는데 100만 충전됨 (Lost Update)
 *
 * 이 클래스는 동시성 문제를 증명하기 위한 베이스라인입니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NoLockPointService {

    private final UserPointRepository userPointRepository;

    /**
     * 포인트 충전 - 동시성 제어 없음 (망가진 코드)
     *
     * @param userId 사용자 ID
     * @param amount 충전 금액
     * @return 업데이트된 UserPoint
     */
    @Transactional
    public UserPoint charge(Long userId, Long amount) {
        // 1. 현재 포인트 조회
        UserPoint userPoint = userPointRepository.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다: " + userId));

        // 2. 현재 잔액을 로컬 변수에 저장 (동시성 이슈 유발 지점)
        Long currentBalance = userPoint.getBalance();

        // 3. 로직 수행 시간 시뮬레이션 (동시성 이슈 유발)
        //    이 사이에 다른 스레드가 같은 데이터를 읽을 수 있음
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 4. 잔액 업데이트 (이전에 읽은 값 기준으로 계산 - Lost Update 발생)
        userPoint.setBalance(currentBalance + amount);

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
