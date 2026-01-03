package com.cos.cs_study_spring.isolationlevel;

import com.cos.cs_study_spring.isolationlevel.service.LoanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Non-Repeatable Read 시연 테스트: 대출 심사 시나리오
 *
 * READ COMMITTED: 매 SELECT마다 스냅샷 갱신 -> Non-Repeatable Read 발생
 * REPEATABLE READ: 트랜잭션 시작 시점의 스냅샷 유지 -> Non-Repeatable Read 방어
 */
@SpringBootTest
class NonRepeatableReadTest {

    @Autowired
    private LoanService loanService;

    private static final String CUSTOMER_ID = "customer_A";

    @BeforeEach
    void setUp() {
        loanService.setupInitialData(CUSTOMER_ID);
    }

    @Test
    @DisplayName("READ COMMITTED - Non-Repeatable Read 발생")
    void testReadCommitted_NonRepeatableRead() throws InterruptedException {
        System.out.println("\n=== Non-Repeatable Read 시연: READ COMMITTED ===\n");

        AtomicReference<long[]> resultRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(2);

        // 트랜잭션 A: 본점 대출 심사
        Thread threadA = new Thread(() -> {
            try {
                long[] amounts = loanService.processLoanWithReadCommitted(CUSTOMER_ID);
                resultRef.set(amounts);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                latch.countDown();
            }
        });

        // 트랜잭션 B: 다른 지점에서 대출 실행
        Thread threadB = new Thread(() -> {
            try {
                Thread.sleep(100); // A가 첫 조회 후 끼어듦
                loanService.insertLoan(CUSTOMER_ID, 1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                latch.countDown();
            }
        });

        threadA.start();
        threadB.start();
        latch.await();

        // 결과 검증
        long[] amounts = resultRef.get();
        System.out.println("\n=== 결과 ===");
        System.out.println("첫 번째 조회: " + amounts[0] + "만원");
        System.out.println("두 번째 조회: " + amounts[1] + "만원");

        // READ COMMITTED에서는 두 번째 조회 시 외부 커밋이 반영됨
        assertThat(amounts[0]).isEqualTo(8000L);
        assertThat(amounts[1]).isEqualTo(9500L);
        System.out.println("\n-> 같은 트랜잭션에서 같은 쿼리 결과가 다름 (Non-Repeatable Read)");
        System.out.println("-> 승인 문자 보냈는데 거절되는 UX 문제 발생");
    }

    @Test
    @DisplayName("REPEATABLE READ - Non-Repeatable Read 방어")
    void testRepeatableRead_Prevention() throws InterruptedException {
        System.out.println("\n=== Non-Repeatable Read 방어: REPEATABLE READ ===\n");

        AtomicReference<long[]> resultRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(2);

        // 트랜잭션 A: 본점 대출 심사
        Thread threadA = new Thread(() -> {
            try {
                long[] amounts = loanService.processLoanWithRepeatableRead(CUSTOMER_ID);
                resultRef.set(amounts);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                latch.countDown();
            }
        });

        // 트랜잭션 B: 다른 지점에서 대출 실행
        Thread threadB = new Thread(() -> {
            try {
                Thread.sleep(100); // A가 첫 조회 후 끼어듦
                loanService.insertLoan(CUSTOMER_ID, 1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                latch.countDown();
            }
        });

        threadA.start();
        threadB.start();
        latch.await();

        // 결과 검증
        long[] amounts = resultRef.get();
        System.out.println("\n=== 결과 ===");
        System.out.println("첫 번째 조회: " + amounts[0] + "만원");
        System.out.println("두 번째 조회: " + amounts[1] + "만원");

        // REPEATABLE READ에서는 MVCC 스냅샷이 유지됨
        assertThat(amounts[0]).isEqualTo(8000L);
        assertThat(amounts[1]).isEqualTo(8000L);
        System.out.println("\n-> 같은 트랜잭션에서 같은 쿼리 결과가 동일함 (MVCC 스냅샷 유지)");
        System.out.println("-> 문제 없이 승인 완료");
    }
}
