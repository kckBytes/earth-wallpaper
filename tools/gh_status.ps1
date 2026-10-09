param([string]$repo = 'kckBytes/earth-live', [int]$tail = 40)
# Latest Actions run of a repo: status, step results, and the tail of the build log.
$env:GCM_INTERACTIVE = 'never'
$f = "$env:TEMP\cq.txt"; Set-Content $f "protocol=https`nhost=github.com`n" -NoNewline -Encoding ascii
$c = cmd /c "git credential fill < `"$f`"" 2>$null; Remove-Item $f
$tok = (($c | ? { $_ -match '^password=' }) -replace '^password=', '')
$h = @{Authorization = "token $tok"; 'User-Agent' = 'earth'; Accept = 'application/vnd.github+json'}
$runs = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/actions/runs?per_page=3" -Headers $h
foreach ($r in $runs.workflow_runs) { "run $($r.id) $($r.event) $($r.status)/$($r.conclusion) started $($r.run_started_at)" }
$run = $runs.workflow_runs[0]
$jobs = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/actions/runs/$($run.id)/jobs" -Headers $h
foreach ($j in $jobs.jobs) {
  "job $($j.name): $($j.status)/$($j.conclusion)"
  foreach ($s in $j.steps) { "   $($s.number) $($s.name): $($s.status)/$($s.conclusion)" }
  if ($j.name -eq 'build' -and $j.status -eq 'completed') {
    try {
      $log = Invoke-WebRequest -UseBasicParsing -Uri "https://api.github.com/repos/$repo/actions/jobs/$($j.id)/logs" -Headers $h
      $lines = ($log.Content -split "`n") | ? { $_ -match 'newest frames|downloaded|IR|curve|coverage|elevation|water-vapour|visible|motion|published|Error|error|Traceback|File "|skipping|saved|republish' }
      $lines | Select -Last $tail | % { $_.Substring([Math]::Min(29, $_.Length)) }
    } catch { "log ERR $($_.Exception.Message)" }
  }
}
$tok = $null; $h = $null
