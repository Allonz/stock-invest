package com.stock.invest.support;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * 评审 F-7：集成测试专用 schema 重建。
 * <p>
 * 背景：application-integration.yml 此前直连生产 MySQL（127.0.0.1:3307/stock_invest），
 * 误传 -Dgroups=integration 即对生产库执行 Flyway 迁移与写入。
 * 本初始化器在 Spring 上下文启动前（创建数据源之前）对独立测试库
 * <code>stock_invest_it</code> 执行 DROP + CREATE，保证每次集成测试运行
 * 都从空库开始迁移，绝不触碰生产 stock_invest 库。
 * </p>
 * <p>用法：在 <code>@SpringBootTest + @ActiveProfiles("integration")</code> 的测试类上
 * 追加 <code>@ContextConfiguration(initializers = IntegrationSchemaResetInitializer.class)</code>。</p>
 */
public class IntegrationSchemaResetInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final String BASE_URL = "jdbc:mysql://127.0.0.1:3307/";
    private static final String USER = "root";
    private static final String SCHEMA = "stock_invest_it";

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        String password = System.getenv().getOrDefault("MYSQL_PASSWORD", "");
        try (Connection c = DriverManager.getConnection(BASE_URL, USER, password);
             Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + SCHEMA);
            st.execute("CREATE DATABASE " + SCHEMA
                    + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        } catch (Exception e) {
            throw new IllegalStateException(
                    "无法重建集成测试 schema " + SCHEMA
                            + "（请确认 MySQL 127.0.0.1:3307 可访问且 MYSQL_PASSWORD 环境变量已设置）",
                    e);
        }
    }
}
