package com.cos.cs_study_spring.concurrency.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 포인트 엔티티 - 낙관적 락(Optimistic Lock) 테스트용
 *
 * @Version 어노테이션을 사용하여 낙관적 락을 구현합니다.
 * - 데이터를 읽을 때 version을 함께 읽고
 * - 업데이트 시 version이 일치하는지 확인
 * - 일치하지 않으면 OptimisticLockException 발생
 * 장점:
 * - 락 경합이 적은 환경에서 높은 성능
 * - DB 락을 잡지 않아 다른 트랜잭션에 영향 없음
 * 단점:
 * - 충돌 시 재시도 로직 필요
 * - 충돌이 빈번한 환경에서는 비효율적
 */
@Entity
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserPointWithVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    private Long balance;

    /**
     * 낙관적 락을 위한 버전 필드
     * JPA가 자동으로 관리하며, UPDATE 시 WHERE 절에 version 조건이 추가됨
     * UPDATE user_point_with_version
     * SET balance = ?, version = version + 1
     * WHERE id = ? AND version = ?
     */
    @Version
    private Long version;

    public UserPointWithVersion(Long userId, Long balance) {
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
