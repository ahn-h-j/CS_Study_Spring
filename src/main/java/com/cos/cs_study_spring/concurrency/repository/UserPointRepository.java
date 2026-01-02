package com.cos.cs_study_spring.concurrency.repository;

import com.cos.cs_study_spring.concurrency.entity.UserPoint;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * UserPoint 레포지토리
 *
 * 일반 CRUD 및 비관적 락(Pessimistic Lock)을 위한 쿼리를 제공합니다.
 */
public interface UserPointRepository extends JpaRepository<UserPoint, Long> {

    /**
     * userId로 포인트 조회
     */
    Optional<UserPoint> findByUserId(Long userId);

    /**
     * 비관적 락을 사용한 조회 (SELECT ... FOR UPDATE)
     *
     * PESSIMISTIC_WRITE:
     * - 배타적 락(Exclusive Lock)을 획득
     * - 다른 트랜잭션은 해당 행을 읽거나 쓸 수 없음
     * - 트랜잭션이 커밋/롤백될 때까지 락 유지
     *
     * MySQL에서 실행되는 SQL:
     * SELECT * FROM user_point WHERE user_id = ? FOR UPDATE
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM UserPoint u WHERE u.userId = :userId")
    Optional<UserPoint> findByUserIdWithPessimisticLock(@Param("userId") Long userId);
}
