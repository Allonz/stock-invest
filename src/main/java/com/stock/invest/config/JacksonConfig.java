package com.stock.invest.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 全局 Jackson 序列化安全配置。
 *
 * <p>任何 Throwable 子类（如 MCP SDK 的 McpError）被直接序列化时，
 * 不再输出 stackTrace / cause / suppressed，避免内部类名和堆栈泄露给客户端。</p>
 */
@Configuration
public class JacksonConfig {

    @JsonIgnoreProperties({"stackTrace", "cause", "suppressed"})
    private abstract static class ThrowableMixin {
    }

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer throwableSerializationCustomizer() {
        return builder -> builder.mixIn(Throwable.class, ThrowableMixin.class);
    }
}
