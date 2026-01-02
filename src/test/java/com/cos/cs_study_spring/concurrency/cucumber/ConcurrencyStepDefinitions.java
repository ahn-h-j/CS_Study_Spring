package com.cos.cs_study_spring.concurrency.cucumber;

import com.cos.cs_study_spring.concurrency.lock.FakeRedisLock;
import com.cos.cs_study_spring.concurrency.repository.UserPointRepository;
import com.cos.cs_study_spring.concurrency.repository.UserPointWithVersionRepository;
import com.cos.cs_study_spring.concurrency.service.*;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cucumber Step Definitions for Concurrency Control Tests
 *
 * 5가지 동시성 제어 전략을 BDD 방식으로 검증합니다.
 * - ExecutorService: 스레드 풀 관리
 * - CountDownLatch: 스레드 동기화 (동시 시작 + 완료 대기)
 */
@Slf4j
public class ConcurrencyStepDefinitions {

    @Autowired
    private NoLockPointService noLockPointService;

    @Autowired
    private SynchronizedPointService synchronizedPointService;

    @Autowired
    private ReentrantLockPointService reentrantLockPointService;

    @Autowired
    private PessimisticLockPointService pessimisticLockPointService;

    @Autowired
    private OptimisticLockPointService optimisticLockPointService;

    @Autowired
    private DistributedLockPointService distributedLockPointService;

    @Autowired
    private UserPointRepository userPointRepository;

    @Autowired
    private UserPointWithVersionRepository userPointWithVersionRepository;

    @Autowired
    private FakeRedisLock fakeRedisLock;

    // 테스트 상태
    private String currentStrategy;
    private Long userId;
    private Long finalBalance;
    private Long expectedBalance;
    private int threadCount;
    private long chargeAmount;
    private AtomicInteger successCount;
    private AtomicInteger failCount;
    private long retryCount;
    private long executionTimeMs;

    private static final Long BASE_USER_ID = 100L;
    private static long userIdCounter = 0;

    @Before
    public void setUp() {
        userId = BASE_USER_ID + (++userIdCounter);
        successCount = new AtomicInteger(0);
        failCount = new AtomicInteger(0);
        retryCount = 0;
        fakeRedisLock.clearAll();
    }

    @After
    public void tearDown() {
        userPointRepository.deleteAll();
        userPointWithVersionRepository.deleteAll();
    }

    // ========================================================================
    // Given
    // ========================================================================

    @Given("user initial balance is {int}")
    public void user_initial_balance_is(int initialBalance) {
        log.info("========================================");
        log.info("[Setup] userId={}, initialBalance={}", userId, initialBalance);
    }

    @Given("strategy is {string}")
    public void strategy_is(String strategy) {
        this.currentStrategy = strategy;
        log.info("[Strategy] {}", strategy);

        // 전략에 따라 초기화
        switch (strategy) {
            case "NoLock" -> noLockPointService.init(userId, 0L);
            case "Synchronized" -> synchronizedPointService.init(userId, 0L);
            case "ReentrantLock" -> reentrantLockPointService.init(userId, 0L);
            case "PessimisticLock" -> pessimisticLockPointService.init(userId, 0L);
            case "OptimisticLock" -> {
                optimisticLockPointService.init(userId, 0L);
                optimisticLockPointService.resetRetryCount();
            }
            case "DistributedLock" -> distributedLockPointService.init(userId, 0L);
            default -> throw new IllegalArgumentException("Unknown strategy: " + strategy);
        }
    }

    // ========================================================================
    // When
    // ========================================================================

