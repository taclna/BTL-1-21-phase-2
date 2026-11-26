package com.example.cinema.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Enables Spring Cache; provider, cache names, TTL and size are set by {@code spring.cache.*}.
 *
 * <p>The explicit order puts the cache interceptor outside the transaction interceptor (which uses
 * {@link Ordered#LOWEST_PRECEDENCE}), so entries are evicted after the commit and not before it.
 */
@Configuration
@EnableCaching(order = Ordered.LOWEST_PRECEDENCE - 1)
public class CacheConfig {
}
