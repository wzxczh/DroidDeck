# Compare live C/C++ sources against the pre-translation backups in .hanhua-orig.
# Checks per file: valid UTF-8, equal line count, per-line "string skeleton" (all
# string literals blanked) unchanged, per-line printf-specifier sequence unchanged,
# banner_log/__android_log_print tag arguments unchanged.
$ErrorActionPreference = 'Stop'
$root = 'D:\DroidDeck'
$origRoot = 'D:\DroidDeck\.hanhua-orig'

$pairs = @()
foreach ($d in @('waylandcomp_src', 'framegen', 'scanout', 'sdnative', 'shaders')) {
    $live = switch ($d) {
        'waylandcomp_src' { Join-Path $root 'app\src\main\cpp\waylandcomp\src' }
        default           { Join-Path $root ("app\src\main\cpp\" + $d) }
    }
    Get-ChildItem -Recurse -File (Join-Path $origRoot $d) | ForEach-Object {
        $rel = $_.FullName.Substring((Join-Path $origRoot $d).Length + 1)
        $pairs += [pscustomobject]@{ live = Join-Path $live $rel; orig = $_.FullName }
    }
}
$pairs += [pscustomobject]@{ live = (Join-Path $root 'app\src\main\cpp\fakeinput_steam.cpp'); orig = (Join-Path $origRoot 'fakeinput_steam.cpp') }

function Get-Skeleton([string]$line) { [regex]::Replace($line, '"(?:[^"\\]|\\.)*"', '""') }
function Get-Specs([string]$line) {
    (([regex]::Matches($line, '%[-+ #0-9.*]*(?:hh|h|ll|l|z|j|t|L)?[diouxXeEfgGaAcspn%]') | ForEach-Object { $_.Value }) -join '|')
}
function Get-Tags([string]$text, [string]$pat) {
    ([regex]::Matches($text, $pat) | ForEach-Object { $_.Groups[1].Value }) -join "`u{1}"
}
$bannerPat = 'banner_log\(\s*"((?:[^"\\]|\\.)*)"'
$alogPat   = '__android_log_print\(\s*[^,]+,\s*"((?:[^"\\]|\\.)*)"'

$fail = 0
$changed = 0
foreach ($p in $pairs) {
    if (-not (Test-Path $p.live)) { continue }
    $enc = New-Object System.Text.UTF8Encoding($false, $true)
    try { $newText = $enc.GetString([System.IO.File]::ReadAllBytes($p.live)) } catch { Write-Host "FAIL UTF8  $($p.live)"; $fail++; continue }
    $oldText = $enc.GetString([System.IO.File]::ReadAllBytes($p.orig))
    $newLines = $newText -split "`n", -1
    $oldLines = $oldText -split "`n", -1
    $rel = $p.live.Substring($root.Length + 1)
    if ($newLines.Count -ne $oldLines.Count) { Write-Host "FAIL LINES $rel ($($oldLines.Count) -> $($newLines.Count))"; $fail++; continue }
    $problems = @()
    $diffLines = @()
    for ($i = 0; $i -lt $oldLines.Count; $i++) {
        $o = $oldLines[$i]; $n = $newLines[$i]
        if ($o -ceq $n) { continue }
        $diffLines += ($i + 1)
        if ((Get-Skeleton $o) -cne (Get-Skeleton $n)) { $problems += "L$($i+1): skeleton" }
        if ((Get-Specs $o) -cne (Get-Specs $n)) { $problems += "L$($i+1): spec '$(Get-Specs $o)' -> '$(Get-Specs $n)'" }
    }
    if ((Get-Tags $oldText $bannerPat) -cne (Get-Tags $newText $bannerPat)) { $problems += 'banner_log tag list changed' }
    if ((Get-Tags $oldText $alogPat) -cne (Get-Tags $newText $alogPat)) { $problems += '__android_log_print tag list changed' }
    if ($problems.Count) { Write-Host "FAIL $rel"; $problems | ForEach-Object { Write-Host "       $_" }; $fail++ }
    elseif ($diffLines.Count) { $changed++; Write-Host "OK   $rel  ($($diffLines.Count) changed lines)" }
}
Write-Host "---- files with changes: $changed ; failing files: $fail"
