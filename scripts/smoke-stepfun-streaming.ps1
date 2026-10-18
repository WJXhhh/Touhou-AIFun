param(
    [string]$SiteConfigPath = (Join-Path $PSScriptRoot '../run/config/touhou_little_maid/sites/tts.json'),
    [string]$Model = 'stepaudio-3-tts',
    [string]$Voice = 'yuanqishaonv',
    [string]$Instruction = '语气温柔，语速自然',
    [string[]]$Sentences = @('第一句话，今天天气真不错。', '第二句话，我们出去玩吧！'),
    [int]$SampleRate = 24000,
    [string]$OutputDirectory = (Join-Path $env:TEMP ('aifun-step-streaming-' + (Get-Date -Format 'yyyyMMdd-HHmmss')))
)

# Protocol-level smoke test for the official StepFun streaming TTS endpoint
# (https://platform.stepfun.com/docs/zh/api-reference/audio/ws-audio).
# It feeds the sentences one at a time, exactly like a streaming chat reply does, and checks that
# PCM audio starts arriving before the reply text has finished. The API key is read from the local
# site JSON and never printed.

$ErrorActionPreference = 'Stop'
$site = (Get-Content -LiteralPath $SiteConfigPath -Raw | ConvertFrom-Json).stepfun
if ([string]::IsNullOrWhiteSpace($site.secret_key)) {
    throw 'The regular stepfun site needs an API key in the specified local configuration.'
}

New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$streamPath = Join-Path $OutputDirectory 'streaming.pcm'
$wavPath = Join-Path $OutputDirectory 'streaming.wav'
$timeout = [TimeSpan]::FromSeconds(90)
$ws = [System.Net.WebSockets.ClientWebSocket]::new()
$ws.Options.SetRequestHeader('Authorization', 'Bearer ' + $site.secret_key)
$cts = [System.Threading.CancellationTokenSource]::new()
$cts.CancelAfter($timeout)
$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
$buffer = New-Object byte[] 1048576
$segment = [System.ArraySegment[byte]]::new($buffer)

function Send-Event([string]$type, [hashtable]$data) {
    $json = [ordered]@{ type = $type; data = $data } | ConvertTo-Json -Depth 10 -Compress
    $bytes = [Text.Encoding]::UTF8.GetBytes($json)
    $ws.SendAsync([System.ArraySegment[byte]]::new($bytes),
        [System.Net.WebSockets.WebSocketMessageType]::Text, $true,
        [System.Threading.CancellationToken]::None).GetAwaiter().GetResult() | Out-Null
}

$pending = [System.Collections.Generic.Queue[string]]::new()
foreach ($sentence in $Sentences) { $pending.Enqueue($sentence) }
$sessionId = $null
$createdAt = -1
$firstAudioAt = -1
$totalBytes = 0
$audioEvents = 0
$sentenceStarts = 0
$finished = $false
$audio = [System.IO.File]::Create($streamPath)
$receiveTask = $null

