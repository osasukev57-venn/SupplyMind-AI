param(
    [string]$WorkspaceRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
)

$ErrorActionPreference = 'Stop'
$artifactDir = Join-Path $PSScriptRoot 'artifacts'
$reportDir = Join-Path $WorkspaceRoot 'backend\target\surefire-reports'
$summaryPath = Join-Path $artifactDir 'backend-surefire-summary.json'
$archivePath = Join-Path $artifactDir 'backend-surefire-xml.zip'
$matrixPath = Join-Path $artifactDir 'web-p0-full-matrix.json'
$dependencyPath = Join-Path $artifactDir 'maven-dependency-tree.txt'

$xmlFiles = @(Get-ChildItem -LiteralPath $reportDir -Filter 'TEST-*.xml' -File | Sort-Object Name)
if ($xmlFiles.Count -eq 0) { throw 'No Surefire XML reports found; run .\mvnw.cmd clean test first.' }

$suites = foreach ($file in $xmlFiles) {
    [xml]$document = [IO.File]::ReadAllText($file.FullName)
    $suite = $document.testsuite
    [ordered]@{
        name = [string]$suite.name
        tests = [int]$suite.tests
        failures = [int]$suite.failures
        errors = [int]$suite.errors
        skipped = [int]$suite.skipped
        timeSeconds = [string]$suite.time
        xmlFile = $file.Name
        xmlSha256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
    }
}

$totals = [ordered]@{
    suites = $suites.Count
    tests = [int](($suites | ForEach-Object { $_['tests'] } | Measure-Object -Sum).Sum)
    failures = [int](($suites | ForEach-Object { $_['failures'] } | Measure-Object -Sum).Sum)
    errors = [int](($suites | ForEach-Object { $_['errors'] } | Measure-Object -Sum).Sum)
    skipped = [int](($suites | ForEach-Object { $_['skipped'] } | Measure-Object -Sum).Sum)
}
if ($totals.failures -ne 0 -or $totals.errors -ne 0) { throw 'Surefire result is not clean.' }

if (Test-Path -LiteralPath $archivePath) { Remove-Item -LiteralPath $archivePath -Force }
Compress-Archive -LiteralPath ($xmlFiles.FullName) -DestinationPath $archivePath -CompressionLevel Optimal

$summary = [ordered]@{
    schemaVersion = 'DAY8-RUNNER-EVIDENCE-V1'
    generatedAt = [DateTimeOffset]::Now.ToString('o')
    command = '.\mvnw.cmd clean test'
    result = 'PASS'
    totals = $totals
    rawRunnerArchive = 'docs/evidence/Day8/artifacts/backend-surefire-xml.zip'
    rawRunnerArchiveSha256 = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash
    suites = $suites
}
$utf8NoBom = [Text.UTF8Encoding]::new($false)
$summaryJson = ($summary | ConvertTo-Json -Depth 8).Replace("`r`n", "`n") + "`n"
[IO.File]::WriteAllText($summaryPath, $summaryJson, $utf8NoBom)

