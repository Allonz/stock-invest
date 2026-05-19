package com.stock.invest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "python.scripts")
public class PythonScriptProperties {

    /** Python 脚本目录路径 */
    private String path = "src/main/resources/python";

    /** 执行器类型（process 等） */
    private String executorType = "process";

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getExecutorType() {
        return executorType;
    }

    public void setExecutorType(String executorType) {
        this.executorType = executorType;
    }
}
