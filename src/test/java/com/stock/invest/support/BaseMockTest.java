package com.stock.invest.support;

import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.context.ActiveProfiles;

/**
 * 通用 Mock 测试基类。
 * 配置 Spring Profile 为 test（使用 H2 内存数据库），
 * 并激活 Mockito 扩展。
 */
@ActiveProfiles("test")
@ExtendWith(MockitoExtension.class)
public abstract class BaseMockTest {

    // 子类通过 @MockBean 声明各自的 mock
}
