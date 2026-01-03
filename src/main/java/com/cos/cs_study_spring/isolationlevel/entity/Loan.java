package com.cos.cs_study_spring.isolationlevel.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "loans")
@Getter
@NoArgsConstructor
public class Loan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Column(nullable = false)
    private Integer amount;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public Loan(String customerId, Integer amount) {
        this.customerId = customerId;
        this.amount = amount;
        this.createdAt = LocalDateTime.now();
    }
}