$suiteMap = @{
    'AT-SRC-001' = @('com.supplymind.acceptance.MaterialDay3AcceptanceTest','com.supplymind.routing.MaterialRoutingTest')
    'AT-SRC-003' = @('com.supplymind.acceptance.MaterialRoutePlanProductionTest','com.supplymind.day5.r2.Day5FinalProviderCapabilityDeclarationTest')
    'AT-SRC-004' = @('com.supplymind.acceptance.MaterialRoutePlanProductionTest','com.supplymind.day5.r2.Day5FinalProviderCapabilityDeclarationTest')
    'AT-SRC-005-D4' = @('com.supplymind.day4.foundation.AtSrcDay4ReconciliationHarnessTest','com.supplymind.day4.foundation.FutureMaterialDay4ContractTest')
    'AT-SRC-007-D4' = @('com.supplymind.acceptance.MaterialDay3AcceptanceTest','com.supplymind.manual.ManualMaterialIntakeTest')
    'AT-SRC-008-D4' = @('com.supplymind.acceptance.MaterialDay3AcceptanceTest','com.supplymind.localimport.SyntheticDemoIsolationTest')
    'AT-PUB-001' = @('com.supplymind.publish.PublishGateTest','com.supplymind.day4.foundation.AtPubDay4ContractHarnessTest')
    'AT-PUB-003' = @('com.supplymind.foundation.storage.RawAndConfigStoreTest','com.supplymind.foundation.storage.RawAcquisitionLinkVerifierTest')
    'AT-PREC-001' = @('com.supplymind.day4.foundation.AtPrecDay4ContractHarnessTest','com.supplymind.foundation.model.QuarantineAndPrecisionV1Test')
    'AT-PREC-002' = @('com.supplymind.foundation.codec.CsvV1CodecTest','com.supplymind.foundation.codec.JsonV1CodecTest')
    'AT-PREC-003' = @('com.supplymind.day4.foundation.AtPrecDay4ContractHarnessTest','com.supplymind.processing.AggregateCalculatorTest')
    'AT-AGG-001' = @('com.supplymind.day4.foundation.AtAggDay4ContractHarnessTest','com.supplymind.processing.MaterialDailyAggregateTest')
    'AT-AGG-002' = @('com.supplymind.day4.foundation.Day4DailyAggregateSchemaContractTest','com.supplymind.processing.AggregateProcessingServiceTest')
    'AT-AGG-003' = @('com.supplymind.processing.DailyProcessingServiceTest','com.supplymind.processing.AggregateCalculatorTest')
    'AT-FILE-000' = @('com.supplymind.foundation.acceptance.IndependentFormatContractAcceptanceTest','com.supplymind.foundation.acceptance.AtFile000RecoveryManifestRootAcceptanceTest')
    'AT-FILE-001' = @('com.supplymind.foundation.storage.DataRootAndPathsTest','com.supplymind.foundation.acceptance.GoldenFixtureContractAcceptanceTest')
    'AT-FILE-002' = @('com.supplymind.processing.AggregateProcessingServiceTest','com.supplymind.foundation.storage.ManifestDerivedFieldsVerifierTest')
    'AT-TIME-001' = @('com.supplymind.rotation.TimeRotationServiceTest','com.supplymind.day5.foundation.Day5TimeContractHarnessTest')
    'AT-TIME-002' = @('com.supplymind.rotation.TimeRotationServiceTest','com.supplymind.day5.r2.Day5R2RotationHistoryCapabilityAttackTest')
    'AT-XR-001' = @('com.supplymind.history.HistoryQueryServiceTest','com.supplymind.day5.foundation.Day5CrossYearHistoryContractHarnessTest')
    'AT-XR-002' = @('com.supplymind.history.HistoryQueryServiceTest','com.supplymind.day5.foundation.Day5CrossYearHistoryContractHarnessTest')
    'AT-CFG-002' = @('com.supplymind.config.CurrentIntakeAttackTest','com.supplymind.config.DynamicConfigWorkflowServiceTest')
    'AT-CFG-003' = @('com.supplymind.backfill.BackfillOrchestratorTest','com.supplymind.config.CurrentIntakeAttackTest')
    'AT-UI-002' = @('com.supplymind.dashboard.DashboardServiceTest','com.supplymind.config.ConfigManagementServiceTest')
    'AT-ALT-001' = @('com.supplymind.warning.WarningServiceTest','com.supplymind.day5.foundation.Day5AlertContractHarnessTest')
    'AT-ALT-002' = @('com.supplymind.warning.WarningAckStoreTest','com.supplymind.warning.WarningApiMvcContractTest')
    'AT-NET-001' = @('com.supplymind.history.HistoryQueryServiceTest','com.supplymind.provider.pboc.PbocRawClosedLoopSmokeGateTest')
    'AT-AI-000' = @('com.supplymind.agent.AgentSpringAiToolCallingTest','com.supplymind.foundation.acceptance.FoundationStartupAcceptanceTest')
    'AT-AI-002' = @('com.supplymind.agent.AgentToolBoundaryTest','com.supplymind.agent.Day6R2IndependentSpringAiAttackTest')
    'AT-AI-003' = @('com.supplymind.agent.Day6R2IndependentEvidenceReportAttackTest','com.supplymind.agent.Day6FinalStageFullBindingAttackTest')
    'AT-OPS-001' = @('com.supplymind.foundation.storage.DataRootAndPathsTest','com.supplymind.foundation.acceptance.FoundationStartupAcceptanceTest')
    'AT-OPS-002' = @('com.supplymind.foundation.storage.DataRootAndPathsTest','com.supplymind.foundation.storage.AtomicFileRecoveryTest')
}

