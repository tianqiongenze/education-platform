package com.ep.knowledge.retrieval.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.beans.factory.annotation.Value;

import java.util.List;

@Configuration
public class RedisConfig {

    @Value("${spring.data.redis.cluster.nodes}")
    private String clusterNodes;

    @Bean
    public LettuceConnectionFactory redisConnectionFactory() {
        List<String> nodes = java.util.Arrays.stream(clusterNodes.split(","))
                .map(String::trim)
                .toList();
        RedisClusterConfiguration config = new RedisClusterConfiguration(nodes);
        return new LettuceConnectionFactory(config);
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(LettuceConnectionFactory cf) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(cf);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        return template;
    }
}
