param(
    [string]$SessionPath = "$PSScriptRoot/../run/config/touhou_aifun/chatgpt/session.json",
    [string]$Model = 'gpt-6.1-sol',
    [ValidateSet('fast', 'priority', 'default')][string]$ServiceTier = 'priority'
)
$ErrorActionPreference = 'Stop'
# Use only the mod's authorization. Never print credentials or raw error responses.
$session = Get-Content -LiteralPath $SessionPath -Raw | ConvertFrom-Json
if ([DateTimeOffset]::UtcNow.ToUnixTimeSeconds() + 30 -ge $session.expires_at) {
    throw 'Mod authorization expired; refresh it in the mod before running this probe.'
}
function Safe-DiagnosticText($value) {
    if ($null -eq $value -or $value -isnot [string]) { return '' }
    foreach ($field in @('access_token', 'refresh_token', 'id_token')) {
        $secret = [string]$session.$field
        if (![string]::IsNullOrEmpty($secret)) { $value = $value.Replace($secret, '[redacted]') }
    }
    $value = $value -replace '(?i)(?:Bearer\s+|sk-)[a-zA-Z0-9._-]+', '[redacted]'
    $value = $value -replace '[\r\n]', ' '
    return $value.Substring(0, [Math]::Min(700, $value.Length))
}
$body = @{
    model = $Model; store = $false; stream = $true; service_tier = $ServiceTier
    input = @(@{ role = 'user'; content = 'Reply with exactly OK.' })
}
try {
    $response = Invoke-WebRequest -UseBasicParsing -Uri 'https://api.openai.com/v1/responses' -Method Post -TimeoutSec 60 `
        -Headers @{ Authorization = 'Bearer ' + $session.access_token; Accept = 'text/event-stream' } `
        -ContentType 'application/json' -Body ($body | ConvertTo-Json -Depth 10 -Compress)
} catch {
    $statusCode = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
    $errorCode = 'transport_error'; $errorParam = ''; $errorMessage = ''
    try {
        $fault = $_.ErrorDetails.Message | ConvertFrom-Json
        $errorCode = $fault.error.code
        $errorParam = Safe-DiagnosticText $fault.error.param
        $errorMessage = Safe-DiagnosticText $fault.error.message
        if (!$errorMessage) { $errorMessage = Safe-DiagnosticText $fault.detail }
    } catch { }
    if ($errorCode -notmatch '^[a-zA-Z0-9_]{1,100}$') { $errorCode = 'request_failed' }
    [ordered]@{ model=$Model; requested_tier=$ServiceTier; http=$statusCode; code=$errorCode
        param=$errorParam; message=$errorMessage } | ConvertTo-Json
    exit 1
}
$wire = if ($response.Content -is [byte[]]) { [Text.Encoding]::UTF8.GetString($response.Content) } else { [string]$response.Content }
$events = @($wire -split '\r?\n' | Where-Object { $_.StartsWith('data:') } | ForEach-Object {
    $value = $_.Substring(5).Trim()
    if ($value -ne '[DONE]') { $value | ConvertFrom-Json }
})
$completed = @($events | Where-Object { $_.type -eq 'response.completed' })
$tier = if ($completed.Count -gt 0) { [string]$completed[-1].response.service_tier } else { '' }
$text = (@($events | Where-Object { $_.type -eq 'response.output_text.delta' } | ForEach-Object { $_.delta }) -join '')
if ([string]::IsNullOrWhiteSpace($text) -and $completed.Count -gt 0) {
    $text = (@($completed[-1].response.output | Where-Object { $_.type -eq 'message' } | ForEach-Object { $_.content }
        | Where-Object { $_.type -eq 'output_text' } | ForEach-Object { $_.text }) -join '')
}
$result = [ordered]@{
    model = $Model; http = [int]$response.StatusCode; requested_tier = $ServiceTier
    actual_tier = $tier; fast_confirmed = ($tier -in @('fast', 'priority'))
    request_id = [string]($response.Headers['x-oai-request-id'] | Select-Object -First 1)
    lifecycle = @($events | Where-Object { $_.type -in @('response.created', 'response.in_progress', 'response.completed') } | ForEach-Object {
        @{ event=$_.type; model=$_.response.model; service_tier=$_.response.service_tier; status=$_.response.status }
    })
    completed = ($completed.Count -gt 0); text = $text
    errors = @($events | Where-Object { $_.type -in @('response.failed', 'error', 'response.incomplete') } | ForEach-Object {
        @{ code = $_.code; response_code = $_.response.error.code; reason = $_.response.incomplete_details.reason }
    })
}
$result | ConvertTo-Json -Depth 6
$tierConfirmed = if ($ServiceTier -eq 'default') { $tier -eq 'default' } else { $result.fast_confirmed }
if (!$result.completed -or [string]::IsNullOrWhiteSpace($result.text) -or !$tierConfirmed) { exit 1 }
