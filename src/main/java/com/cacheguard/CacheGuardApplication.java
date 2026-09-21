package com.cacheguard;

import com.cacheguard.config.RiskProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(RiskProperties.class)
public class CacheGuardApplication {

    public static void main(String[] args) {
        SpringApplication.run(CacheGuardApplication.class, args);
    }
}
