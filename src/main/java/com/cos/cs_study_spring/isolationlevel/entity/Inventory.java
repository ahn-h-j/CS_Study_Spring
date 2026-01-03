package com.cos.cs_study_spring.isolationlevel.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "inventory")
@Getter
@NoArgsConstructor
public class Inventory {

    @Id
    private Long id;

    @Column(nullable = false)
    private Integer stock;

    public Inventory(Long id, Integer stock) {
        this.id = id;
        this.stock = stock;
    }

    public void decreaseStock() {
        this.stock -= 1;
    }

    public void setStock(Integer stock) {
        this.stock = stock;
    }
}
