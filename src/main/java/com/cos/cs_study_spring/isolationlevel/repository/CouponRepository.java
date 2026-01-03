package com.cos.cs_study_spring.isolationlevel.repository;

import com.cos.cs_study_spring.isolationlevel.entity.Coupon;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

    @Query("SELECT COUNT(c) FROM Coupon c")
    int countAll();

    /**
     * FOR UPDATE는 Native Query로 직접 작성해야 함
     * JPA의 @Lock은 엔티티 조회에만 적용됨
     */
    @Query(value = "SELECT COUNT(*) FROM coupon FOR UPDATE", nativeQuery = true)
    int countAllForUpdate();
}
