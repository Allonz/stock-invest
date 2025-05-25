# 设置控制台编码为UTF-8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

Write-Host "Starting application with YFinance as stock data source..."

# 设置Spring Profile
$env:SPRING_PROFILES_ACTIVE = "yfinance"
$env:JAVA_OPTS = "-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"
$env:LANG = "zh_CN.UTF-8"
$env:LC_ALL = "zh_CN.UTF-8"

# 切换到项目根目录
Set-Location -Path "C:\Application\stock-invest"

# 运行Spring Boot应用
mvn spring-boot:run -Dspring-boot.run.profiles=yfinance -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 