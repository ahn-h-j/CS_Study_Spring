package com.cos.cs_study_spring.concurrency.repository;

import com.cos.cs_study_spring.concurrency.entity.UserPointWithVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * UserPointWithVersion 레포지토리
 *
 * 낙관적 락(@Version)을 사용하는 엔티티를 위한 레포지토리입니다.
 * - JPA가 @Version 필드를 자동으로 관리
 * - UPDATE 시 version 불일치하면 OptimisticLockException 발생
 */
public interface UserPointWithVersionRepository extends JpaRepository<UserPointWithVersion, Long> {

    /**
     * userId로 포인트 조회
     */
    Optional<UserPointWithVersion> findByUserId(Long userId);
}
