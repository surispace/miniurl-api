package com.miniurl.redirect.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class AppConfig {

    @Bean
    public ReactiveRedisTemplate<String, String> reactiveRedisTemplate(ReactiveRedisConnectionFactory factory) {
        RedisSerializationContext.RedisSerializationContextBuilder<String, String> builder =
            RedisSerializationContext.newSerializationContext(new StringRedisSerializer());
        
        RedisSerializationContext<String, String> context = builder
            .value(new StringRedisSerializer())
            .build();
            
        return new ReactiveRedisTemplate<>(factory, context);
    }

    @Bean
    public WebClient webClient(WebClient.Builder builder) {
        // The base URL is set to the load-balanced service name via Eureka
        return builder.baseUrl("http://url-service").build();
    }
}
