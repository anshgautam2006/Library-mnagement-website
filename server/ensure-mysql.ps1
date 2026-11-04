$ErrorActionPreference = 'Stop'
$dbHost = if ($env:LMS_DB_HOST) { $env:LMS_DB_HOST } else { '127.0.0.1' }
$dbPort = if ($env:LMS_DB_PORT) { [int]$env:LMS_DB_PORT } else { 3306 }

function Test-MySqlPort {
    param([string]$HostName, [int]$Port)

    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $connect = $client.BeginConnect($HostName, $Port, $null, $null)
        if (-not $connect.AsyncWaitHandle.WaitOne(1200)) { return $false }
        $client.EndConnect($connect)
        return $true
    }
    catch {
        return $false
    }
    finally {
        $client.Dispose()
    }
}

if (Test-MySqlPort -HostName $dbHost -Port $dbPort) {
    Write-Host "MySQL is already accepting connections at ${dbHost}:${dbPort}."
    exit 0
}

if ($dbHost -notin @('127.0.0.1', 'localhost', '::1')) {
    [Console]::Error.WriteLine("MySQL at ${dbHost}:${dbPort} is not reachable. Check LMS_DB_HOST and LMS_DB_PORT.")
    exit 1
}

if ($env:LMS_DB_SERVICE_NAME) {
    $serviceName = $env:LMS_DB_SERVICE_NAME
}
else {
    $serviceNames = @(
        Get-ChildItem 'HKLM:\SYSTEM\CurrentControlSet\Services' -ErrorAction SilentlyContinue |
            ForEach-Object {
                $serviceKey = Get-ItemProperty -LiteralPath $_.PSPath -Name ImagePath -ErrorAction SilentlyContinue
                if ($serviceKey.ImagePath -match '(?i)mysqld\.exe') { $_.PSChildName }
            }
    )

    if ($serviceNames.Count -eq 0) {
        [Console]::Error.WriteLine('No installed MySQL Windows service was found. Install and configure MySQL Server first.')
        exit 1
    }
    if ($serviceNames.Count -gt 1) {
        [Console]::Error.WriteLine("Multiple MySQL services were found: $($serviceNames -join ', '). Set LMS_DB_SERVICE_NAME to the service to start.")
        exit 1
    }
    $serviceName = $serviceNames[0]
}

if ($serviceName -notmatch '^[A-Za-z0-9_.-]+$') {
    [Console]::Error.WriteLine("The MySQL service name '$serviceName' contains unsupported characters. Set LMS_DB_SERVICE_NAME to the Windows service name.")
    exit 1
}

Write-Host "MySQL is stopped. Windows will ask for administrator approval to start '$serviceName'."
$scExe = Join-Path $env:SystemRoot 'System32\sc.exe'

try {
    # Elevate only the Windows service-control utility, not this project script.
    $adminProcess = Start-Process -FilePath $scExe -Verb RunAs -WindowStyle Hidden -Wait -PassThru -ArgumentList "start $serviceName"
    if ($adminProcess.ExitCode -notin @(0, 1056)) {
        [Console]::Error.WriteLine("Windows could not start MySQL service '$serviceName' (service control exit code $($adminProcess.ExitCode)).")
        exit 1
    }
}
catch {
    [Console]::Error.WriteLine("Windows did not approve starting MySQL. Run PowerShell as administrator and use: Start-Service $serviceName")
    exit 1
}

for ($attempt = 0; $attempt -lt 30; $attempt++) {
    if (Test-MySqlPort -HostName $dbHost -Port $dbPort) {
        Write-Host "MySQL is ready at ${dbHost}:${dbPort}."
        exit 0
    }
    Start-Sleep -Seconds 1
}

[Console]::Error.WriteLine("The MySQL service started, but nothing is accepting connections at ${dbHost}:${dbPort}. Check MySQL's port in its configuration or set LMS_DB_PORT.")
exit 1
