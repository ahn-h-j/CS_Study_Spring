package com.cos.cs_study_spring.isolationlevel;

import com.cos.cs_study_spring.isolationlevel.service.InventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MySQL Lock 비교 테스트
 *
 * SERIALIZABLE vs READ_COMMITTED + SELECT FOR UPDATE 비교
 */
@SpringBootTest
class MySqlLockComparisonTest {

    @Autowired
    private InventoryService inventoryService;

    private static final Long INVENTORY_ID = 1L;
    private static final int INITIAL_STOCK = 1000;
    private static final int THREAD_COUNT = 100;

    @BeforeEach
    void setUp() {
        inventoryService.setupInitialData(INVENTORY_ID, INITIAL_STOCK);
    }

    @Test
    @DisplayName("SERIALIZABLE 격리 수준으로 재고 차감")
    void testSerializable() throws InterruptedException {
        System.out.println("\n=== [테스트 1: SERIALIZABLE 격리 수준] ===\n");

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        long start = System.currentTimeMillis();

        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.execute(() -> {
                try {
                    inventoryService.decreaseStockWithSerializable(INVENTORY_ID);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        long end = System.currentTimeMillis();
        int finalStock = inventoryService.getStock(INVENTORY_ID);

        System.out.println("-----------------------------------------");
        System.out.println("결과: SERIALIZABLE");
        System.out.println("- 소요 시간: " + (end - start) + "ms");
        System.out.println("- 성공: " + successCount.get() + "건 / 실패: " + failCount.get() + "건");
        System.out.println("- 최종 재고: " + finalStock);
        System.out.println("-----------------------------------------");

        // 성공 건수만큼 재고가 줄어야 함
        assertThat(finalStock).isEqualTo(INITIAL_STOCK - successCount.get());
    }

    @Test
    @DisplayName("READ COMMITTED + SELECT FOR UPDATE로 재고 차감")
    void testExplicitLock() throws InterruptedException {
        System.out.println("\n=== [테스트 2: READ COMMITTED + SELECT FOR UPDATE] ===\n");

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        long start = System.currentTimeMillis();

        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.execute(() -> {
                try {
                    inventoryService.decreaseStockWithExplicitLock(INVENTORY_ID);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        long end = System.currentTimeMillis();
        int finalStock = inventoryService.getStock(INVENTORY_ID);

        System.out.println("-----------------------------------------");
        System.out.println("결과: 명시적 락 (SELECT FOR UPDATE)");
        System.out.println("- 소요 시간: " + (end - start) + "ms");
        System.out.println("- 성공: " + successCount.get() + "건 / 실패: " + failCount.get() + "건");
        System.out.println("- 최종 재고: " + finalStock);
        System.out.println("-----------------------------------------");

        // 모든 요청이 성공해야 하고, 재고는 정확히 100 줄어야 함
        assertThat(successCount.get()).isEqualTo(THREAD_COUNT);
        assertThat(finalStock).isEqualTo(INITIAL_STOCK - THREAD_COUNT);
    }

    @Test
    @DisplayName("두 방식 비교 테스트")
    void compareBothMethods() throws InterruptedException {
        System.out.println("\n=== [비교 테스트: SERIALIZABLE vs 명시적 락] ===\n");

        // Test 1: SERIALIZABLE
        inventoryService.resetStock(INVENTORY_ID, INITIAL_STOCK);

        ExecutorService executor1 = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch1 = new CountDownLatch(THREAD_COUNT);
        AtomicInteger success1 = new AtomicInteger(0);
        AtomicInteger fail1 = new AtomicInteger(0);

        long start1 = System.currentTimeMillis();
        for (int i = 0; i < THREAD_COUNT; i++) {
            executor1.execute(() -> {
                try {
                    inventoryService.decreaseStockWithSerializable(INVENTORY_ID);
                    success1.incrementAndGet();
                } catch (Exception e) {
                    fail1.incrementAndGet();
                } finally {
                    latch1.countDown();
                }
            });
        }
        latch1.await();
        executor1.shutdown();
        long end1 = System.currentTimeMillis();
        int stock1 = inventoryService.getStock(INVENTORY_ID);

        // Test 2: Explicit Lock
        inventoryService.resetStock(INVENTORY_ID, INITIAL_STOCK);

        ExecutorService executor2 = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch2 = new CountDownLatch(THREAD_COUNT);
        AtomicInteger success2 = new AtomicInteger(0);
        AtomicInteger fail2 = new AtomicInteger(0);

        long start2 = System.currentTimeMillis();
        for (int i = 0; i < THREAD_COUNT; i++) {
            executor2.execute(() -> {
                try {
                    inventoryService.decreaseStockWithExplicitLock(INVENTORY_ID);
                    success2.incrementAndGet();
                } catch (Exception e) {
                    fail2.incrementAndGet();
                } finally {
                    latch2.countDown();
                }
            });
        }
        latch2.await();
        executor2.shutdown();
        long end2 = System.currentTimeMillis();
        int stock2 = inventoryService.getStock(INVENTORY_ID);

        // 결과 비교
        System.out.println("===========================================");
        System.out.println("              결과 비교");
        System.out.println("===========================================");
        System.out.println(String.format("%-20s | %-15s | %-15s", "", "SERIALIZABLE", "명시적 락"));
        System.out.println("-------------------------------------------");
        System.out.println(String.format("%-20s | %-15s | %-15s", "소요 시간", (end1 - start1) + "ms", (end2 - start2) + "ms"));
        System.out.println(String.format("%-20s | %-15s | %-15s", "성공 건수", success1.get() + "건", success2.get() + "건"));
        System.out.println(String.format("%-20s | %-15s | %-15s", "실패 건수", fail1.get() + "건", fail2.get() + "건"));
        System.out.println(String.format("%-20s | %-15s | %-15s", "최종 재고", stock1, stock2));
        System.out.println("===========================================");
        System.out.println("\n-> SERIALIZABLE: 데드락 발생 가능성 높음");
        System.out.println("-> 명시적 락: 안정적이고 예측 가능한 동작");
    }
}
