package com.ep.knowledge.retrieval.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.Components;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** D-070: OpenAPI 3.0 spec auto-generated from controllers via springdoc. */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI retrievalServiceOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Retrieval Service API")
                        .description("知识检索服务 — 混合检索（向量+全文）与 RAG 问答")
                        .version("1.0.0")
                        .contact(new Contact().name("EP Team"))
                        .license(new License().name("Proprietary")))
                .components(new Components()
                        .addSecuritySchemes("TenantAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Tenant-Id")))
                .addSecurityItem(new SecurityRequirement().addList("TenantAuth"));
    }
}
