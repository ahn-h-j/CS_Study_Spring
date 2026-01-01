package com.cos.cs_study_spring.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;

/**
 * Lettuce 기반 분산 락 저장소
 * - Redis의 SETNX(SET if Not eXists) 명령을 사용
 * - 락 획득: SETNX로 키가 없으면 설정, 있으면 실패
 * - 락 해제: DEL 명령으로 키 삭제
 */
@Repository
@RequiredArgsConstructor
public class LettuceLockRepository {

    private final RedisTemplate<String, String> redisTemplate;

    /**
     * 락 획득 시도 (SETNX 사용)
     *
     * @param key 락 키
     * @return 락 획득 성공 여부
     *
     * [SETNX 동작 원리]
     * - SET if Not eXists: 키가 존재하지 않을 때만 값을 설정
     * - 원자적(Atomic) 연산이므로 동시에 여러 클라이언트가 호출해도 하나만 성공
     * - TTL(3초) 설정으로 데드락 방지 (락을 획득한 프로세스가 죽어도 자동 해제)
     */
    public Boolean tryLock(String key) {
        return redisTemplate
                .opsForValue()
                .setIfAbsent(key, "locked", Duration.ofSeconds(3));
    }

    /**
     * 락 해제
     * @param key 락 키
     */
    public void unlock(String key) {
        redisTemplate.delete(key);
    }
}
