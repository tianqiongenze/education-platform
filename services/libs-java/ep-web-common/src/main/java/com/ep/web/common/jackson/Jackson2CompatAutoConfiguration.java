package com.ep.web.common.jackson;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * D-231: Boot 4 auto-configures a Jackson 3 mapper (tools.jackson) but many
 * service components (servlet filters, event consumers) are written against
 * the Jackson 2 API (com.fasterxml.jackson.databind.ObjectMapper). Without
 * this bean those injection points fail with NoSuchBeanDefinitionException
 * at startup. Registered for every service via
 * {@code META-INF/spring/...AutoConfiguration.imports}; {@code
 * ConditionalOnMissingBean} keeps service-local definitions (e.g. portal-bff
 * ProxyConfig) authoritative.
 */
@AutoConfiguration
@ConditionalOnClass(ObjectMapper.class)
public class Jackson2CompatAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper epJackson2ObjectMapper() {
        return new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
