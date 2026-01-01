package com.cos.cs_study_spring.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 공유 자원: 재고 (Stock) - JPA 엔티티
 * - 동시성 문제를 확인하기 위한 공통 자원
 * - MySQL에 저장되며, 여러 스레드/프로세스가 동시에 접근
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Stock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long quantity;

    public Stock(Long quantity) {
        this.quantity = quantity;
    }

    /**
     * 재고 감소 (비원자적 연산)
     * - 읽기 -> 감소 -> 쓰기의 3단계로 구성
     * - 락이 없으면 Race Condition 발생 가능
     */
    public void decrease() {
        if (this.quantity <= 0) {
            throw new RuntimeException("재고가 부족합니다.");
        }
        this.quantity--;
    }
}
