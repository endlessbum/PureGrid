# Windows version of build-and-install.sh: build the launcher APK and install it on the TV as the
# default home screen. Uses Android Studio's bundled JDK and the default SDK location unless
# JAVA_HOME / ANDROID_HOME are set.
#
# Usage: launcher\build-and-install.ps1 -TvHost 192.168.1.50 [-StockLauncher <package>|none]
#        The stock launcher is detected from the TV when -StockLauncher is not given.
#        [-InputKeyService]   remote's Input button opens the input switcher (Google TV); re-pass on every install
#        [-HomeKeyService]    remotes whose Home button sends KEYCODE_ALL_APPS (Xiaomi sticks)
param(
    [string]$TvHost = $env:TV_HOST,
    [string]$StockLauncher = '',
    [switch]$InputKeyService,
    [switch]$HomeKeyService
)
$ErrorActionPreference = 'Stop'
# Pipe plain UTF-8 to native commands; the default here prepends a BOM that corrupts the first command.
$OutputEncoding = New-Object System.Text.UTF8Encoding $false
if (-not $TvHost) { throw 'pass -TvHost <tv ip> or set $env:TV_HOST' }
$env:TV_HOST = $TvHost
Set-Location $PSScriptRoot

if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    $jbr = Get-ChildItem 'C:\Program Files\Android' -Directory -ErrorAction SilentlyContinue |
        ForEach-Object { Join-Path $_.FullName 'jbr' } | Where-Object { Test-Path "$_\bin\java.exe" } | Select-Object -First 1
    if (-not $jbr) { throw 'Set JAVA_HOME to a JDK 17+ (Android Studio ships one in its jbr folder)' }
    $env:JAVA_HOME = $jbr
}
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
if (-not (Test-Path local.properties)) {
    "sdk.dir=$($sdk -replace '\\', '/')" | Out-File -Encoding ascii local.properties
}

.\gradlew.bat assembleRelease -q --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed' }

$py = '..\tools\venv\Scripts\python.exe'
# Which app owns Home differs by build; ask the TV, but only accept a launcher we recognise (the answer
# can also be Android's chooser "android", the setup wizard, or another third-party launcher).
$knownStock = @('com.google.android.tvlauncher', 'com.google.android.apps.tv.launcherx', 'com.google.android.leanbacklauncher', 'com.mitv.tvhome')
if (-not $StockLauncher) {
    $homePkg = & $py -I ..\tools\tv.py 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME' | Select-Object -Last 1
    if ($LASTEXITCODE -ne 0 -or -not $homePkg) { throw 'could not ask the TV which launcher owns Home; check -TvHost and the debugging prompt, or pass -StockLauncher <package>' }
    $homePkg = "$homePkg".Trim().Split('/')[0]
    if ($knownStock -contains $homePkg) { $StockLauncher = $homePkg }
    elseif ($homePkg -eq 'tv.puregrid.launcher') { $StockLauncher = 'none'; Write-Host 'Home already belongs to this launcher; nothing will be disabled.' }
    elseif ($homePkg -eq 'android') {
        # Android's chooser: launchers tie and no default is set (a restore followed by a re-applied list
        # ends here). Take the one known stock launcher among the HOME claimants, if there is exactly one.
        $claimants = (& $py -I ..\tools\tv.py 'cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME') -join "`n"
        $found = @($knownStock | Where-Object { $claimants -match ([regex]::Escape($_) + '/') })
        if ($found.Count -eq 1) { $StockLauncher = $found[0]; Write-Host "Home is Android's chooser; the stock launcher among the candidates is $StockLauncher." }
        else { throw "Home is Android's launcher chooser and $($found.Count) known stock launchers claim Home: pick a default on the TV first, or run again with -StockLauncher <package to disable> or -StockLauncher none." }
    }
    else { throw "Home currently resolves to '$homePkg', which is not a stock launcher this script knows (android = launcher chooser, com.google.android.tungsten.setupwraith = setup wizard, see README Troubleshooting; anything else = another launcher is the default). Run again with -StockLauncher <package to disable> or -StockLauncher none." }
}
Write-Host "Stock launcher: $StockLauncher"
# The value is pasted into shell commands on the TV below (pm disable-user/pm enable, the recovery
# file), exactly like -Stock in restore.ps1 and the debloat list entries: only a package name, or
# "none". A typo like "launcher; reboot" must be refused here, not executed on the TV.
if ($StockLauncher -ne 'none' -and $StockLauncher -notmatch '^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$') {
    throw "refusing -StockLauncher '$StockLauncher': not a package name"
}
$disableStock = if ($StockLauncher -eq 'none') { 'echo stock launcher left alone' } else { "pm disable-user --user 0 $StockLauncher" }

