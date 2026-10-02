param(
    [string]$SiteConfigPath = (Join-Path $PSScriptRoot '../run/config/touhou_little_maid/sites/tts.json'),
    [string]$Voice = 'yuanqishaonv',
    [string]$OutputDirectory = (Join-Path $env:TEMP ('aifun-step-singing-' + (Get-Date -Format 'yyyyMMdd-HHmmss')))
)

$ErrorActionPreference = 'Stop'
$site = (Get-Content -LiteralPath $SiteConfigPath -Raw | ConvertFrom-Json).stepfun
if ([string]::IsNullOrWhiteSpace($site.secret_key)) {
    throw 'The regular stepfun site needs an API key in the specified local configuration.'
}

New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$lyrics = "(唱歌)风吹过山岗，星光落在窗。`n明天再出发，把心愿轻轻唱。"
$singingInstruction = '请将整段文本作为歌词，以有明确旋律、节拍和持续音高的人声清唱完整演唱。保持歌唱方式，不要朗读、念白或加入说话式开场结尾。无需伴奏。'

# Use the same original text and official voice for both requests. Never print the key or headers.
foreach ($variant in @('marker-only', 'singing-instruction')) {
    $body = @{
        model = 'stepaudio-3-tts'
        voice = $Voice
        input = $lyrics
        response_format = 'mp3'
        sample_rate = 24000
    }
    if ($variant -eq 'singing-instruction') { $body.instruction = $singingInstruction }
    $outputPath = Join-Path $OutputDirectory ($variant + '.mp3')
    try {
        $response = Invoke-WebRequest -Uri 'https://api.stepfun.com/v1/audio/speech' -Method Post `
            -Headers @{ Authorization = ('Bearer ' + $site.secret_key) } `
            -ContentType 'application/json; charset=utf-8' `
            -Body ([Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Compress))) `
            -TimeoutSec 90 -OutFile $outputPath -PassThru
    } catch {
        throw "StepFun synthesis failed for '$variant'. HTTP status: $($_.Exception.Response.StatusCode)."
    }
    if ($response.Headers['Content-Type'] -notmatch 'audio|octet-stream') {
        throw "Expected audio for '$variant'; received $($response.Headers['Content-Type'])."
    }
    [pscustomobject]@{
        Variant = $variant
        Status = [int]$response.StatusCode
        Bytes = (Get-Item -LiteralPath $outputPath).Length
        Path = [IO.Path]::GetFullPath($outputPath)
    }
}

# HTTP success verifies the protocol only. Listen to both files to assess singing behavior.
