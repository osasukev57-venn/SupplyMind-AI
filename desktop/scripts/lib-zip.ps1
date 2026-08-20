# Deterministic ZIP writer (D9 Final Attack F1).
# Produces a byte-identical ZIP for identical directory input: entries are sorted,
# timestamps are fixed, UTF-8 names, deflate compression. Never embeds wall-clock time.

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$script:ZipFixedTimestamp = [DateTime]::new(2026, 8, 20, 0, 0, 0)

function Get-RelativeUnixPath {
    param([string]$Root, [string]$FullPath)
    $rel = $FullPath.Substring($Root.Length).TrimStart('\', '/')
    return $rel.Replace('\', '/')
}

function New-DeterministicZip {
    param(
        [string]$SourceDir,
        [string]$ZipPath,
        [string]$RootEntryName = 'SupplyMindAI'
    )
    $SourceDir = [System.IO.Path]::GetFullPath($SourceDir).TrimEnd('\')
    if (-not (Test-Path -LiteralPath $SourceDir)) {
        throw "source dir missing: $SourceDir"
    }
    if (Test-Path -LiteralPath $ZipPath) {
        Remove-Item -LiteralPath $ZipPath -Force
    }
    $files = Get-ChildItem -LiteralPath $SourceDir -Recurse -File | Sort-Object { $_.FullName }
    $dirs = Get-ChildItem -LiteralPath $SourceDir -Recurse -Directory | Sort-Object { $_.FullName }

    $fs = [System.IO.File]::Open($ZipPath, [System.IO.FileMode]::CreateNew)
    try {
        $archive = New-Object System.IO.Compression.ZipArchive($fs, [System.IO.Compression.ZipArchiveMode]::Create)
        try {
            # root directory entry
            $rootEntry = $archive.CreateEntry("$RootEntryName/")
            $rootEntry.LastWriteTime = $script:ZipFixedTimestamp

            # subdirectories
            foreach ($d in $dirs) {
                $name = Get-RelativeUnixPath $SourceDir $d.FullName
                $entry = $archive.CreateEntry("$RootEntryName/$name/")
                $entry.LastWriteTime = $script:ZipFixedTimestamp
            }

            # files (sorted)
            foreach ($f in $files) {
                $name = Get-RelativeUnixPath $SourceDir $f.FullName
                $entry = $archive.CreateEntry("$RootEntryName/$name")
                $entry.LastWriteTime = $script:ZipFixedTimestamp
                $in = [System.IO.File]::OpenRead($f.FullName)
                try {
                    $out = $entry.Open()
                    try {
                        $in.CopyTo($out)
                    } finally {
                        $out.Dispose()
                    }
                } finally {
                    $in.Dispose()
                }
            }
        } finally {
            $archive.Dispose()
        }
    } finally {
        $fs.Dispose()
    }
}

function Get-ZipEntries {
    param([string]$ZipPath)
    $fs = [System.IO.File]::OpenRead($ZipPath)
    try {
        $archive = New-Object System.IO.Compression.ZipArchive($fs, [System.IO.Compression.ZipArchiveMode]::Read)
        try {
            $entries = @($archive.Entries | ForEach-Object { $_.FullName } | Sort-Object)
            return $entries
        } finally {
            $archive.Dispose()
        }
    } finally {
        $fs.Dispose()
    }
}