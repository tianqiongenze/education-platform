package com.ep.disruptor.spring;

import com.ep.disruptor.DisruptorPipeline;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * D-216: activates whenever the service declares at least one
 * {@link DisruptorPipeline} bean. A BeanPostProcessor picks up every pipeline
 * bean wherever it is defined and hands it to the centrally lifecycle-managed
 * {@link DisruptorPipelineRegistry}.
 */
@AutoConfiguration
@ConditionalOnClass(DisruptorPipeline.class)
public class DisruptorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DisruptorPipelineRegistry disruptorPipelineRegistry() {
        return new DisruptorPipelineRegistry();
    }

    /**
     * Static registrar bean: receives the registry lazily via ObjectProvider
     * so the post-processor itself never forces early bean initialization.
     */
    @Bean
    public static DisruptorPipelineRegistrar disruptorPipelineRegistrar(
            ObjectProvider<DisruptorPipelineRegistry> registry) {
        return new DisruptorPipelineRegistrar(registry);
    }

    static final class DisruptorPipelineRegistrar implements BeanPostProcessor {

        private final ObjectProvider<DisruptorPipelineRegistry> registry;

        private DisruptorPipelineRegistrar(ObjectProvider<DisruptorPipelineRegistry> registry) {
            this.registry = registry;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (bean instanceof DisruptorPipeline pipeline) {
                registry.ifAvailable(r -> r.register(pipeline));
            }
            return bean;
        }
    }
}
