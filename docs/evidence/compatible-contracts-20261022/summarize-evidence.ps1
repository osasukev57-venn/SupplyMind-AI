param([string]$EvidenceRoot = $PSScriptRoot)
$results = @()
foreach ($directory in Get-ChildItem -LiteralPath $EvidenceRoot -Directory) {
    $reports = @(Get-ChildItem -LiteralPath $directory.FullName -Filter 'TEST-*.xml' -File)
    if ($reports.Count -eq 0) { continue }
    $total=0; $failures=0; $errors=0; $skipped=0; $jdks=@(); $skipReasons=@()
    foreach ($report in $reports) {
        [xml]$document = Get-Content -LiteralPath $report.FullName -Raw
        $suite=$document.testsuite
        $total += [int]$suite.tests; $failures += [int]$suite.failures
        $errors += [int]$suite.errors; $skipped += [int]$suite.skipped
        $jdks += @($suite.properties.property | Where-Object {$_.name -eq 'java.version'} | ForEach-Object {$_.value})
        foreach ($case in $suite.testcase) {
            if ($null -ne $case.skipped) {
                $skipReasons += [pscustomobject]@{test="$($case.classname)#$($case.name)";reason=$case.skipped.message}
            }
        }
    }
    $results += [pscustomobject]@{directory=$directory.Name; total=$total;passed=$total-$failures-$errors-$skipped;failures=$failures;errors=$errors;skipped=$skipped;jdk=@($jdks | Sort-Object -Unique);skipReasons=$skipReasons}
}
$results | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $EvidenceRoot 'verified-counts.json') -Encoding utf8
$results | Select-Object directory,total,passed,failures,errors,skipped,jdk | Format-Table
