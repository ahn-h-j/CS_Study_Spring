package com.cos.cs_study_spring.isolationlevel.repository;

import com.cos.cs_study_spring.isolationlevel.entity.Loan;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoanRepository extends JpaRepository<Loan, Long> {

    @Query("SELECT COALESCE(SUM(l.amount), 0) FROM Loan l WHERE l.customerId = :customerId")
    Long getTotalLoanAmount(@Param("customerId") String customerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT COALESCE(SUM(l.amount), 0) FROM Loan l WHERE l.customerId = :customerId")
    Long getTotalLoanAmountForUpdate(@Param("customerId") String customerId);
}
