package com.miniurl.feature;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
@EntityScan("com.miniurl.entity")
public class FeatureServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(FeatureServiceApplication.class, args);
    }
}