$apk = 'app\build\outputs\apk\release\app-release.apk'
& $py -I ..\tools\b64push.py $apk /data/local/tmp/tvhome.apk
if ($LASTEXITCODE -ne 0) { throw 'APK upload failed' }
$out = & $py -I ..\tools\tv.py 'pm install -r /data/local/tmp/tvhome.apk; rm -f /data/local/tmp/tvhome.apk'
Write-Host $out
if ("$out" -notmatch 'Success') { throw 'pm install failed; the stock launcher was left untouched' }
# Android 9 only accepts a component here; Android 10+ also takes a bare package name.
$out = & $py -I ..\tools\tv.py 'cmd package set-home-activity --user 0 tv.puregrid.launcher/.MainActivity'
Write-Host $out
if ("$out" -match 'Error') { throw 'could not set the new launcher as home; the stock launcher was left untouched' }
# Start the launcher on its own, before the stock Home is touched (see build-and-install.sh, #239):
# in the batch a failed start only prints an Error line and the stock launcher would be disabled anyway.
$start = (& $py -I ..\tools\tv.py 'am start -W -n tv.puregrid.launcher/.MainActivity') -join "`n"
Write-Host $start
if ($start -notmatch 'Status: ok' -or $start -match '(?m)^Error') { throw "the new launcher did not start (see above); $StockLauncher was left enabled" }
$batchOut = @(
    'pm grant tv.puregrid.launcher android.permission.ACCESS_FINE_LOCATION 2>/dev/null && echo location-granted || echo location-grant-failed',
    'pm grant tv.puregrid.launcher android.permission.WRITE_SECURE_SETTINGS 2>/dev/null && echo secure-granted || echo secure-grant-failed',
    'pm grant tv.puregrid.launcher android.permission.GET_ACCOUNTS 2>/dev/null && echo account-granted || echo account-grant-failed',
    'pm grant tv.puregrid.launcher android.permission.READ_CONTACTS 2>/dev/null && echo contacts-granted || echo contacts-grant-failed',
    'cmd package compile -m speed -f tv.puregrid.launcher',
    $disableStock,
    'cmd package set-home-activity --user 0 tv.puregrid.launcher/.MainActivity'
) | & $py -I ..\tools\tv.py --batch
$batchOut | Write-Host
if ($LASTEXITCODE -ne 0) { Write-Host 'WARNING: the connection dropped during the batch; the switch may be half-done — checking and rolling back below.' -ForegroundColor Yellow }
# A pm grant refusal prints a *-grant-failed marker and keeps going (so a lost connection cannot
# hide the rollback below); without checking it, a degraded install — no location: weather falls
# back to IP, no contacts: the pill keeps the mono letter — would look fully successful. The
# marker must match as a whole output LINE: the batch also echoes the command itself, whose text
# contains "location-grant-failed" even when the grant succeeded.
$failedGrants = @(@($batchOut) -match '^(location|secure|account|contacts)-grant-failed\s*$')
if ($failedGrants.Count -gt 0) {
    Write-Host "WARNING: some permissions were not granted (markers above): $($failedGrants -join ', '). The launcher still works, but the features behind them degrade silently:" -ForegroundColor Yellow
    Write-Host 'weather by location falls back to IP, the account pill keeps the mono letter.' -ForegroundColor Yellow
}
# Order matters, see build-and-install.sh: start the new launcher, disable the stock one, set the
# preference last, then check where Home ended up and put the stock launcher back if it is not ours.
# Empty output means "the TV stopped answering", not "no Home": the branches below tell the two apart.
$homeNow = ((& $py -I ..\tools\tv.py 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME' 2>$null | Select-Object -Last 1) -as [string]).Trim()
if ($homeNow -ne 'tv.puregrid.launcher' -and -not $homeNow.StartsWith('tv.puregrid.launcher/')) {
    # Put the stock launcher back and check that it took. A TV whose new Home does not start can only
    # be rescued over ADB, so the commands also go into a local file: when it is the connection that
    # broke, the terminal that ran this script may never come back.
    $rolledBack = $false
    if ($StockLauncher -ne 'none') {
        & $py -I ..\tools\tv.py "pm enable --user 0 $StockLauncher" 2>$null | Out-Null
        # set-home-activity needs pkg/activity on Android 9, so resolve the stock component on the TV.
        & $py -I ..\tools\tv.py "cmd package set-home-activity --user 0 \$(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME $StockLauncher | tail -n 1)" 2>$null | Out-Null
        $back = ((& $py -I ..\tools\tv.py 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME' 2>$null | Select-Object -Last 1) -as [string]).Trim()
        if ($back.StartsWith("$StockLauncher/")) { Write-Host "$StockLauncher owns Home again."; $rolledBack = $true }
    }
    if (-not $rolledBack) {
        if (-not $homeNow) {
            throw 'the TV stopped answering while Home was being switched, so the outcome is unknown.'
        }
        if ($StockLauncher -ne 'none') {
            $rec = Join-Path $env:USERPROFILE ("puregrid-handover-" + ($TvHost -replace '[^A-Za-z0-9._-]', '_') + '.txt')
            @(
                "# puregrid: the launcher handover on $TvHost did not finish. From the repo root:"
                "TV_HOST=$TvHost tools/tv.py 'pm enable --user 0 $StockLauncher'"
                "TV_HOST=$TvHost tools/tv.py 'cmd package set-home-activity --user 0 \$(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME $StockLauncher | tail -n 1)'"
                "# then check: TV_HOST=$TvHost tools/tv.py 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME'"
            ) | Set-Content -Path $rec -Encoding UTF8
            throw "recovery is incomplete; Home still resolves to '$homeNow'. The commands that finish it are in $rec; the TV still needs ADB for them."
        }
        throw "Home resolves to '$homeNow', not to the new launcher, and no stock launcher was disabled; nothing to roll back."
    }
    throw "Home resolves to '$homeNow', not to the new launcher; $StockLauncher was re-enabled. On Google TV this usually means com.google.android.tungsten.setupwraith also claims Home: add it to your debloat list (see README, Troubleshooting), apply it, then run this script again."
}
Write-Host "Home now resolves to $homeNow"
# Optional accessibility services, appended to enabled_accessibility_services without dropping others.
function Enable-Service([string]$Name) {
    $svc = "tv.puregrid.launcher/.$Name"
    & $py -I ..\tools\tv.py ('s=$(settings get secure enabled_accessibility_services); case "$s" in *' + $Name + '*) ;; null|"") settings put secure enabled_accessibility_services ' + $svc + ' ;; *) settings put secure enabled_accessibility_services "$s:' + $svc + '" ;; esac; settings put secure accessibility_enabled 1; echo ' + $Name + ' enabled')
}
if ($InputKeyService) { Enable-Service 'InputKeyService' }
if ($HomeKeyService) { Enable-Service 'HomeKeyService' }
Write-Host 'Installed. Press Home on the remote to see it.'
