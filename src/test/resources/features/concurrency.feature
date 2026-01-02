Feature: Point Charging Concurrency Control Test
  To prevent concurrency issues in Point Charging API
  We verify 5 different concurrency control strategies

  Background:
    Given user initial balance is 0

  @NoLock
  Scenario: Case 0 - No Lock (Race Condition occurs)
    Given strategy is "NoLock"
    When 10 users charge 100 won concurrently
    Then final balance should be less than 1000 won
    And race condition should be detected

  @Synchronized
  Scenario: Case 1 - Java synchronized keyword
    Given strategy is "Synchronized"
    When 10 users charge 100 won concurrently
    Then final balance should be exactly 1000 won
    And consistency should be guaranteed

  @ReentrantLock
  Scenario: Case 2 - Java ReentrantLock
    Given strategy is "ReentrantLock"
    When 10 users charge 100 won concurrently
    Then final balance should be exactly 1000 won
    And consistency should be guaranteed

  @PessimisticLock
  Scenario: Case 3 - DB Pessimistic Lock (SELECT FOR UPDATE)
    Given strategy is "PessimisticLock"
    When 10 users charge 100 won concurrently
    Then final balance should be exactly 1000 won
    And consistency should be guaranteed

  @OptimisticLock
  Scenario: Case 4 - DB Optimistic Lock (@Version + Retry)
    Given strategy is "OptimisticLock"
    When 10 users charge 100 won concurrently
    Then final balance should be exactly 1000 won
    And consistency should be guaranteed
    And retry should have occurred

  @OptimisticLockRetry
  Scenario: Case 4-1 - Optimistic Lock - 5 users all succeed with retry
    Given strategy is "OptimisticLock"
    When 5 users charge 100 won with collision
    Then all 5 users should succeed with retry
    And final balance should be exactly 500 won

  @DistributedLock
  Scenario: Case 5 - Distributed Lock (FakeRedisLock)
    Given strategy is "DistributedLock"
    When 10 users charge 100 won concurrently
    Then final balance should be exactly 1000 won
    And consistency should be guaranteed
