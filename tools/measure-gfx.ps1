# Measures frame pacing and memory of a running Lane Dash build on a USB-connected phone.
#
# Usage (PowerShell, phone connected with USB debugging on, game already running a round):
#   .\tools\measure-gfx.ps1            # resets counters, waits 15 s while you play, prints a summary
#   .\tools\measure-gfx.ps1 -Seconds 30
#
# Reading the numbers: "Janky frames" should stay in the low single digits (%), and the 90th
# percentile frame time should stay under ~16 ms (60 Hz) / ~8 ms (120 Hz). If the percentiles
# climb after a graphics change, check whether the game's FxQuality fallback has kicked in.
param(
    [int]$Seconds = 15,
    [string]$Package = 'com.khcompany.lanedash'
)

$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
if (-not (Test-Path $adb)) { $adb = 'adb' }

$devices = & $adb devices | Select-String -Pattern "`tdevice$"
if (-not $devices) {
    Write-Host 'No phone found. Connect it with USB debugging enabled and try again.'
    exit 1
}

& $adb shell dumpsys gfxinfo $Package reset | Out-Null
Write-Host "Counters reset. Play for $Seconds seconds..."
Start-Sleep -Seconds $Seconds

Write-Host ''
Write-Host '== Frame pacing'
& $adb shell dumpsys gfxinfo $Package |
    Select-String -Pattern 'Total frames rendered|Janky frames|50th percentile|90th percentile|95th percentile|99th percentile|Number Missed Vsync|Number Slow UI|Number Slow issue draw'

Write-Host ''
Write-Host '== Memory (KB)'
& $adb shell dumpsys meminfo $Package |
    Select-String -Pattern 'Native Heap|Java Heap|Graphics|TOTAL PSS'
