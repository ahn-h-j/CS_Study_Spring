package com.cos.cs_study_spring.isolationlevel.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "coupon")
@Getter
@NoArgsConstructor
public class Coupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public Coupon(String userId) {
        this.userId = userId;
        this.createdAt = LocalDateTime.now();
    }
}
