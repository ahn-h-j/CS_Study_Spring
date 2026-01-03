package com.cos.cs_study_spring.isolationlevel.service;

import com.cos.cs_study_spring.isolationlevel.entity.Loan;
import com.cos.cs_study_spring.isolationlevel.repository.LoanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Non-Repeatable Read 시연을 위한 대출 서비스
 *
 * READ_COMMITTED: 매 SELECT마다 스냅샷 갱신 -> Non-Repeatable Read 발생
 * REPEATABLE_READ: 트랜잭션 시작 시점의 스냅샷 유지 -> Non-Repeatable Read 방어
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoanService {

    private final LoanRepository loanRepository;

    /**
     * READ COMMITTED 격리 수준으로 대출 심사 (Non-Repeatable Read 발생 가능)
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public long[] processLoanWithReadCommitted(String customerId) throws InterruptedException {
        log.info("[본점] BEGIN (READ COMMITTED)");

        // 1. 첫 번째 조회
        long amount1 = loanRepository.getTotalLoanAmount(customerId);
        long available1 = 10000 - amount1;
        log.info("[본점] SELECT SUM(amount) -> {}만원", amount1);
        log.info("[본점] 한도 1억 - {}만 = {}만원 가능", amount1, available1);
        log.info("[본점] 판단: 1,500만원 대출 가능!");

        // 심사 진행 중 (다른 트랜잭션이 끼어들 시간)
        Thread.sleep(500);

        log.info("[본점] 심사 완료! 대출 승인 문자 발송");

        // 2. 두 번째 조회 - Non-Repeatable Read 확인
        long amount2 = loanRepository.getTotalLoanAmount(customerId);
        long available2 = 10000 - amount2;

        if (available2 < 2000) {
            log.info("[본점] SELECT SUM(amount) -> {}만원 (Non-Repeatable Read!)", amount2);
            log.info("[본점] 남은 한도: {}만원, 1,500만원 실행 불가!", available2);
        } else {
            log.info("[본점] SELECT SUM(amount) -> {}만원", amount2);
            log.info("[본점] 남은 한도: {}만원, 1,500만원 실행!", available2);
        }

        return new long[]{amount1, amount2};
    }

    /**
     * REPEATABLE READ 격리 수준으로 대출 심사 (Non-Repeatable Read 방어)
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public long[] processLoanWithRepeatableRead(String customerId) throws InterruptedException {
        log.info("[본점] BEGIN (REPEATABLE READ)");

        // 1. 첫 번째 조회 - MVCC 스냅샷 생성
        long amount1 = loanRepository.getTotalLoanAmount(customerId);
        long available1 = 10000 - amount1;
        log.info("[본점] SELECT SUM(amount) -> {}만원 (MVCC 스냅샷 생성)", amount1);
        log.info("[본점] 한도 1억 - {}만 = {}만원 가능", amount1, available1);
        log.info("[본점] 판단: 1,500만원 대출 가능!");

        // 심사 진행 중 (다른 트랜잭션이 끼어들 시간)
        Thread.sleep(500);

        log.info("[본점] 심사 완료! 대출 승인 문자 발송");

        // 2. 두 번째 조회 - MVCC 스냅샷 유지
        long amount2 = loanRepository.getTotalLoanAmount(customerId);
        long available2 = 10000 - amount2;
        log.info("[본점] SELECT SUM(amount) -> {}만원 (MVCC 스냅샷 유지)", amount2);
        log.info("[본점] 남은 한도: {}만원, 1,500만원 실행!", available2);

        return new long[]{amount1, amount2};
    }

    /**
     * 다른 지점에서 대출 실행 (새 트랜잭션)
     */
    @Transactional
    public void insertLoan(String customerId, int amount) {
        log.info("[다른지점] INSERT {}만원 대출", amount);
        loanRepository.save(new Loan(customerId, amount));
        log.info("[다른지점] COMMIT 완료");
    }

    /**
     * 초기 데이터 설정
     */
    @Transactional
    public void setupInitialData(String customerId) {
        loanRepository.deleteAll();
        // 고객 A의 기존 대출 8,000만원
        loanRepository.save(new Loan(customerId, 3000));
        loanRepository.save(new Loan(customerId, 2500));
        loanRepository.save(new Loan(customerId, 2500));
        log.info("[초기화] loans 테이블 생성, 고객 {} 총 대출금 8,000만원", customerId);
    }
}
