package com.cos.cs_study_spring.facade;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long quantity;

    public Ticket(Long quantity) {
        this.quantity = quantity;
    }

    public void decrease() {
        if (this.quantity <= 0) {
            throw new RuntimeException("티켓 매진");
        }
        this.quantity--;
    }
}
