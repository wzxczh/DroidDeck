# Apply an exact-text translation table to one or more source files.
# Table format (UTF-8):
#   #TARGET=<absolute path>
#   #OLD
#   <exact old text>
#   #NEW
#   <exact new text>
#   #OLD ...
#   #TARGET=...   (next file)
# Replacement is ordinal (no regex). Every #OLD must be found; count is reported.
$ErrorActionPreference = 'Stop'
$tablePath = if ($args.Count -gt 0) { $args[0] } else { $env:HANHUA_TABLE }
if (-not $tablePath) { throw 'usage: apply.ps1 <table>' }
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$utf8Bom   = New-Object System.Text.UTF8Encoding($true)

$raw = [System.IO.File]::ReadAllText($tablePath) -replace "`r`n", "`n"
$lines = $raw -split "`n"
if ($lines.Count -ge 2 -and $lines[$lines.Count - 1] -eq '') { $lines = $lines[0..($lines.Count - 2)] }

$jobs = @()
$cur = $null
$mode = $null      # 'old' | 'new' | $null
$acc = @()
$old = $null

function Flush-Pair {
    if ($script:mode -eq 'new' -and $null -ne $script:cur) {
        $script:cur.pairs += ,@($script:old, ($script:acc -join "`n"))
    }
    $script:mode = $null
    $script:acc = @()
}

foreach ($ln in $lines) {
    if ($ln.StartsWith('#TARGET=')) {
        Flush-Pair
        if ($cur) { $jobs += $cur }
        $cur = @{ target = $ln.Substring(8).Trim(); pairs = @() }
        continue
    }
    if ($ln -eq '#OLD') {
        Flush-Pair
        $mode = 'old'; $acc = @()
        continue
    }
    if ($ln -eq '#NEW') {
        if ($mode -ne 'old') { throw "table error: #NEW without #OLD" }
        $old = ($acc -join "`n")
        $mode = 'new'; $acc = @()
        continue
    }
    if ($null -ne $mode) { $acc += $ln }
}
Flush-Pair
if ($cur) { $jobs += $cur }

$miss = 0
foreach ($job in $jobs) {
    $t = $job.target
    if (-not (Test-Path $t)) { Write-Host "MISS (file not found) $t"; $miss++; continue }
    $bytes = [System.IO.File]::ReadAllBytes($t)
    $hasBom = $bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF
    $text = if ($hasBom) { $utf8Bom.GetString($bytes) } else { $utf8NoBom.GetString($bytes) }
    $crlf = $text.Contains("`r`n")
    $work = $text.Replace("`r`n", "`n")
    $nApplied = 0
    foreach ($pair in $job.pairs) {
        $o = $pair[0]; $n = $pair[1]
        $oc = ([regex]::Matches($o, "`n")).Count; $nc = ([regex]::Matches($n, "`n")).Count
        if ($oc -ne $nc -and -not $env:HANHUA_ALLOW_LINES) { Write-Host "LINES in ${t}: old has $oc newlines, new has $nc :: $(($o -split "`n")[0])"; $miss++; continue }
        if ($oc -ne $nc) { Write-Host "LINES-OK (allowed) in ${t}: $oc -> $nc" }
        $c = 0; $idx = 0
        while (($i = $work.IndexOf($o, $idx, [StringComparison]::Ordinal)) -ge 0) { $c++; $idx = $i + $o.Length }
        if ($c -eq 0) { $first = ($o -split "`n")[0]; Write-Host "MISS in ${t}: $first"; $miss++; continue }
        $work = $work.Replace($o, $n)
        $nApplied++
        if ($c -gt 1) { $first = ($o -split "`n")[0]; Write-Host "WARN x$c in ${t}: $first" }
    }
    if ($nApplied -gt 0) {
        $out = if ($crlf) { $work.Replace("`n", "`r`n") } else { $work }
        $enc = if ($hasBom) { $utf8Bom } else { $utf8NoBom }
        [System.IO.File]::WriteAllText($t, $out, $enc)
    }
    Write-Host "APPLIED $nApplied/$($job.pairs.Count)  $t"
}
if ($miss -gt 0) { Write-Host "TOTAL MISSES: $miss"; exit 1 } else { Write-Host 'ALL OK' }
