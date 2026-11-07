param(
    [string]$OutputDir = (Join-Path $PSScriptRoot "..\test-images\pxhere"),
    [int]$Count = 50,
    [int]$Seed = 20261107
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

$datasetApi = "https://datasets-server.huggingface.co/first-rows"
$allRows = [System.Collections.Generic.List[object]]::new()

# The dataset server's arbitrary-row endpoint can exceed its Parquet scan limit
# on this million-row dataset. Its public first-rows endpoint gives 100 real,
# downloadable examples without fetching multi-gigabyte tar shards.
$uri = "$datasetApi`?dataset=nyuuzyou%2Fpxhere&config=default&split=all"
$response = Invoke-RestMethod -Uri $uri -Headers @{ "User-Agent" = "DropZone-Android-Test-Set/1.0" } -TimeoutSec 90
foreach ($item in $response.rows) {
    $downloadUrl = [string]$item.row.json.download_url
    if ([string]::IsNullOrWhiteSpace($downloadUrl)) { continue }
    $allRows.Add([pscustomobject]@{
        DatasetRow = [int]$item.row_idx
        Id = [string]$item.row.__key__
        DownloadUrl = $downloadUrl
        Description = ([System.Uri]::UnescapeDataString(([System.IO.Path]::GetFileNameWithoutExtension($downloadUrl))) -replace '^photo-', '')
    })
}

$excluded = '(?i)(nude|nudity|bikini|lingerie|underwear|undergarment|sexy|fetish|erotic|breast|pantyhose|stocking)'
$ocrTerms = '(?i)(sign|text|book|newspaper|menu|label|poster|billboard|writing|document|letter|keyboard|screen|store|shop|office|advertising|road-sign|street-sign)'
$safeRows = @($allRows | Where-Object { $_.Description -notmatch $excluded })
if ($safeRows.Count -lt $Count) {
    throw "Only $($safeRows.Count) suitable public preview rows are available, fewer than requested count $Count."
}
$random = [System.Random]::new($Seed)
$shuffled = @($safeRows | Sort-Object { $random.Next() })

$ocrTarget = [Math]::Min(14, [Math]::Floor($Count / 3))
$ocrRows = @($shuffled | Where-Object { $_.Description -match $ocrTerms } | Select-Object -First $ocrTarget)
$ocrIds = @{}; foreach ($row in $ocrRows) { $ocrIds[$row.Id] = $true }
$generalRows = @($shuffled | Where-Object { -not $ocrIds.ContainsKey($_.Id) } | Select-Object -First ($Count - $ocrRows.Count))
$selected = @($ocrRows + $generalRows | Sort-Object { $random.Next() })

$manifest = [System.Collections.Generic.List[object]]::new()
$downloaded = 0
foreach ($row in $selected) {
    $downloaded++
    $safeId = $row.Id -replace '[^A-Za-z0-9_-]', '_'
    $kind = if ($row.Description -match $ocrTerms) { "OCR+Vision" } else { "Vision" }
    $fileName = ("{0:D2}_{1}_{2}.jpg" -f $downloaded, $kind.Replace('+','-'), $safeId)
    $destination = Join-Path $OutputDir $fileName

    $success = $false
    for ($attempt = 1; $attempt -le 3 -and -not $success; $attempt++) {
        try {
            Invoke-WebRequest -Uri $row.DownloadUrl -OutFile $destination -Headers @{ "User-Agent" = "Mozilla/5.0 DropZone-Test" } -TimeoutSec 180
            $file = Get-Item -LiteralPath $destination
            if ($file.Length -lt 10000) { throw "Downloaded file is unexpectedly small" }
            $bytes = [System.IO.File]::ReadAllBytes($destination)
            if ($bytes[0] -ne 0xFF -or $bytes[1] -ne 0xD8) { throw "Downloaded file is not a JPEG" }
            $success = $true
        } catch {
            if (Test-Path -LiteralPath $destination) { Remove-Item -LiteralPath $destination -Force }
            if ($attempt -eq 3) { throw }
            Start-Sleep -Seconds (2 * $attempt)
        }
    }

    $manifest.Add([pscustomobject]@{
        File = $fileName
        TestType = $kind
        DatasetRow = $row.DatasetRow
        PxhereId = $row.Id
        DescriptionFromSourceUrl = $row.Description
        SourceUrl = $row.DownloadUrl
    })
    Write-Host ("[{0}/{1}] {2}" -f $downloaded, $Count, $fileName)
}

$manifest | Export-Csv -LiteralPath (Join-Path $OutputDir "manifest.csv") -NoTypeInformation -Encoding UTF8

$readme = @"
# DropZone PxHere test photos

This folder contains $($manifest.Count) reproducibly sampled images from the
[nyuuzyou/pxhere](https://huggingface.co/datasets/nyuuzyou/pxhere) dataset.

- Sample seed: `$Seed`
- License reported by the dataset card: CC0 1.0
- `manifest.csv` records the dataset row, PxHere ID, source description, and
  original download URL for every image.
- `OCR-Vision` files were selected from the random candidate pool because their
  source descriptions suggest visible text or text-bearing objects. The other
  files are general visual-recognition tests.

These images are test data and are not packaged into the Android APK.
"@
Set-Content -LiteralPath (Join-Path $OutputDir "README.md") -Value $readme -Encoding UTF8

$totalBytes = ($manifest | ForEach-Object { (Get-Item -LiteralPath (Join-Path $OutputDir $_.File)).Length } | Measure-Object -Sum).Sum
Write-Host ("DONE count={0} bytes={1} output={2}" -f $manifest.Count, $totalBytes, (Resolve-Path $OutputDir))
