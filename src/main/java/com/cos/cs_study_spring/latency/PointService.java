package com.cos.cs_study_spring.latency;

import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class PointService {

    private final PointRepository pointRepository;
    private final RedissonClient redissonClient;

    /**
     * 포인트 충전 (분산 락 사용)
     */
    public void charge(Long userId, Long amount) throws InterruptedException {
        String lockKey = "lock:point:" + userId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            if (lock.tryLock(5, TimeUnit.SECONDS)) {
                try {
                    executeCharge(userId, amount);
                } finally {
                    lock.unlock();
                }
            } else {
                throw new RuntimeException("락 획득 실패");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    @Transactional
    public void executeCharge(Long userId, Long amount) {
        Point point = pointRepository.findByUserId(userId)
                .orElseGet(() -> pointRepository.save(new Point(userId, 0L)));
        point.charge(amount);
        pointRepository.saveAndFlush(point);
    }

    /**
     * 포인트 조회
     */
    public Long getAmount(Long userId) {
        return pointRepository.findByUserId(userId)
                .map(Point::getAmount)
                .orElse(0L);
    }

    /**
     * 초기화
     */
    public void init(Long userId, Long amount) {
        pointRepository.findByUserId(userId)
                .ifPresentOrElse(
                        p -> {},
                        () -> pointRepository.save(new Point(userId, amount))
                );
    }
}
