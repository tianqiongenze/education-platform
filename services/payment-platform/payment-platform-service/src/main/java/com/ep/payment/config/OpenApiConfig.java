package com.ep.payment.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.Components;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * D-070: OpenAPI 3.1 spec. The component models come from swagger-models-jakarta
 * (io.swagger.core.v3:swagger-models-jakarta), which provides the
 * {@code io.swagger.v3.oas.models.*} classes used here.
 */
@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI epOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("core-business Payment API")
                .description("Education Platform — Payment Middle Platform")
                .version("1.0.0")
                .contact(new Contact().name("EP Team")))
            .components(new Components()
                .addSecuritySchemes("bearerAuth", new SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")))
            .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
