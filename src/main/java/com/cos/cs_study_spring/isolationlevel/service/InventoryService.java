package com.cos.cs_study_spring.isolationlevel.service;

import com.cos.cs_study_spring.isolationlevel.entity.Inventory;
import com.cos.cs_study_spring.isolationlevel.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * MySQL Lock 비교를 위한 재고 서비스
 *
 * SERIALIZABLE: 자동으로 모든 SELECT에 락 적용 (데드락 가능성 높음)
 * READ_COMMITTED + FOR UPDATE: 명시적 락으로 정확한 동시성 제어
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryRepository inventoryRepository;

    /**
     * SERIALIZABLE 격리 수준으로 재고 차감
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void decreaseStockWithSerializable(Long id) {
        Inventory inventory = inventoryRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Inventory not found"));
        inventory.decreaseStock();
    }

    /**
     * READ COMMITTED + SELECT FOR UPDATE로 재고 차감
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void decreaseStockWithExplicitLock(Long id) {
        Inventory inventory = inventoryRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new RuntimeException("Inventory not found"));
        inventory.decreaseStock();
    }

    /**
     * 초기 데이터 설정
     */
    @Transactional
    public void setupInitialData(Long id, int stock) {
        inventoryRepository.deleteAll();
        inventoryRepository.save(new Inventory(id, stock));
        log.info("[초기화] inventory 테이블 생성, id={}, stock={}", id, stock);
    }

    /**
     * 재고 리셋
     */
    @Transactional
    public void resetStock(Long id, int stock) {
        Inventory inventory = inventoryRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Inventory not found"));
        inventory.setStock(stock);
        log.info("[리셋] stock={}", stock);
    }

    /**
     * 현재 재고 조회
     */
    @Transactional(readOnly = true)
    public int getStock(Long id) {
        return inventoryRepository.findById(id)
                .map(Inventory::getStock)
                .orElse(-1);
    }
}
