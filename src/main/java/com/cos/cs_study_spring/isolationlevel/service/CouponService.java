package com.cos.cs_study_spring.isolationlevel.service;

import com.cos.cs_study_spring.isolationlevel.entity.Coupon;
import com.cos.cs_study_spring.isolationlevel.repository.CouponRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phantom Read 시연을 위한 쿠폰 서비스
 *
 * READ_COMMITTED: 갭 락 없음 -> Phantom Read 발생
 * REPEATABLE_READ + 일반 SELECT: MVCC가 유령 데이터 숨김
 * REPEATABLE_READ + FOR UPDATE:
 *   - 처음부터 사용 시 갭 락으로 방어
 *   - 나중에 사용 시 MVCC 우회로 Phantom Read 발생
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponService {

    private final CouponRepository couponRepository;

    /**
     * READ COMMITTED - Phantom Read 발생
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int[] checkCouponWithReadCommitted() throws InterruptedException {
        log.info("[트랜잭션 A] BEGIN (READ COMMITTED)");

        // 1. 첫 번째 조회
        int count1 = couponRepository.countAll();
        log.info("[트랜잭션 A] SELECT COUNT(*) -> {}개", count1);
        log.info("[트랜잭션 A] 판단: {}자리 남음, 발급 가능!", 100 - count1);

        // 다른 트랜잭션이 끼어들 시간
        Thread.sleep(500);

        // 2. 두 번째 조회 - 최신 커밋 데이터 반영
        int count2 = couponRepository.countAll();
        log.info("[트랜잭션 A] SELECT COUNT(*) -> {}개 (최신 커밋 데이터)", count2);

        // 3. 세 번째 조회 (FOR UPDATE)
        int count3 = couponRepository.countAllForUpdate();
        log.info("[트랜잭션 A] SELECT COUNT(*) FOR UPDATE -> {}개", count3);

        return new int[]{count1, count2, count3};
    }

    /**
     * REPEATABLE READ + 일반 SELECT - MVCC가 Phantom 숨김
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public int[] checkCouponWithRepeatableRead() throws InterruptedException {
        log.info("[트랜잭션 A] BEGIN (REPEATABLE READ)");

        // 1. 첫 번째 조회 (일반 SELECT) - MVCC 스냅샷 생성
        int count1 = couponRepository.countAll();
        log.info("[트랜잭션 A] SELECT COUNT(*) -> {}개 (MVCC 스냅샷)", count1);
        log.info("[트랜잭션 A] 판단: {}자리 남음, 발급 가능!", 100 - count1);

        // 다른 트랜잭션이 끼어들 시간
        Thread.sleep(500);

        // 2. 두 번째 조회 (일반 SELECT) - MVCC 스냅샷 유지
        int count2 = couponRepository.countAll();
        log.info("[트랜잭션 A] SELECT COUNT(*) -> {}개 (MVCC 스냅샷 유지)", count2);

        // 3. 세 번째 조회 (일반 SELECT) - 여전히 MVCC 스냅샷
        int count3 = couponRepository.countAll();
        log.info("[트랜잭션 A] SELECT COUNT(*) -> {}개 (MVCC 스냅샷 유지)", count3);

        return new int[]{count1, count2, count3};
    }

    /**
     * REPEATABLE READ + MVCC 우회 - Phantom Read 발생
     * 처음 일반 SELECT 후 나중에 FOR UPDATE 사용
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public int[] checkCouponWithMvccBypass() throws InterruptedException {
        log.info("[트랜잭션 A] BEGIN (REPEATABLE READ)");

        // 1. 첫 번째 조회 (일반 SELECT) - 갭 락 없음
        int count1 = couponRepository.countAll();
        log.info("[트랜잭션 A] SELECT COUNT(*) -> {}개 (MVCC 스냅샷)", count1);
        log.info("[트랜잭션 A] 판단: {}자리 남음, 발급 가능!", 100 - count1);

        // 다른 트랜잭션이 끼어들 시간
        Thread.sleep(500);

        // 2. 두 번째 조회 (일반 SELECT) - MVCC 스냅샷 유지
        int count2 = couponRepository.countAll();
        log.info("[트랜잭션 A] SELECT COUNT(*) -> {}개 (MVCC 스냅샷 유지)", count2);

        // 3. 세 번째 조회 (FOR UPDATE) - Current Read! MVCC 우회
        int count3 = couponRepository.countAllForUpdate();
        log.info("[트랜잭션 A] SELECT COUNT(*) FOR UPDATE -> {}개 (Current Read!)", count3);
        log.info("[트랜잭션 A] Phantom Read 발생! 실제로는 {}자리만 남음", 100 - count3);

        return new int[]{count1, count2, count3};
    }

    /**
     * REPEATABLE READ + Gap Lock - Phantom Read 방어
     * 처음부터 FOR UPDATE 사용
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public int[] checkCouponWithGapLock() throws InterruptedException {
        log.info("[트랜잭션 A] BEGIN (REPEATABLE READ)");

        // 1. 첫 번째 조회 (FOR UPDATE) - 갭 락 획득
        int count1 = couponRepository.countAllForUpdate();
        log.info("[트랜잭션 A] SELECT COUNT(*) FOR UPDATE -> {}개 (Gap Lock 획득)", count1);
        log.info("[트랜잭션 A] 판단: {}자리 남음, 발급 가능!", 100 - count1);

        // 다른 트랜잭션이 끼어들 시간 (갭 락으로 INSERT 차단됨)
        Thread.sleep(500);

        // 2. 두 번째 조회 (일반 SELECT)
        int count2 = couponRepository.countAll();
        log.info("[트랜잭션 A] SELECT COUNT(*) -> {}개", count2);

        // 3. 세 번째 조회 (FOR UPDATE)
        int count3 = couponRepository.countAllForUpdate();
        log.info("[트랜잭션 A] SELECT COUNT(*) FOR UPDATE -> {}개 (여전히 {}개)", count3, count1);

        return new int[]{count1, count2, count3};
    }

    /**
     * 쿠폰 발급 (새 트랜잭션)
     */
    @Transactional
    public void issueCoupon(String userId) {
        couponRepository.save(new Coupon(userId));
        log.info("[트랜잭션] {} INSERT + COMMIT 완료", userId);
    }

    /**
     * 초기 데이터 설정 (98개 쿠폰)
     */
    @Transactional
    public void setupInitialData(int count) {
        couponRepository.deleteAll();
        for (int i = 1; i <= count; i++) {
            couponRepository.save(new Coupon("user_" + i));
        }
        log.info("[초기화] 쿠폰 테이블 생성, {}개 데이터 삽입 완료", count);
    }
}