    @When("{int} users charge {long} won concurrently")
    public void users_charge_won_concurrently(int count, Long amount) throws InterruptedException {
        this.threadCount = count;
        this.chargeAmount = amount;
        this.expectedBalance = (long) count * amount;

        log.info("[Concurrent Charging] {} users x {} won = expected {} won", count, amount, expectedBalance);

        // ExecutorService: 스레드 풀 생성
        ExecutorService executorService = Executors.newFixedThreadPool(count);

        // CountDownLatch: 모든 스레드가 동시에 시작하도록 동기화
        CountDownLatch startLatch = new CountDownLatch(1);
        // CountDownLatch: 모든 스레드 완료 대기
        CountDownLatch endLatch = new CountDownLatch(count);

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < count; i++) {
            executorService.submit(() -> {
                try {
                    // 모든 스레드가 준비될 때까지 대기
                    startLatch.await();

                    // 전략에 따라 충전 실행
                    executeCharge(amount);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    log.warn("Charge failed: {}", e.getMessage());
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // 모든 스레드 동시 시작
        startLatch.countDown();

        // 모든 스레드 완료 대기
        endLatch.await();

        executionTimeMs = System.currentTimeMillis() - startTime;
        executorService.shutdown();

        // 최종 잔액 조회
        finalBalance = getBalance();

        // Optimistic Lock의 경우 재시도 횟수 기록
        if ("OptimisticLock".equals(currentStrategy)) {
            retryCount = optimisticLockPointService.getTotalRetryCount();
        }

        log.info("[Result] success={}, fail={}, finalBalance={} won, time={}ms",
                successCount.get(), failCount.get(), finalBalance, executionTimeMs);
    }

    @When("{int} users charge {long} won with collision")
    public void users_charge_won_with_collision(int count, Long amount) throws InterruptedException {
        // 동일한 로직 사용
        users_charge_won_concurrently(count, amount);
    }

    // ========================================================================
    // Then
    // ========================================================================

    @Then("final balance should be less than {long} won")
    public void final_balance_should_be_less_than_won(Long expected) {
        log.info("[Verify] finalBalance({}) < {} won", finalBalance, expected);
        assertThat(finalBalance).isLessThan(expected);
        log.info("[PASS] Race Condition detected - lost {} won", expected - finalBalance);
    }

    @Then("final balance should be exactly {long} won")
    public void final_balance_should_be_exactly_won(Long expected) {
        log.info("[Verify] finalBalance({}) == {} won", finalBalance, expected);
        assertThat(finalBalance).isEqualTo(expected);
        log.info("[PASS] Consistency guaranteed");
    }

    @Then("all {int} users should succeed with retry")
    public void all_users_should_succeed_with_retry(int count) {
        log.info("[Verify] All {} users succeeded (success={}, fail={})", count, successCount.get(), failCount.get());
        assertThat(successCount.get()).isEqualTo(count);
        assertThat(failCount.get()).isEqualTo(0);
        log.info("[PASS] All {} users succeeded with {} retries", count, retryCount);
    }

    @And("race condition should be detected")
    public void race_condition_should_be_detected() {
        long lostAmount = expectedBalance - finalBalance;
        log.info("========================================");
        log.info("[NoLock] Race Condition Detected");
        log.info("  - Expected: {} won", expectedBalance);
        log.info("  - Actual:   {} won", finalBalance);
        log.info("  - Lost:     {} won ({}% loss)", lostAmount, (lostAmount * 100) / expectedBalance);
        log.info("  - Time:     {} ms", executionTimeMs);
        log.info("========================================");

        assertThat(finalBalance).isLessThan(expectedBalance);
    }

    @And("consistency should be guaranteed")
    public void consistency_should_be_guaranteed() {
        log.info("========================================");
        log.info("[{}] Consistency Guaranteed", currentStrategy);
        log.info("  - Expected: {} won", expectedBalance);
        log.info("  - Actual:   {} won", finalBalance);
        log.info("  - Success/Fail: {}/{}", successCount.get(), failCount.get());
        log.info("  - Time:     {} ms", executionTimeMs);
        log.info("========================================");

        assertThat(finalBalance).isEqualTo(expectedBalance);
    }

    @And("retry should have occurred")
    public void retry_should_have_occurred() {
        log.info("========================================");
        log.info("[OptimisticLock] Retry Occurred");
        log.info("  - Retry count: {}", retryCount);
        log.info("  - Concurrent requests: {}", threadCount);
        log.info("========================================");

        // 동시 요청이 있으면 충돌로 인한 재시도가 발생해야 함
        assertThat(retryCount).isGreaterThan(0);
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private void executeCharge(Long amount) {
        switch (currentStrategy) {
            case "NoLock" -> noLockPointService.charge(userId, amount);
            case "Synchronized" -> synchronizedPointService.charge(userId, amount);
            case "ReentrantLock" -> reentrantLockPointService.charge(userId, amount);
            case "PessimisticLock" -> pessimisticLockPointService.charge(userId, amount);
            case "OptimisticLock" -> optimisticLockPointService.charge(userId, amount);
            case "DistributedLock" -> distributedLockPointService.charge(userId, amount);
            default -> throw new IllegalArgumentException("Unknown strategy: " + currentStrategy);
        }
    }

    private Long getBalance() {
        return switch (currentStrategy) {
            case "NoLock" -> noLockPointService.getBalance(userId);
            case "Synchronized" -> synchronizedPointService.getBalance(userId);
            case "ReentrantLock" -> reentrantLockPointService.getBalance(userId);
            case "PessimisticLock" -> pessimisticLockPointService.getBalance(userId);
            case "OptimisticLock" -> optimisticLockPointService.getBalance(userId);
            case "DistributedLock" -> distributedLockPointService.getBalance(userId);
            default -> throw new IllegalArgumentException("Unknown strategy: " + currentStrategy);
        };
    }
}
