Write-Host "启动股票投资应用..." -ForegroundColor Green

# 检查参数
if ($args.Count -eq 0) {
    Write-Host "请指定数据源: twelvedata, yfinance 或 tiger" -ForegroundColor Yellow
    Write-Host "用法: .\run-app.ps1 [twelvedata|yfinance|tiger]"
    exit 1
}

# 验证数据源参数
$dataSource = $args[0]
Write-Host "设置数据源为: $dataSource" -ForegroundColor Cyan

if ($dataSource -ne "twelvedata" -and $dataSource -ne "yfinance" -and $dataSource -ne "tiger") {
    Write-Host "无效的数据源: $dataSource" -ForegroundColor Red
    Write-Host "请使用: twelvedata, yfinance 或 tiger"
    exit 1
}

# 运行对应的脚本
Write-Host "使用 $dataSource 数据源启动应用..." -ForegroundColor Cyan
$scriptPath = Split-Path -Parent $MyInvocation.MyCommand.Path
$targetScript = "$scriptPath\run-$dataSource.ps1"
Write-Host "执行脚本: $targetScript" -ForegroundColor Cyan

if (-not (Test-Path $targetScript)) {
    Write-Host "错误: 脚本文件不存在 - $targetScript" -ForegroundColor Red
    exit 1
}

& $targetScript 