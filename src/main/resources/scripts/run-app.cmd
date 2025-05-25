@echo off
:: 设置命令提示符编码为UTF-8
chcp 65001 >nul
echo 启动股票投资应用...

:: 检查参数
if "%1"=="" (
    echo 请指定数据源: twelvedata, yfinance 或 tiger
    echo 用法: run-app.cmd [twelvedata^|yfinance^|tiger]
    exit /b 1
)

:: 验证数据源参数
set "DATA_SOURCE=%1"
echo 设置数据源为: %DATA_SOURCE%

if not "%DATA_SOURCE%"=="twelvedata" if not "%DATA_SOURCE%"=="yfinance" if not "%DATA_SOURCE%"=="tiger" (
    echo 无效的数据源: %DATA_SOURCE%
    echo 请使用: twelvedata, yfinance 或 tiger
    exit /b 1
)

:: 运行对应的脚本
echo 使用 %DATA_SOURCE% 数据源启动应用...
set "SCRIPT_PATH=%~dp0run-%DATA_SOURCE%.cmd"
echo 执行脚本: %SCRIPT_PATH%

if not exist "%SCRIPT_PATH%" (
    echo 错误: 脚本文件不存在 - %SCRIPT_PATH%
    exit /b 1
)

call "%SCRIPT_PATH%"
