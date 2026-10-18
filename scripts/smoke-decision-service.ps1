# 独立决策服务（JEV / Perplexity Decider）冒烟测试
#
# 验证 mod 的 DecisionProtocol 请求形状能否被真实决策服务接受，并观察：
#   1) 无歧义有限选择：choice 概率分布、confidence、noul 概率
#   2) 有歧义选择：confidence 低于 minConfidence 时应触发回退（决策模型"参与但不乱选"）
#
# 默认从 run/config/touhou-aifun.toml 读取 provider/endpoint/model/key 与 minConfidence，
# 不会打印 Key。可用参数覆盖。
#
# 用法：
#   .\scripts\smoke-decision-service.ps1
#   .\scripts\smoke-decision-service.ps1 -Provider jev
#
# 预期：每个问题输出 choice/noul、probabilities 合计约 1，以及与 minConfidence 的比较。

param(
    [ValidateSet("perplexity", "jev")][string]$Provider = "",
    [string]$Endpoint = "",
    [string]$Model = "",
    [string]$ApiKey = "",
    [double]$MinConfidence = 0.9,
    [int]$TimeoutSec = 30
)

$ErrorActionPreference = "Stop"
$configPath = Join-Path $PSScriptRoot "..\run\config\touhou-aifun.toml"
$toml = if (Test-Path $configPath) { Get-Content $configPath -Raw -Encoding UTF8 } else { "" }

if (-not $Provider) {
    $m = [regex]::Match($toml, '(?ms)\[agentRuntimeV1\.decisionService\][\s\S]*?provider\s*=\s*"([^"]*)"')
    $Provider = if ($m.Success -and $m.Groups[1].Value) { $m.Groups[1].Value } else { "perplexity" }
}
if (-not $MinConfidence -or $MinConfidence -le 0) { $MinConfidence = 0.9 }
$section = [regex]::Escape("[agentRuntimeV1.decisionService.$Provider]")
function Read-ProfileValue([string]$name, [string]$fallback) {
    $m = [regex]::Match($toml, "(?ms)$section[\s\S]*?$name\s*=\s*`"([^`"]*)`"")
    if ($m.Success -and $m.Groups[1].Value) { return $m.Groups[1].Value }
    return $fallback
}
if (-not $Endpoint) { $Endpoint = Read-ProfileValue "endpoint" "" }
if (-not $Model) { $Model = Read-ProfileValue "model" "" }
if (-not $ApiKey) { $ApiKey = Read-ProfileValue "apiKey" "" }
if (-not $Endpoint -or -not $Model) { throw "decision endpoint/model missing; pass -Endpoint and -Model" }
if (-not $ApiKey) { throw "decision api key missing; configure [agentRuntimeV1.decisionService.$Provider].apiKey or pass -ApiKey" }

$headers = @{ "Authorization" = "Bearer $ApiKey"; "Content-Type" = "application/json" }
Write-Host "provider=$Provider endpoint=$Endpoint model=$Model key=configured minConfidence=$MinConfidence"
Write-Host ""

function Invoke-Decision([string]$name, [hashtable]$state, [hashtable]$questions) {
    $body = @{ model = $Model; state = $state; questions = $questions } | ConvertTo-Json -Depth 20
    $timer = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $resp = Invoke-RestMethod -Uri $Endpoint -Method Post -Headers $headers -Body $body -TimeoutSec $TimeoutSec
    } catch {
        Write-Host "FAILED $name : $($_.Exception.Message)"
        return
    }
    $timer.Stop()
    Write-Host "== $name : HTTP ok in $($timer.ElapsedMilliseconds) ms, reported model=$($resp.model), usage=$($resp.usage | ConvertTo-Json -Compress)"
    foreach ($key in $questions.Keys) {
        $q = $questions[$key]
        $a = $resp.answers.$key
        if ($null -eq $a) { Write-Host "  $key : MISSING answer"; continue }
        if ($q.type -eq "choice") {
            $probs = $a.probabilities
            $sum = 0.0; $max = 0.0
            foreach ($p in $probs.PSObject.Properties) { $value = [double]$p.Value; $sum += $value; if ($value -gt $max) { $max = $value } }
            $chosenP = [double]$probs.($a.choice)
            $conf = [double]$a.confidence
            $ok = ($chosenP + 1e-6 -ge $max) -and ([Math]::Abs($sum - 1.0) -le 0.005)
            Write-Host ("  {0} : choice={1} confidence={2:N3} chosen_p={3:N3} sum={4:N4} protocol_ok={5} accepted_by_mod={6}" -f `
                $key, $a.choice, $conf, $chosenP, $sum, $ok, ($conf -ge $MinConfidence))
        }
        else {
            Write-Host ("  {0} : noul={1:N3} (mod: true>={2:N3}, false<={3:N3})" -f $key, [double]$a.noul, 0.5, 0.1)
        }
    }
    Write-Host ""
}

$unambiguousState = @{
    goal = "把最近的箱子里的全部物品取到女仆背包"
    planned_step = "选择要打开的箱子"
    live = @{
        maid_position = @(10, 64, -2)
        observed_targets = @(
            @{ id = "chest_north"; kind = "container"; registry_id = "minecraft:chest"; position = @(10, 64, -5); distance = 3.0; visible = $true },
            @{ id = "chest_west"; kind = "container"; registry_id = "minecraft:chest"; position = @(4, 64, -2); distance = 6.0; visible = $true }
        )
    }
}
$unambiguousQuestions = @{
    source = @{ type = "choice"; instructions = "选择最合适的源箱子：主人要求最近的那个。"; criteria = @{ chest_north = "北侧箱子，距离 3.0"; chest_west = "西侧箱子，距离 6.0" } }
    proceed = @{ type = "noul"; instructions = "当前观察是否足以决定打开哪个箱子？" }
}
Invoke-Decision "unambiguous_choice" $unambiguousState $unambiguousQuestions

$ambiguousState = @{
    goal = "打开主人说的那个箱子"
    planned_step = "选择目标"
    live = @{
        maid_position = @(0, 64, 0)
        observed_targets = @(
            @{ id = "a"; kind = "container"; registry_id = "minecraft:chest"; position = @(2, 64, 0); distance = 2.0; label = "箱子" },
            @{ id = "b"; kind = "container"; registry_id = "minecraft:chest"; position = @(-2, 64, 0); distance = 2.0; label = "箱子" }
        )
    }
}
$ambiguousQuestions = @{
    target = @{ type = "choice"; instructions = "主人说那个箱子，但两个箱子完全一样且距离相同，无法区分。选择最合适的动作。"; criteria = @{ a = "正东方 2 格"; b = "正西方 2 格" } }
    ask_owner = @{ type = "noul"; instructions = "是否应该先请主人澄清目标，而不是自行选择？" }
}
Invoke-Decision "ambiguous_choice" $ambiguousState $ambiguousQuestions

Write-Host "提示：choice 的 confidence 不等于最大概率；低于 minConfidence 时 mod 会回退到后台执行模型。"
