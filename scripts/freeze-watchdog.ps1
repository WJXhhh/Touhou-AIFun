# Freeze watchdog: when the Minecraft window stops responding, capture a thread dump.
$ErrorActionPreference = "Continue"
$repo = "C:\Users\WJC\Documents\development\Touhou-AIFun"
$dumpDir = Join-Path $repo "run\logs\hang-dumps"
New-Item -ItemType Directory -Force $dumpDir | Out-Null
$watchLog = Join-Path $dumpDir "watchdog.log"
$jdk = "C:\Program Files\Java\jdk-17.0.20\bin"
$lastDump = [datetime]::MinValue
$strikes = 0
$lastPid = 0
"[{0}] watchdog started" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') | Out-File $watchLog -Append -Encoding utf8
while ($true) {
    $gamePid = 0
    foreach ($p in (Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue)) {
        if ($p.CommandLine -and $p.CommandLine -match 'BootstrapLauncher') { $gamePid = [int]$p.ProcessId; break }
    }
    if ($gamePid -eq 0) {
        if ($lastPid -ne 0) { "[{0}] game exited pid={1}" -f (Get-Date -Format 'HH:mm:ss'), $lastPid | Out-File $watchLog -Append -Encoding utf8 }
        $lastPid = 0; $strikes = 0
        Start-Sleep -Seconds 3
        continue
    }
    if ($gamePid -ne $lastPid) {
        "[{0}] watching game pid={1}" -f (Get-Date -Format 'HH:mm:ss'), $gamePid | Out-File $watchLog -Append -Encoding utf8
        $lastPid = $gamePid
        $strikes = 0
    }
    try {
        $proc = Get-Process -Id $gamePid -ErrorAction Stop
        $responding = $proc.Responding
        $cpu = $proc.CPU
    } catch {
        Start-Sleep -Seconds 2
        continue
    }
    if (-not $responding) {
        $strikes++
    } else {
        $strikes = 0
    }
    if ($strikes -ge 2 -and ((Get-Date) - $lastDump).TotalSeconds -gt 90) {
        $lastDump = Get-Date
        $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
        $out = Join-Path $dumpDir "hang-$stamp.txt"
        try {
            $proc.Refresh()
            $trig = "[{0}] HANG pid={1} responding=False cpu={2:N1}s totalCpu={3:N1}s" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $gamePid, $proc.TotalProcessorTime.TotalSeconds, $cpu
            $trig | Out-File $out -Encoding utf8
            & "$jdk\jstack.exe" -l $gamePid 2>&1 | Out-File $out -Append -Encoding utf8
            "`n===== GC.heap_info =====" | Out-File $out -Append -Encoding utf8
            & "$jdk\jcmd.exe" $gamePid GC.heap_info 2>&1 | Out-File $out -Append -Encoding utf8
            "dump saved: $out" | Out-File $watchLog -Append -Encoding utf8
        } catch {
            "dump failed: $_" | Out-File $watchLog -Append -Encoding utf8
        }
        $strikes = 0
    }
    Start-Sleep -Seconds 2
}
