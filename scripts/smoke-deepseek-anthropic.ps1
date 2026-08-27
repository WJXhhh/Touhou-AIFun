# DeepSeek Anthropic 端点冒烟测试脚本
#
# 验证两点：
#   1) Anthropic Messages 协议基本聊天（POST {base}/v1/messages，x-api-key 认证）
#   2) 联网搜索 server tool（tools 里声明 web_search_20250305，
#      响应应包含 server_tool_use / web_search_tool_result 块）
#
# 用法（PowerShell）：
#   .\scripts\smoke-deepseek-anthropic.ps1 -ApiKey "sk-xxxx"
#   .\scripts\smoke-deepseek-anthropic.ps1 -ApiKey "sk-xxxx" -Model "deepseek-v4-pro"
#
# 预期输出：
#   == 1) Basic chat ==     -> 一个 [text] 块
#   == 2) Web search ==     -> [server_tool_use]、[web_search_tool_result (N items)]、
#                              随后基于搜索结果的 [text] 块；stop_reason: end_turn

param(
    [Parameter(Mandatory = $true)][string]$ApiKey,
    [string]$BaseUrl = "https://api.deepseek.com/anthropic",
    [string]$Model = "deepseek-v4-flash"
)

$ErrorActionPreference = "Stop"
$endpoint = "$BaseUrl/v1/messages"
$headers = @{
    "x-api-key"          = $ApiKey
    "content-type"       = "application/json"
    "anthropic-version"  = "2023-06-01"
}

Write-Host "== 1) Basic chat (no tools, non-streaming) =="
$body1 = @{
    model      = $Model
    max_tokens = 256
    system     = "You are a helpful assistant. Reply in Chinese."
    messages   = @(
        @{ role = "user"; content = @(@{ type = "text"; text = "你好，用一句话介绍你自己" }) }
    )
} | ConvertTo-Json -Depth 10
$resp1 = Invoke-RestMethod -Uri $endpoint -Method Post -Headers $headers -Body $body1
$resp1.content | ForEach-Object { Write-Host "[$($_.type)] $($_.text)" }
Write-Host "stop_reason: $($resp1.stop_reason)"
Write-Host ""

Write-Host "== 2) Web search (server tool, web_search_20250305) =="
$body2 = @{
    model      = $Model
    max_tokens = 512
    system     = "You have a web_search tool. Use it when the question needs current information, then answer based on the search results."
    messages   = @(
        @{ role = "user"; content = @(@{ type = "text"; text = "今天是几号？最近一周有什么重要新闻？" }) }
    )
    tools      = @(
        @{ type = "web_search_20250305"; name = "web_search"; max_uses = 3 }
    )
} | ConvertTo-Json -Depth 10
$resp2 = Invoke-RestMethod -Uri $endpoint -Method Post -Headers $headers -Body $body2
$resp2.content | ForEach-Object {
    if ($_.type -eq "web_search_tool_result") {
        Write-Host "[web_search_tool_result] $($_.content.Count) search result item(s)"
    } elseif ($_.type -eq "server_tool_use") {
        Write-Host "[server_tool_use] name=$($_.name) query=$($_.input.query)"
    } else {
        Write-Host "[$($_.type)] $($_.text)"
    }
}
Write-Host "stop_reason: $($resp2.stop_reason)"
Write-Host "usage: input=$($resp2.usage.input_tokens) output=$($resp2.usage.output_tokens)"
Write-Host ""

Write-Host "== 3) Streaming (stream: true, SSE events) =="
$body3 = @{
    model      = $Model
    max_tokens = 256
    stream     = $true
    system     = "You are a helpful assistant. Reply in Chinese."
    messages   = @(
        @{ role = "user"; content = @(@{ type = "text"; text = "用两句话说说天气" }) }
    )
} | ConvertTo-Json -Depth 10
$sseTypes = @{}
$textLen = 0
curl.exe -sN $endpoint -H "x-api-key: $ApiKey" -H "content-type: application/json" -H "anthropic-version: 2023-06-01" -d $body3 | ForEach-Object {
    if ($_ -match '^data:') {
        $payload = $_ -replace '^data: ', ''
        if ($payload -match '"type":"([^"]+)"') {
            $sseTypes[$Matches[1]] = $true
        }
        if ($payload -match '"type":"text_delta","text":"([^"]*)"') {
            $textLen += $Matches[1].Length
        }
    }
}
Write-Host "SSE event types seen: $($sseTypes.Keys -join ', ')"
Write-Host "streamed text chars: $textLen (应 > 0，且应包含 content_block_start/delta/stop、message_start/stop)"
Write-Host ""

Write-Host "== 4) Tool-use round trip (preserve provider thinking like the mod client) =="
$timeTool = @{ name = "get_time"; description = "Get the current time"; input_schema = @{ type = "object"; properties = @{} } }
$toolStartBody = @{
    model      = $Model
    max_tokens = 512
    messages   = @(
        @{ role = "user"; content = @(@{ type = "text"; text = "现在几点了？请立即使用 get_time 工具，不要猜测" }) }
    )
    tools      = @($timeTool)
} | ConvertTo-Json -Depth 10
try {
    $toolStart = Invoke-RestMethod -Uri $endpoint -Method Post -Headers $headers -Body $toolStartBody -TimeoutSec 30
    $thinking = $toolStart.content | Where-Object { $_.type -eq "thinking" } | Select-Object -First 1
    $toolUse = $toolStart.content | Where-Object { $_.type -eq "tool_use" } | Select-Object -First 1
    if ($null -eq $toolUse) { throw "Provider did not return tool_use" }

    # Match AnthropicCompatLLMClient: preserve thinking text and rebuild tool_use. DeepSeek currently
    # accepts this without the optional signature field; the real callback stores the thinking in
    # ReasoningContentCodec across the base-mod agent loop.
    $assistantBlocks = @()
    if ($null -ne $thinking) {
        $assistantBlocks += @{ type = "thinking"; thinking = [string]$thinking.thinking }
    }
    $assistantBlocks += @{
        type = "tool_use"; id = [string]$toolUse.id; name = [string]$toolUse.name; input = $toolUse.input
    }
    $toolResultBody = @{
        model      = $Model
        max_tokens = 512
        messages   = @(
            @{ role = "user"; content = @(@{ type = "text"; text = "现在几点了？请立即使用 get_time 工具，不要猜测" }) },
            @{ role = "assistant"; content = $assistantBlocks },
            @{ role = "user"; content = @(@{ type = "tool_result"; tool_use_id = [string]$toolUse.id; content = "现在是 14:30" }) },
            @{ role = "user"; content = @(@{ type = "text"; text = "" }) }
        )
        tools      = @($timeTool)
    } | ConvertTo-Json -Depth 10
    $resp4 = Invoke-RestMethod -Uri $endpoint -Method Post -Headers $headers -Body $toolResultBody -TimeoutSec 30
    Write-Host "first types: $(($toolStart.content.type) -join ', ')"
    Write-Host "second types: $(($resp4.content.type) -join ', ')"
    Write-Host "stop_reason: $($resp4.stop_reason)"
} catch {
    Write-Host "!! tool round trip failed: $($_.Exception.Message)"
    if ($_.ErrorDetails.Message) { Write-Host "响应体：$($_.ErrorDetails.Message)" }
    throw
}
Write-Host ""
Write-Host "若第 2 步出现 server tool/result，则独立 DeepSeek 搜索 provider 所依赖的原生结果结构正常；第 3、4 步继续验证 Anthropic 基本协议链路。"
