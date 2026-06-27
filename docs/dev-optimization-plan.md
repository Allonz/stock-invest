# stock-invest 开发优化方案

> 项目路径：`/home/allon/application/stock-invest/`
> 当前分支：`main` | 远程：`origin git@github.com:Allonz/stock-invest.git`

---

## 目录

- [问题一：@Scheduled 时区偏差](#问题一scheduled-时区偏差)
- [问题二：processRetryingTasks 重复执行（已修复）](#问题二processretryingtasks-重复执行已修复)
- [问题三：节点工具链配置（已完成）](#问题三节点工具链配置已完成)
- [问题四：开发体验优化建议](#问题四开发体验优化建议)

---

## 问题一：@Scheduled 时区偏差

### 现象

代码中：

```java
// DataFillScheduler.java:27
@Scheduled(cron = "0 0 19 * * ?", zone = "America/New_York")
```

设定美东时间 19:00 执行补缺任务，但实际在北京时间 06:00 左右触发（理论上 19:00 EDT = 07:00 CST）。

### 根因

`@Scheduled(cron = "...", zone = "America/New_York")` 的 `zone` 属性存在跨 JVM 版本的可信度问题：

1. **依赖 JDK 底层时区数据库** — 不同 JDK 版本的 TZDB 规则可能细微差异
2. **需要做时区转换** — JVM 默认时区（北京时间）→ `America/New_York` 时区，这个转换链路中 DST 计算可能出现偏差
3. **Spring 官方推荐方案**：使用 `SchedulingConfigurer` 全局设置调度器时区，而非在每个 `@Scheduled` 上写 `zone`

### 涉及的文件

| 文件 | 当前内容 | 需要修改 |
|------|---------|---------|
| `src/main/java/com/stock/invest/scheduler/DataFillScheduler.java` | `@Scheduled(cron = "0 0 19 * * ?", zone = "America/New_York")` | 去掉 `zone` 属性 |
| `src/main/java/com/stock/invest/scheduler/ScreeningScheduler.java` | `@Scheduled(cron = "0 30 21 * * ?", zone = "America/New_York")` | 去掉 `zone` 属性 |
| `src/main/java/com/stock/invest/scheduler/TradingCalendarScheduler.java` | `@Scheduled(cron = "0 30 4 * * MON", zone = "America/New_York")` | 去掉 `zone` 属性 |
| **新建** `SchedulerConfig.java` | — | 实现 `SchedulingConfigurer`，全局设置时区 |

### 修复方案

#### 步骤 1：新建 `SchedulerConfig.java`

```java
package com.stock.invest.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import java.util.TimeZone;

@Configuration
public class SchedulerConfig implements SchedulingConfigurer {

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.setTimeZone(TimeZone.getTimeZone("America/New_York"));
    }
}
```

#### 步骤 2：修改三个 Scheduler，去掉 `zone` 属性

**DataFillScheduler.java**

```java
// 改前
@Scheduled(cron = "0 0 19 * * ?", zone = "America/New_York")

// 改后
@Scheduled(cron = "0 0 19 * * ?")
```

**ScreeningScheduler.java**

```java
// 改前
@Scheduled(cron = "0 30 21 * * ?", zone = "America/New_York")

// 改后
@Scheduled(cron = "0 30 21 * * ?")
```

**TradingCalendarScheduler.java**

```java
// 改前
@Scheduled(cron = "0 30 4 * * MON", zone = "America/New_York")

// 改后
@Scheduled(cron = "0 30 4 * * MON")
```

> ⚠️ **注意**：项目中有大量 `ZonedDateTime.now(ZoneId.of("America/New_York"))` 的显式写法，这些不受影响，保持不变。

---

## 问题二：processRetryingTasks 重复执行（已修复 ✅）

### 状态

| 项目 | 状态 |
|------|------|
| 代码修改 | ✅ 已提交 `0a67dd0` → `origin main` |
| 修改内容 | 删除 `DataGapFillerServiceImpl.java:169` 的 `processRetryingTasks()` 调用 |
| 原因 | `fillGaps()` 内部调了一次，`DataFillScheduler` 又调了一次，导致每天执行两次 |

### 修改说明

```java
// 删除了以下两行（fillGaps 方法尾部）：
//
//     // 补缺完成后也处理未完成的 retry 任务
//     processRetryingTasks();
//
// 现在 processRetryingTasks 仅由 DataFillScheduler 在 fillGaps 完成后统一调度
```

---

## 问题三：节点工具链配置（已完成 ✅）

### 状态

| 项目 | 状态 |
|------|------|
| `~/.hermes/node/bin` → nvm v24.16.0 | ✅ 软链接已创建 |
| 全局工具安装（codex、clawhub、openclaw、pnpm） | ✅ 通过 nvm npm install -g |
| `~/.hermes/node/lib/` 旧源码 | ✅ 已删除（释放 767MB） |
| `~/.local/bin/` 独立软链 | ✅ 已清理 |

### 当前 PATH 顺序（hermes-gateway.service 生成时）

```
1. .venv/bin              ← Python venv
2. node_modules/.bin      ← project-level npm 工具
3. .hermes/node/bin       → nvm v24.16.0 (symlink)
4. .local/bin             ← user-local 工具
```

---

## 问题四：开发体验优化建议

以下是与时区无直接关系，但可以考虑的优化点：

### 1. application.yml 中嵌入敏感信息

当前包含明文 API Key：

```yaml
twelvedata:
  api:
    api-key: dbad6e2929d54beca7083fbbf111b6be   # ← 明文
tiingo:
  api:
    token: e8d9f9e86a9e5a6261e9cefa7caa55c2e1080394  # ← 明文
```

**建议**：通过环境变量注入，而非硬编码：

```yaml
twelvedata:
  api:
    api-key: ${TWELVEDATA_API_KEY}
tiingo:
  api:
    token: ${TIINGO_TOKEN}
```

### 2. .gitignore 优化

当前仓库存入了以下不应提交的文件：

```
❌ .openclaw-debug/          ← 调试文件
❌ src/main/resources/python/__pycache__/   ← Python 缓存
❌ src/main/java/.../Untitled               ← 临时文件
❌ src/main/java/.../*.bak                  ← 备份文件
```

**建议**：更新 `.gitignore` 排除调试和临时文件

### 3. 前端 npm 依赖管理

`frontend/` 目录存在，确认是否已配好 `package.json` 和构建脚本。

---

## 修改优先级

| 优先级 | 事项 | 工作量 | 依赖 |
|--------|------|--------|------|
| 🔴 **高** | 问题一：SchedulingConfigurer 全局时区 | 新建 1 文件 + 改 3 文件 | 无 |
| 🟡 **中** | 问题四-1：API Key 提取到环境变量 | 改 1 文件 | 需确认启动方式 |
| 🟢 **低** | 问题四-2：.gitignore 优化 | 改 1 文件 | 无 |
| 🟢 **低** | 问题四-3：前端构建确认 | 需调研 | 走读 frontend/ |

---

## 问题一修复后的验证方式

1. 启动项目后，在日志中观察补缺任务的执行时间
2. 执行时间应为对应的美东时间（19:00 EDT ≈ 隔日 07:00 CST）
3. 所有三个 Scheduler 的时间均按 `America/New_York` 解析
