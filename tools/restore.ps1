# Windows version of restore.sh: re-enable every package listed in a debloat file, restore the
# stock launcher as home and reset animation scales. Pass the same list you used with disable.ps1.
#
# Usage: tools\restore.ps1 -TvHost 192.168.1.50 examples\xiaomi-mi-tv-4a\debloat.txt [-Stock stock.launcher.package]
param(
    [Parameter(Mandatory = $true, Position = 0)][string]$List,
    [string]$Stock = 'com.google.android.tvlauncher',
    [string]$TvHost = $env:TV_HOST
)
$ErrorActionPreference = 'Stop'
# Pipe plain UTF-8 to native commands; the default here prepends a BOM that corrupts the first command.
$OutputEncoding = New-Object System.Text.UTF8Encoding $false
$repo = Split-Path -Parent $PSScriptRoot
$List = (Resolve-Path $List).Path
# $Stock is pasted into a shell command on the TV just like the list entries (#165): only a package name.
if ($Stock -notmatch '^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$') {
    throw "refusing -Stock '$Stock': not a package name"
}
$py = Join-Path $repo 'tools\venv\Scripts\python.exe'
if (-not $TvHost) { throw 'pass -TvHost <tv ip> or set $env:TV_HOST' }
$env:TV_HOST = $TvHost

# -Encoding UTF8, see disable.ps1: PowerShell 5.1 otherwise reads a UTF-8 list as ANSI.
# Comment parsing mirrors restore.sh: only a comment preceded by whitespace is stripped, and a
# whole-line comment is dropped — "pkg#note" stays whole and is refused by the name check.
$pkgs = Get-Content $List -Encoding UTF8 |
    ForEach-Object { ($_ -replace '\s+#.*$', '').Trim() } |
    Where-Object { $_ -and -not $_.StartsWith('#') } |
    ForEach-Object { ($_ -split '\s+')[0] }
# Same check as disable.ps1: the list is contributor-supplied (#165).
$bad = @($pkgs | Where-Object { $_ -notmatch '^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$' })
if ($bad.Count -gt 0) {
    throw "refusing $List, not a package name: $($bad -join ', ')"
}
$cmds = @($pkgs | ForEach-Object { "pm enable $_" }) + @(
    "pm enable $Stock",
    # resolve the stock HOME component on the TV (Android 9 needs pkg/activity, not just pkg)
    ('cmd package set-home-activity --user 0 $(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME ' + $Stock + ' | tail -n 1)'),
    'settings put global window_animation_scale 1.0',
    'settings put global transition_animation_scale 1.0',
    'settings put global animator_duration_scale 1.0',
    'pm list packages -d'
)
$cmds | & $py -I (Join-Path $repo 'tools\tv.py') --batch
# tv.py exits 1 when a command answered Error/Failure (the batch summary above lists which);
# $ErrorActionPreference does not see native exit codes — parity with the WARNING in restore.sh.
if ($LASTEXITCODE -ne 0) {
    Write-Warning "Some commands answered Error/Failure (see the batch summary above). Check where Home resolves to: tools\tv.py 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME'"
    Write-Warning "(see docs\TROUBLESHOOTING.md for the 'choose a Home app' screen after a restore)."
    exit $LASTEXITCODE
}
# Where Home actually points after the restore: set-home-activity can silently fail to stick
# (e.g. the stock launcher was reinstalled and its component does not resolve). A warning, not
# an error — the restore itself worked; this just says the last step out loud.
$homeNow = (& $py -I (Join-Path $repo 'tools\tv.py') 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME' | Select-Object -Last 1)
if (-not $homeNow -or ((($homeNow -split '/')[0]).Trim()) -ne $Stock) {
    Write-Warning "Home now resolves to '$homeNow', not $Stock. Re-run the set-home-activity line above by hand, or see docs\TROUBLESHOOTING.md."
}
