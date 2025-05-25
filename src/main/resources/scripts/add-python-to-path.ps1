# 将Python添加到系统PATH环境变量
# 需要以管理员权限运行此脚本

# 检查是否以管理员权限运行
if (-NOT ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole] "Administrator")) {
    Write-Warning "请以管理员权限运行此脚本!"
    exit
}

# Python安装路径
$pythonPath = "C:\Program Files\Python310"
$pythonScriptsPath = "C:\Program Files\Python310\Scripts"

# 检查Python是否已安装
if (-not (Test-Path $pythonPath)) {
    Write-Error "Python未安装或安装路径不正确！"
    exit
}

# 获取当前的系统PATH
$currentPath = [Environment]::GetEnvironmentVariable("Path", "Machine")

# 检查PATH中是否已包含Python路径
$pathsToAdd = @()
if ($currentPath -notlike "*$pythonPath*") {
    $pathsToAdd += $pythonPath
}
if ($currentPath -notlike "*$pythonScriptsPath*") {
    $pathsToAdd += $pythonScriptsPath
}

# 如果有新路径需要添加
if ($pathsToAdd.Count -gt 0) {
    # 将Python路径添加到系统PATH中
    $newPath = $currentPath
    foreach ($path in $pathsToAdd) {
        if ($newPath.EndsWith(";")) {
            $newPath = $newPath + $path
        } else {
            $newPath = $newPath + ";" + $path
        }
    }
    
    # 更新系统环境变量
    [Environment]::SetEnvironmentVariable("Path", $newPath, "Machine")
    Write-Host "已将Python路径添加到系统PATH环境变量中: $($pathsToAdd -join ', ')" -ForegroundColor Green
} else {
    Write-Host "Python路径已存在于系统PATH环境变量中" -ForegroundColor Yellow
}

# 验证Python命令是否可用
try {
    $pythonVersion = & python --version
    Write-Host "Python安装验证成功: $pythonVersion" -ForegroundColor Green
} catch {
    Write-Warning "无法执行Python命令，可能需要重新启动计算机或终端以使更改生效"
}

Write-Host "操作完成。建议重新启动计算机以确保环境变量更改生效。" -ForegroundColor Cyan 