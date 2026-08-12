package com.ep.knowledge.service.config;

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
    public OpenAPI knowledgeServiceOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Knowledge Service API")
                        .description("知识库文档管理服务 — 文档上传、分类管理、检索")
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
