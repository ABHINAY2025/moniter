package com.employeemonitor.api.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Fixed-window rate limiter backed by Redis so limits hold across API instances. */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final StringRedisTemplate redis;

    public RateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Throws 429 when {@code key} has been hit more than {@code limit} times in the current window. */
    public void check(String key, int limit, Duration window) {
        if (limit <= 0) {
            return;
        }
        String redisKey = "em:ratelimit:" + key;
        try {
            Long count = redis.opsForValue().increment(redisKey);
            if (count != null && count == 1L) {
                redis.expire(redisKey, window);
            }
            if (count != null && count > limit) {
                throw ApiException.tooManyRequests();
            }
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            // Fail open: account lockout still protects logins if Redis is briefly unavailable.
            log.warn("Rate limiter unavailable for key {}: {}", key, e.getMessage());
        }
    }
}