$knownSuites = @{}; foreach ($suite in $suites) { $knownSuites[$suite.name] = $suite }
$matrix = Get-Content -LiteralPath $matrixPath -Raw | ConvertFrom-Json
$summaryRel = 'docs/evidence/Day8/artifacts/backend-surefire-summary.json'
$archiveRel = 'docs/evidence/Day8/artifacts/backend-surefire-xml.zip'
$summarySha = (Get-FileHash -LiteralPath $summaryPath -Algorithm SHA256).Hash
$archiveSha = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash
$atSrcRoot = Join-Path $WorkspaceRoot 'docs\evidence\AT-SRC-002'

foreach ($entry in $matrix.matrix) {
    if ($entry.caseId -eq 'AT-SRC-002' -and $entry.result -eq 'PASS') {
        $xmlRel = 'docs/evidence/AT-SRC-002/TEST-com.supplymind.acceptance.AtSrc002AcceptanceTest.xml'
        $sumRel = 'docs/evidence/AT-SRC-002/at-src-002-summary.json'
        $entry.evidenceFiles = @($xmlRel, $sumRel)
        $entry.PSObject.Properties.Remove('evidenceSha')
        $entry | Add-Member -NotePropertyName evidenceSha256 -NotePropertyValue ([ordered]@{
            $xmlRel = (Get-FileHash -LiteralPath (Join-Path $atSrcRoot 'TEST-com.supplymind.acceptance.AtSrc002AcceptanceTest.xml') -Algorithm SHA256).Hash
            $sumRel = (Get-FileHash -LiteralPath (Join-Path $atSrcRoot 'at-src-002-summary.json') -Algorithm SHA256).Hash
        }) -Force
        $entry | Add-Member -NotePropertyName runnerSuites -NotePropertyValue @('com.supplymind.acceptance.AtSrc002AcceptanceTest') -Force
        continue
    }
    if ($entry.result -eq 'PASS' -and $suiteMap.ContainsKey($entry.caseId)) {
        foreach ($suiteName in $suiteMap[$entry.caseId]) {
            if (-not $knownSuites.ContainsKey($suiteName)) { throw "Mapped suite missing: $($entry.caseId) -> $suiteName" }
            if ($knownSuites[$suiteName].failures -ne 0 -or $knownSuites[$suiteName].errors -ne 0) {
                throw "Mapped suite failed: $($entry.caseId) -> $suiteName"
            }
        }
        $entry.evidenceFiles = @($summaryRel, $archiveRel)
        $entry.PSObject.Properties.Remove('evidenceSha')
        $entry | Add-Member -NotePropertyName evidenceSha256 -NotePropertyValue `
                ([ordered]@{ $summaryRel = $summarySha; $archiveRel = $archiveSha }) -Force
        $entry | Add-Member -NotePropertyName runnerSuites -NotePropertyValue @($suiteMap[$entry.caseId]) -Force
        if ($entry.caseId -in @('AT-AI-000','AT-OPS-001')) {
            if (-not (Test-Path -LiteralPath $dependencyPath)) { throw 'maven-dependency-tree.txt is required.' }
            $dependencyRel = 'docs/evidence/Day8/artifacts/maven-dependency-tree.txt'
            $entry.evidenceFiles = @($summaryRel, $archiveRel, $dependencyRel)
            $entry.evidenceSha256 = [ordered]@{
                $summaryRel = $summarySha
                $archiveRel = $archiveSha
                $dependencyRel = (Get-FileHash -LiteralPath $dependencyPath -Algorithm SHA256).Hash
            }
        }
    }
}
$matrix.generatedBy = 'capture-day8-final-runner-evidence.ps1 from immutable Surefire XML + tracked browser artifacts'
$matrixJson = ($matrix | ConvertTo-Json -Depth 12).Replace("`r`n", "`n") + "`n"
[IO.File]::WriteAllText($matrixPath, $matrixJson, $utf8NoBom)

Write-Output ("BACKEND_RUNNER_EVIDENCE PASS suites={0} tests={1} failures={2} errors={3} skipped={4}" -f `
    $totals.suites, $totals.tests, $totals.failures, $totals.errors, $totals.skipped)
