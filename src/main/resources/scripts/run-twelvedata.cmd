@echo off
chcp 65001 >nul
echo Starting application with TwelveData as stock data source...

:: 设置Spring Profile
set SPRING_PROFILES_ACTIVE=twelvedata
set JAVA_OPTS=-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8
set LANG=zh_CN.UTF-8
set LC_ALL=zh_CN.UTF-8

:: 返回项目根目录
cd /d C:\Application\stock-invest

:: 运行Spring Boot应用
call mvn spring-boot:run -Dspring-boot.run.profiles=twelvedata -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 