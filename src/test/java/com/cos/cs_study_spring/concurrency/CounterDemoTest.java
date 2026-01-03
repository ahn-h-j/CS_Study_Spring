package com.cos.cs_study_spring.concurrency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Race Condition / Lost Update 시연 테스트
 *
 * 순수 자바에서 count++ 연산이 원자적이지 않아 발생하는 문제
 * (CS_Study의 CounterDemo.java 스프링 버전)
 */
class CounterDemoTest {

    static int count = 0;

    @Test
    @DisplayName("왜 1+1 = 2가 아닌가? - Race Condition 시연")
    void testRaceCondition() throws InterruptedException {
        System.out.println("=== [Step 0] 왜 1+1 = 2가 아닌가? ===\n");

        count = 0;

        Thread threadA = new Thread(() -> {
            try {
                // READ
                int temp = count;
                System.out.println("[Thread A] READ:   temp = " + temp + " (메모리에서 읽음)");

                // MODIFY
                temp = temp + 1;
                System.out.println("[Thread A] MODIFY: temp = " + temp + " (레지스터에서 +1)");

                // WRITE
                count = temp;
                System.out.println("[Thread A] WRITE:  count = " + temp + " (메모리에 저장)\n");
            } catch (Exception e) {
                Thread.currentThread().interrupt();
            }
        });

        Thread threadB = new Thread(() -> {
            try {
                // READ
                int temp = count;
                System.out.println("[Thread B] READ:   temp = " + temp + " (메모리에서 읽음) <- 아직 0!");

                // MODIFY
                temp = temp + 1;
                System.out.println("[Thread B] MODIFY: temp = " + temp + " (레지스터에서 +1)");

                // WRITE
                count = temp;
                System.out.println("[Thread B] WRITE:  count = " + temp + " (메모리에 저장)\n");
            } catch (Exception e) {
                Thread.currentThread().interrupt();
            }
        });

        threadA.start();
        threadB.start();
        threadA.join();
        threadB.join();

        System.out.println("=== 결과 ===");
        System.out.println("예상: 2");
        System.out.println("실제: " + count);

        if (count == 2) {
            System.out.println("-> 이번엔 운 좋게 순차 실행됨 (문제가 없는 게 아님!)");
        } else {
            System.out.println("-> Lost Update 발생! 하나의 업데이트가 손실됨");
        }
    }

    @Test
    @DisplayName("대량 테스트 - Lost Update 확인")
    void testLostUpdate() throws InterruptedException {
        System.out.println("\n=== [대량 테스트] Lost Update 확인 ===\n");

        count = 0;
        int iterations = 100_000;

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        Thread tA = new Thread(() -> {
            ready.countDown();
            try {
                go.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            for (int i = 0; i < iterations; i++) {
                count++;
            }
        });

        Thread tB = new Thread(() -> {
            ready.countDown();
            try {
                go.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            for (int i = 0; i < iterations; i++) {
                count++;
            }
        });

        tA.start();
        tB.start();
        ready.await();
        go.countDown();
        tA.join();
        tB.join();

        int expected = iterations * 2;
        int lost = expected - count;

        System.out.println("예상: " + expected);
        System.out.println("실제: " + count);
        System.out.println("손실: " + lost);

        System.out.println("\n-> count++ 연산이 원자적이지 않아 Lost Update 발생");
        System.out.println("-> 해결책: synchronized, AtomicInteger, Lock 등");

        // 대부분의 경우 Lost Update가 발생함
        assertThat(lost).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("synchronized로 해결")
    void testWithSynchronized() throws InterruptedException {
        System.out.println("\n=== [해결책] synchronized 사용 ===\n");

        count = 0;
        int iterations = 100_000;
        Object lock = new Object();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        Thread tA = new Thread(() -> {
            ready.countDown();
            try {
                go.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            for (int i = 0; i < iterations; i++) {
                synchronized (lock) {
                    count++;
                }
            }
        });

        Thread tB = new Thread(() -> {
            ready.countDown();
            try {
                go.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            for (int i = 0; i < iterations; i++) {
                synchronized (lock) {
                    count++;
                }
            }
        });

        tA.start();
        tB.start();
        ready.await();
        go.countDown();
        tA.join();
        tB.join();

        int expected = iterations * 2;

        System.out.println("예상: " + expected);
        System.out.println("실제: " + count);
        System.out.println("손실: " + (expected - count));

        // synchronized를 사용하면 Lost Update가 발생하지 않음
        assertThat(count).isEqualTo(expected);
        System.out.println("\n-> synchronized로 상호 배제를 보장하여 Lost Update 방지");
    }
}
