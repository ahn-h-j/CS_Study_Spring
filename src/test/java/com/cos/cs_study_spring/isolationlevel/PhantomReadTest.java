package com.cos.cs_study_spring.isolationlevel;

import com.cos.cs_study_spring.isolationlevel.service.CouponService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phantom Read 시연 테스트: 쿠폰 발급 시나리오
 *
 * READ COMMITTED: 갭 락 없음 -> Phantom Read 발생
 * REPEATABLE READ + 일반 SELECT: MVCC가 유령 데이터 숨김
 * REPEATABLE READ + FOR UPDATE:
 *   - 처음부터 사용 시 갭 락으로 방어
 *   - 나중에 사용 시 MVCC 우회로 Phantom Read 발생
 */
@SpringBootTest
class PhantomReadTest {

    @Autowired
    private CouponService couponService;

    @BeforeEach
    void setUp() {
        couponService.setupInitialData(98);
    }

    @Test
    @DisplayName("READ COMMITTED - Phantom Read 발생")
    void testReadCommitted_PhantomRead() throws InterruptedException {
        System.out.println("\n=== Phantom Read 시연: READ COMMITTED ===\n");

        AtomicReference<int[]> resultRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(3);

        // 트랜잭션 A: 쿠폰 발급 확인
        Thread threadA = new Thread(() -> {
            try {
                int[] counts = couponService.checkCouponWithReadCommitted();
                resultRef.set(counts);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                latch.countDown();
            }
        });

        // 트랜잭션 B, C: 쿠폰 발급
        Thread threadB = new Thread(() -> {
            try {
                Thread.sleep(100);
                couponService.issueCoupon("user_B");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                latch.countDown();
            }
        });

        Thread threadC = new Thread(() -> {
            try {
                Thread.sleep(200);
                couponService.issueCoupon("user_C");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                latch.countDown();
            }
        });

        threadA.start();
        threadB.start();
        threadC.start();
        latch.await();

        int[] counts = resultRef.get();
        System.out.println("\n=== 결과 (READ COMMITTED) ===");
        System.out.println("첫 번째 SELECT:           " + counts[0] + "개");
        System.out.println("두 번째 SELECT:           " + counts[1] + "개");
        System.out.println("세 번째 SELECT FOR UPDATE: " + counts[2] + "개");
        System.out.println("\n-> READ COMMITTED는 매 SELECT마다 스냅샷 갱신, 갭 락 없음");
        System.out.println("-> 트랜잭션 도중 수량이 계속 변함 (Phantom Read)");

        assertThat(counts[0]).isEqualTo(98);
        assertThat(counts[2]).isEqualTo(100);
    }

    @Test
    @DisplayName("REPEATABLE READ + 일반 SELECT - MVCC가 Phantom 숨김")
    void testRepeatableRead_MvccProtection() throws InterruptedException {
        System.out.println("\n=== Phantom Read 방어: REPEATABLE READ (MVCC) ===\n");

        AtomicReference<int[]> resultRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(3);

        Thread threadA = new Thread(() -> {
            try {
                int[] counts = couponService.checkCouponWithRepeatableRead();
                resultRef.set(counts);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                latch.countDown();
            }
        });

        Thread threadB = new Thread(() -> {
            try {
                Thread.sleep(100);
                couponService.issueCoupon("user_B");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                latch.countDown();
            }
        });

        Thread threadC = new Thread(() -> {
            try {
                Thread.sleep(200);
                couponService.issueCoupon("user_C");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                latch.countDown();
            }
        });

        threadA.start();
        threadB.start();
        threadC.start();
        latch.await();

        int[] counts = resultRef.get();
        System.out.println("\n=== 결과 (REPEATABLE READ - MVCC 방어) ===");
        System.out.println("첫 번째 SELECT: " + counts[0] + "개 (MVCC 스냅샷 생성)");
        System.out.println("두 번째 SELECT: " + counts[1] + "개 (MVCC 스냅샷 유지)");
        System.out.println("세 번째 SELECT: " + counts[2] + "개 (MVCC 스냅샷 유지)");
        System.out.println("\n-> 일반 SELECT만 사용하면 MVCC가 유령 데이터를 숨김");
        System.out.println("-> 하지만 Write 작업 시에는 무의미");

        assertThat(counts[0]).isEqualTo(98);
        assertThat(counts[1]).isEqualTo(98);
        assertThat(counts[2]).isEqualTo(98);
    }

