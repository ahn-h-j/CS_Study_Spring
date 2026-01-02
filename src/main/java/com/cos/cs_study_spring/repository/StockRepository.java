package com.cos.cs_study_spring.repository;

import com.cos.cs_study_spring.lock.Stock;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 재고 JPA 저장소
 * - MySQL에 저장된 Stock 엔티티 관리
 * - 분산 락 테스트를 위한 공유 자원 역할
 */
public interface StockRepository extends JpaRepository<Stock, Long> {
}
