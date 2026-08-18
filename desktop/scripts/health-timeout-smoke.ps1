# D9-T03 real health-timeout check: polling a quiet loopback port must resolve to
# TIMEOUT within the deadline (never hang), with no residual process involved.
$ErrorActionPreference = 'Stop'

$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
$listener.Start(); $port = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
$listener.Stop()

$script = @"
const { waitForBackend } = require('D:/Dev/Projects/SupplyMind AI/desktop/src/health.js');
const start = Date.now();
waitForBackend('http://127.0.0.1:$port', { timeoutMs: 1500, intervalMs: 200 })
  .then((result) => {
    const elapsed = Date.now() - start;
    console.log('state=' + result.state + ' elapsed=' + elapsed + 'ms message=' + result.message);
    if (result.state !== 'TIMEOUT') { process.exit(2); }
    if (elapsed < 1200 || elapsed > 5000) { process.exit(3); }
    process.exit(0);
  })
  .catch((err) => { console.error(err); process.exit(4); });
"@

$node = (Get-Command node).Source
$output = & $node -e $script 2>&1
$exitCode = $LASTEXITCODE
Write-Host "[health-timeout] $output"
if ($exitCode -ne 0) { throw "[health-timeout] FAIL (exit $exitCode)" }
Write-Host '[health-timeout] PASS: TIMEOUT resolved within the deadline, no hang'
