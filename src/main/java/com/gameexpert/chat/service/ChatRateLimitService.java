package com.gameexpert.chat.service;

import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ChatRateLimitService {

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> chatLimitScript = new DefaultRedisScript<>("""
                        local count = tonumber(redis.call('GET', KEYS[1]) or '0')
                        if count >= tonumber(ARGV[1]) then
                            return 0
                        end
                        local updated = redis.call('INCR', KEYS[1])
                        if updated == 1 then
                            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))
                        end
                        return 1
                        """, Long.class);

    public boolean allow(Long playerId) {
        String key = "chat:limit:" + playerId;
        Long result = redisTemplate.execute(chatLimitScript, List.of(key),String.valueOf(5), String.valueOf(10));
        return result != 0;
    }
}