    @Test
    @DisplayName("REPEATABLE READ + MVCC 우회 - Phantom Read 발생")
    void testRepeatableRead_MvccBypass() throws InterruptedException {
        System.out.println("\n=== Phantom Read 시연: REPEATABLE READ (MVCC 우회) ===\n");

        AtomicReference<int[]> resultRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(3);

        Thread threadA = new Thread(() -> {
            try {
                int[] counts = couponService.checkCouponWithMvccBypass();
                resultRef.set(counts);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                latch.countDown();
            }
        });

        Thread threadB = new Thread(() -> {
            try {
                Thread.sleep(100);
                couponService.issueCoupon("user_B");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                latch.countDown();
            }
        });

        Thread threadC = new Thread(() -> {
            try {
                Thread.sleep(200);
                couponService.issueCoupon("user_C");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                latch.countDown();
            }
        });

        threadA.start();
        threadB.start();
        threadC.start();
        latch.await();

        int[] counts = resultRef.get();
        System.out.println("\n=== 결과 (REPEATABLE READ - MVCC 우회) ===");
        System.out.println("첫 번째 SELECT:           " + counts[0] + "개 (MVCC 스냅샷)");
        System.out.println("두 번째 SELECT:           " + counts[1] + "개 (MVCC 스냅샷)");
        System.out.println("세 번째 SELECT FOR UPDATE: " + counts[2] + "개 (Current Read)");
        System.out.println("\n-> FOR UPDATE는 MVCC 스냅샷이 아닌 실제 테이블을 읽음");
        System.out.println("-> MVCC 방어막을 뚫고 Phantom Read 발생");

        assertThat(counts[0]).isEqualTo(98);
        assertThat(counts[1]).isEqualTo(98);
        assertThat(counts[2]).isEqualTo(100);
    }

    @Test
    @DisplayName("REPEATABLE READ + Gap Lock - Phantom Read 방어")
    void testRepeatableRead_GapLock() throws InterruptedException {
        System.out.println("\n=== Phantom Read 방어: REPEATABLE READ (Gap Lock) ===\n");

        AtomicReference<int[]> resultRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(3);

        Thread threadA = new Thread(() -> {
            try {
                int[] counts = couponService.checkCouponWithGapLock();
                resultRef.set(counts);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                latch.countDown();
            }
        });

        // B, C는 A의 갭 락 때문에 대기
        Thread threadB = new Thread(() -> {
            try {
                Thread.sleep(100);
                couponService.issueCoupon("user_B");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                latch.countDown();
            }
        });

        Thread threadC = new Thread(() -> {
            try {
                Thread.sleep(200);
                couponService.issueCoupon("user_C");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                latch.countDown();
            }
        });

        threadA.start();
        threadB.start();
        threadC.start();
        latch.await();

        int[] counts = resultRef.get();
        System.out.println("\n=== 결과 (REPEATABLE READ + Gap Lock) ===");
        System.out.println("첫 번째 SELECT FOR UPDATE: " + counts[0] + "개 (Gap Lock 획득)");
        System.out.println("두 번째 SELECT:           " + counts[1] + "개");
        System.out.println("세 번째 SELECT FOR UPDATE: " + counts[2] + "개");
        System.out.println("\n-> 처음부터 FOR UPDATE로 Gap Lock 획득");
        System.out.println("-> 외부 INSERT가 차단되어 Phantom Read 방어");

        assertThat(counts[0]).isEqualTo(98);
        assertThat(counts[2]).isEqualTo(98);
    }
}
