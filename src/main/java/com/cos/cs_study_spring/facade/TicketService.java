package com.cos.cs_study_spring.facade;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ============================================================================
 * [Service Layer] 트랜잭션만 담당
 * ============================================================================
 *
 * - 순수하게 비즈니스 로직과 트랜잭션만 처리
 * - 락에 대한 책임 없음
 * - Facade에서 호출됨 (락이 이미 획득된 상태)
 *
 * ============================================================================
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketService {

    private final TicketRepository ticketRepository;

    /**
     * 티켓 감소 (트랜잭션 처리)
     *
     * 이 메서드가 호출될 때:
     * - 이미 Facade에서 락을 획득한 상태
     * - 메서드 리턴 시 트랜잭션 커밋 완료
     * - 커밋 완료 후 Facade에서 락 해제
     */
    @Transactional
    public void decrease(Long id) {
        Ticket ticket = ticketRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Ticket not found"));
        ticket.decrease();
        ticketRepository.saveAndFlush(ticket);
    }

    public Long getQuantity(Long id) {
        return ticketRepository.findById(id)
                .map(Ticket::getQuantity)
                .orElse(0L);
    }
}
