param(
    [string]$SessionPath = "$PSScriptRoot/../run/config/touhou_aifun/chatgpt/session.json",
    [string]$Model = "gpt-6.1-sol"
)
$ErrorActionPreference = 'Stop'
# Read only the addon's authorization; never print tokens or raw error responses.
$session = Get-Content -LiteralPath $SessionPath -Raw | ConvertFrom-Json
if ([DateTimeOffset]::UtcNow.ToUnixTimeSeconds() + 30 -ge $session.expires_at) {
    throw 'Mod authorization expired; refresh it in the mod before running this probe.'
}
$body = @{
    model = $Model; store = $false; stream = $true
    reasoning = @{ effort = 'low' }
    input = @(@{ role = 'user'; content = 'Use web search to find the recommended Minecraft Forge version for Minecraft 1.20.1 on the official downloads site. Answer in one short sentence and cite the source.' })
    tools = @(
        @{ type = 'web_search' },
        @{ type = 'namespace'; name = 'maid'; description = 'Minecraft maid tools'; tools = @(
            @{ type = 'function'; name = 'current_date_time'; description = 'Get current time'; parameters = @{ type = 'object'; properties = @{} }; strict = $false }
        ) }
    )
}
try {
    $response = Invoke-WebRequest -UseBasicParsing -Uri 'https://api.openai.com/v1/responses' -Method Post -TimeoutSec 90 `
        -Headers @{ Authorization = 'Bearer ' + $session.access_token; Accept = 'text/event-stream' } `
        -ContentType 'application/json' -Body ($body | ConvertTo-Json -Depth 20 -Compress)
} catch {
    $statusCode = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
    $errorCode = 'transport_error'
    try { $errorCode = ($_.ErrorDetails.Message | ConvertFrom-Json).error.code } catch { }
    Write-Output "Model=$Model HTTP=$statusCode Code=$errorCode"
    exit 1
}
$wire = if ($response.Content -is [byte[]]) { [Text.Encoding]::UTF8.GetString($response.Content) } else { [string]$response.Content }
$events = @($wire -split '\r?\n' | Where-Object { $_.StartsWith('data:') } | ForEach-Object {
    $value = $_.Substring(5).Trim()
    if ($value -ne '[DONE]') { $value | ConvertFrom-Json }
})
$completed = @($events | Where-Object { $_.type -eq 'response.completed' })
$items = @($events | Where-Object { $_.type -eq 'response.output_item.done' } | ForEach-Object { $_.item })
$searches = @($items | Where-Object { $_.type -eq 'web_search_call' })
$texts = @($items | Where-Object { $_.type -eq 'message' } | ForEach-Object { $_.content } | Where-Object { $_.type -eq 'output_text' })
$citations = @($texts | ForEach-Object { $_.annotations } | Where-Object { $_.type -eq 'url_citation' })
$result = [ordered]@{
    model = $Model; http = [int]$response.StatusCode
    completed = ($completed.Count -gt 0); searches = $searches.Count
    citations = $citations.Count; text = ($texts.text -join "`n")
    sources = @($citations | ForEach-Object { @{ title = $_.title; url = $_.url } })
    events = @($events | ForEach-Object { $_.type } | Select-Object -Unique)
    errors = @($events | Where-Object { $_.type -in @('response.failed', 'error', 'response.incomplete') } | ForEach-Object {
        @{ code = $_.code; response_code = $_.response.error.code; reason = $_.response.incomplete_details.reason }
    })
}
$result | ConvertTo-Json -Depth 10
if (!$result.completed -or $result.searches -eq 0 -or [string]::IsNullOrWhiteSpace($result.text)) { exit 1 }
