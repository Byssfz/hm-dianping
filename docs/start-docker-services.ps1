[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$composeFile = Join-Path $PSScriptRoot 'docker-compose-rocketmq.yml'
$expectedPorts = [ordered]@{
    mysql   = @{ ContainerPort = '3306/tcp';  HostPort = '13306' }
    redis   = @{ ContainerPort = '6379/tcp';  HostPort = '16379' }
    namesrv = @{ ContainerPort = '9876/tcp';  HostPort = '9876' }
    broker  = @{ ContainerPort = '10909/tcp'; HostPort = '10909' }
}

function Invoke-DockerCommand {
    param([Parameter(Mandatory)][string[]]$Arguments)
    & docker @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Docker 命令执行失败，退出码：$LASTEXITCODE" }
}

function Get-ServiceContainerId {
    param([Parameter(Mandatory)][string]$Service)
    $containerId = (& docker compose -f $composeFile ps -q $Service 2>$null).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($containerId)) {
        throw "服务 $Service 没有运行中的 Compose 容器。"
    }
    return $containerId
}

function Assert-PublishedPort {
    param(
        [Parameter(Mandatory)][string]$Service,
        [Parameter(Mandatory)][string]$ContainerPort,
        [Parameter(Mandatory)][string]$HostPort
    )
    $containerId = Get-ServiceContainerId -Service $Service
    $runtimeJson = (& docker inspect $containerId --format '{{json .NetworkSettings.Ports}}').Trim()
    if ($LASTEXITCODE -ne 0) { throw "无法读取服务 $Service 的运行时端口。" }
    $runtimePorts = $runtimeJson | ConvertFrom-Json
    $binding = $runtimePorts.$ContainerPort | Select-Object -First 1
    if ($null -eq $binding -or $binding.HostIp -ne '127.0.0.1' -or $binding.HostPort -ne $HostPort) {
        throw "服务 $Service 的端口发布异常：期望 127.0.0.1:$HostPort -> $ContainerPort，实际为 $runtimeJson。请强制重建该服务。"
    }
}

function Wait-ServiceHealthy {
    param([Parameter(Mandatory)][string]$Service)
    $containerId = Get-ServiceContainerId -Service $Service
    $deadline = (Get-Date).AddMinutes(2)
    do {
        $state = (& docker inspect $containerId --format '{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{end}}').Trim()
        if ($state -eq 'running|healthy') { return }
        if ($state -match '^(exited|dead)\|') { throw "服务 $Service 已退出，请查看 Compose 日志。" }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "等待服务 $Service 健康状态超时，当前状态：$state"
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw '未找到 docker 命令，请先安装并启动 Docker Desktop。'
}

Invoke-DockerCommand -Arguments @('compose', '-f', $composeFile, 'config', '--quiet')
try {
    Invoke-DockerCommand -Arguments @('compose', '-f', $composeFile, 'up', '-d')
} catch {
    Write-Host '请检查旧容器是否占用 13306、16379、9876、10909 或 10911：' -ForegroundColor Yellow
    & docker ps --format 'table {{.Names}}\t{{.Ports}}'
    throw
}

foreach ($service in $expectedPorts.Keys) { Wait-ServiceHealthy -Service $service }
foreach ($entry in $expectedPorts.GetEnumerator()) {
    Assert-PublishedPort -Service $entry.Key -ContainerPort $entry.Value.ContainerPort -HostPort $entry.Value.HostPort
}
Assert-PublishedPort -Service broker -ContainerPort '10911/tcp' -HostPort '10911'

foreach ($hostPort in @('9876', '10909', '10911', '13306', '16379')) {
    $connected = Test-NetConnection 127.0.0.1 -Port ([int]$hostPort) -InformationLevel Quiet -WarningAction SilentlyContinue
    if (-not $connected) { throw "127.0.0.1:$hostPort 未通过 TCP 连通性检查。" }
}

Invoke-DockerCommand -Arguments @('compose', '-f', $composeFile, 'ps', '-a')
Write-Host 'MySQL、Redis、RocketMQ 的健康状态、端口发布和本机 TCP 连通性均通过。' -ForegroundColor Green