try {
    $ws.ConnectAsync([Uri]("wss://api.stepfun.com/v1/realtime/audio?model=" + $Model), $cts.Token).GetAwaiter().GetResult() | Out-Null
    Write-Host ("CONNECTED in {0} ms" -f $stopwatch.ElapsedMilliseconds)
    $receiveTask = $ws.ReceiveAsync($segment, $cts.Token)

    while (-not $finished) {
        if (-not $receiveTask.IsCompleted) {
            Start-Sleep -Milliseconds 15
            continue
        }
        $result = $receiveTask.Result
        if ($result.MessageType -eq [System.Net.WebSockets.WebSocketMessageType]::Close) {
            throw ("WebSocket closed unexpectedly: {0} {1}" -f $result.CloseStatus, $result.CloseStatusDescription)
        }
        $message = [System.IO.MemoryStream]::new()
        $message.Write($buffer, 0, $result.Count)
        while (-not $result.EndOfMessage) {
            $receiveTask = $ws.ReceiveAsync($segment, $cts.Token)
            $result = $receiveTask.GetAwaiter().GetResult()
            $message.Write($buffer, 0, $result.Count)
        }
        $receiveTask = $ws.ReceiveAsync($segment, $cts.Token)

        $event = [Text.Encoding]::UTF8.GetString($message.ToArray()) | ConvertFrom-Json
        $at = $stopwatch.ElapsedMilliseconds
        $payload = $event.data
        switch ($event.type) {
            'tts.connection.done' {
                $sessionId = $payload.session_id
                Write-Host ("SESSION {0}" -f $sessionId)
                $create = @{
                    session_id     = $sessionId
                    voice_id       = $Voice
                    response_format = 'pcm'
                    sample_rate    = $SampleRate
                    mode           = 'sentence'
                }
                if (-not [string]::IsNullOrWhiteSpace($Instruction)) { $create.instruction = $Instruction }
                Send-Event 'tts.create' $create
            }
            'tts.response.created' {
                $sessionId = $payload.session_id
                $createdAt = $at
                Write-Host ("CREATED at {0} ms" -f $at)
                if ($pending.Count -gt 0) {
                    Send-Event 'tts.text.delta' @{ session_id = $sessionId; text = $pending.Dequeue() }
                }
            }
            'tts.response.sentence.start' {
                $sentenceStarts++
                Write-Host ("SENTENCE {0} start at {1} ms: {2}" -f $sentenceStarts, $at, $payload.text)
                if ($pending.Count -gt 0) {
                    Send-Event 'tts.text.delta' @{ session_id = $sessionId; text = $pending.Dequeue() }
                }
            }
            'tts.response.audio.delta' {
                $audioEvents++
                if (-not [string]::IsNullOrEmpty($payload.audio)) {
                    $pcm = [Convert]::FromBase64String($payload.audio)
                    if ($pcm.Length -gt 0) {
                        $audio.Write($pcm, 0, $pcm.Length)
                        $totalBytes += $pcm.Length
                        if ($firstAudioAt -lt 0) {
                            $firstAudioAt = $at
                            Write-Host ("FIRST AUDIO at {0} ms" -f $at)
                        }
                    }
                }
            }
            'tts.response.audio.done' {
                Write-Host ("AUDIO DONE at {0} ms" -f $at)
                $finished = $true
            }
            'tts.response.error' {
                throw ("StepFun streaming error: {0}" -f ($event | ConvertTo-Json -Depth 10 -Compress))
            }
            default {
                if ($event.type -notin @('tts.response.audio.done')) {
                    Write-Host ("<< {0} at {1} ms" -f $event.type, $at)
                }
            }
        }
        if ($createdAt -ge 0 -and $pending.Count -eq 0 -and -not $script:doneSent) {
            Send-Event 'tts.text.done' @{ session_id = $sessionId }
            $script:doneSent = $true
        }
    }
} finally {
    $audio.Flush()
    $audio.Close()
    try { $ws.Dispose() } catch { }
}

# Wrap the raw 16-bit little-endian mono PCM in a WAV container for easy listening.
$pcmBytes = [System.IO.File]::ReadAllBytes($streamPath)
$wav = [System.IO.File]::Create($wavPath)
$writer = [System.IO.BinaryWriter]::new($wav)
try {
    $dataLength = $pcmBytes.Length
    $writer.Write([Text.Encoding]::ASCII.GetBytes('RIFF'))
    $writer.Write(36 + $dataLength)
    $writer.Write([Text.Encoding]::ASCII.GetBytes('WAVE'))
    $writer.Write([Text.Encoding]::ASCII.GetBytes('fmt '))
    $writer.Write(16)
    $writer.Write([int16]1)
    $writer.Write([int16]1)
    $writer.Write($SampleRate)
    $writer.Write($SampleRate * 2)
    $writer.Write([int16]2)
    $writer.Write([int16]16)
    $writer.Write([Text.Encoding]::ASCII.GetBytes('data'))
    $writer.Write($dataLength)
    $writer.Write($pcmBytes)
} finally {
    $writer.Flush()
    $writer.Close()
}

if ($totalBytes -le 0) {
    throw 'The streaming session returned no PCM audio.'
}
if ($firstAudioAt -lt 0 -or $firstAudioAt -ge $stopwatch.ElapsedMilliseconds) {
    throw 'The streaming session never produced the first audio chunk.'
}

[pscustomobject]@{
    Model                = $Model
    Voice                = $Voice
    SentencesSent        = $Sentences.Count
    SentenceStarts       = $sentenceStarts
    AudioEvents          = $audioEvents
    FirstAudioMs         = $firstAudioAt
    TotalMs              = $stopwatch.ElapsedMilliseconds
    PcmBytes             = $totalBytes
    AudioSeconds         = [Math]::Round($totalBytes / (2.0 * $SampleRate), 2)
    Wav                  = [IO.Path]::GetFullPath($wavPath)
}
