# 股票投资应用

这是一个基于Spring Boot的股票投资分析应用，支持从多个数据源获取股票数据并进行分析。

## 项目结构

```
stock-invest/
├── src/
│   ├── main/
│   │   ├── java/            # Java源代码
│   │   │   └── com/stock/invest/
│   │   │       ├── config/  # 配置类
│   │   │       ├── controller/ # REST控制器
│   │   │       ├── model/   # 实体类
│   │   │       ├── repository/ # 数据访问层
│   │   │       ├── service/ # 服务层
│   │   │       │   └── impl/ # 服务实现
│   │   │       └── util/    # 工具类
│   │   └── resources/       # 配置文件和资源
│   │       ├── application.yml # 主配置文件
│   │       ├── application-*.yml # 特定profile的配置
│   │       ├── python/      # Python脚本
│   │       └── scripts/     # 批处理和脚本文件
│   └── test/                # 测试代码
├── pom.xml                  # Maven项目描述
└── README.md                # 项目说明
```

## 数据源

本应用支持以下数据源：

1. **TwelveData** - 基于TwelveData API的股票数据
2. **YFinance** - 基于Yahoo Finance的股票数据
3. **Tiger** - 基于Tiger Broker API的股票数据

## 功能特点

- 获取股票实时价格和基本信息
- 获取股票K线数据
- 股票筛选和过滤
- 低价高交易量股票扫描
- 自定义股票模式识别

## 环境要求

- JDK 8或更高版本
- Maven 3.6或更高版本
- Python 3.8或更高版本
- 必要的Python库（见requirements.txt）

## 安装

1. 克隆仓库：
   ```bash
   git clone https://github.com/yourusername/stock-invest.git
   cd stock-invest
   ```

2. 安装Java和Maven依赖：
   ```bash
   mvn clean install
   ```

3. 安装Python依赖：
   ```bash
   pip install -r src/main/resources/python/requirements.txt
   ```

## 使用指南

### 启动应用程序

您可以使用以下命令启动应用程序，根据需要选择不同的数据源：

#### 使用scripts目录中的脚本启动

```bash
# 使用TwelveData作为数据源启动（CMD）
src\main\resources\scripts\run-app.cmd twelvedata

# 使用TwelveData作为数据源启动（PowerShell）
.\src\main\resources\scripts\run-app.ps1 twelvedata

# 使用YFinance作为数据源启动（CMD）
src\main\resources\scripts\run-app.cmd yfinance

# 使用YFinance作为数据源启动（PowerShell）
.\src\main\resources\scripts\run-app.ps1 yfinance

# 使用Tiger作为数据源启动（CMD）
src\main\resources\scripts\run-app.cmd tiger

# 使用Tiger作为数据源启动（PowerShell）
.\src\main\resources\scripts\run-app.ps1 tiger
```

#### 使用配置文件中的默认数据源

如果不指定数据源参数，应用程序将使用配置文件中指定的默认数据源（当前默认为`twelvedata`）。

## API端点

应用启动后，可以访问以下API端点：

- **基本股票信息**：`GET /api/tiger/kline/daily/{symbol}`
- **股票K线数据**：`GET /api/tiger/kline/daily/object/{symbol}`
- **股票筛选**：`POST /api/scanner/custom` (包含市场、限制、价格区间等参数)
- **低价高交易量股票**：`GET /api/tiger/scan/low-price-volume-pattern?limit={limit}`

## 贡献

欢迎贡献代码和提交问题！请遵循以下步骤：

1. Fork本仓库
2. 创建您的特性分支 (`git checkout -b feature/amazing-feature`)
3. 提交您的更改 (`git commit -m 'Add some amazing feature'`)
4. 推送到分支 (`git push origin feature/amazing-feature`)
5. 打开一个Pull Request

## 许可证

本项目采用MIT许可证。详见 [LICENSE](LICENSE) 文件。 