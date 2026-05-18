package com.miniurl.redirect.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI redirectServiceAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("Redirect Service")
                .description("High-throughput URL redirect endpoint")
                .version("1.0.0"));
    }
}
