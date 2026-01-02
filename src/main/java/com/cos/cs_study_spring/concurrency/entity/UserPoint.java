package com.cos.cs_study_spring.concurrency.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 포인트 엔티티 - 동시성 테스트용
 *
 * 이 엔티티는 동시성 제어 전략을 테스트하기 위한 기본 엔티티입니다.
 * - synchronized, ReentrantLock, Pessimistic Lock에서 사용
 * - Optimistic Lock은 @Version이 필요하므로 별도 엔티티 사용
 */
@Entity
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserPoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    private Long balance;

    public UserPoint(Long userId, Long balance) {
        this.userId = userId;
        this.balance = balance;
    }

    /**
     * 포인트 충전
     *
     * @param amount 충전할 금액
     */
    public void charge(Long amount) {
        this.balance += amount;
    }
}
