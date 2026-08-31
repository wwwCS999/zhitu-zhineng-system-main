param(
    [string]$Key = "",
    [ValidateSet("DeepSeek", "DashScope")]
    [string]$Provider = "DeepSeek",
    [string]$Model = "",
    [string]$FallbackModel = "",
    [string]$FallbackBaseUrl = "",
    [string]$FallbackKey = "",
    [string]$VisionBaseUrl = "",
    [string]$VisionKey = "",
    [string]$VisionModel = "",
    [switch]$DeepSeekForResumeAndLearning,
    [switch]$RestartBackend
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Resolve-Path (Join-Path $scriptDir "..\..")
$envPath = Join-Path $root ".env"
$examplePath = Join-Path $root ".env.example"

function Read-SecretText {
    param([string]$Prompt)
    $secure = Read-Host -AsSecureString $Prompt
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    } finally {
        if ($bstr -ne [IntPtr]::Zero) {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
        }
    }
}

function Set-EnvLine {
    param(
        [string[]]$Lines,
        [string]$Name,
        [string]$Value
    )
    $pattern = "^\s*" + [regex]::Escape($Name) + "\s*="
    $found = $false
    $updated = foreach ($line in $Lines) {
        if ($line -match $pattern) {
            $found = $true
            "$Name=$Value"
        } else {
            $line
        }
    }
    if (-not $found) {
        $updated += "$Name=$Value"
    }
    return ,$updated
}

if (-not (Test-Path -LiteralPath $envPath)) {
    if (Test-Path -LiteralPath $examplePath) {
        Copy-Item -LiteralPath $examplePath -Destination $envPath
    } else {
        New-Item -ItemType File -Path $envPath | Out-Null
    }
}

$lines = Get-Content -LiteralPath $envPath -Encoding UTF8

function Get-EnvValue {
    param(
        [string[]]$Lines,
        [string]$Name
    )
    $line = $Lines | Where-Object { $_ -match ("^\s*" + [regex]::Escape($Name) + "\s*=") } | Select-Object -First 1
    if (-not $line) { return "" }
    return $line -replace ("^\s*" + [regex]::Escape($Name) + "\s*="), ""
}

$providerConfig = @{
    DeepSeek = @{
        BaseUrl = "https://api.deepseek.com/v1"
        DefaultModel = "deepseek-chat"
        DefaultFallbackModel = ""
        KeyName = "DEEPSEEK_API_KEY"
        DisplayName = "DeepSeek"
    }
    DashScope = @{
        BaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1"
        DefaultModel = "qwen-plus"
        DefaultFallbackModel = "qwen-turbo"
        KeyName = "DASHSCOPE_API_KEY"
        DisplayName = "Alibaba Cloud Bailian / DashScope"
    }
}[$Provider]

if ([string]::IsNullOrWhiteSpace($Model)) {
    $Model = $providerConfig.DefaultModel
}

if ([string]::IsNullOrWhiteSpace($Key)) {
    $Key = Get-EnvValue $lines $providerConfig.KeyName
    if ($Provider -eq "DashScope") {
        $deepSeekKey = Get-EnvValue $lines "DEEPSEEK_API_KEY"
        $existingVisionKey = Get-EnvValue $lines "AI_RESUME_VISION_API_KEY"
        if (-not [string]::IsNullOrWhiteSpace($existingVisionKey) -and
            ([string]::IsNullOrWhiteSpace($Key) -or $Key -eq $deepSeekKey)) {
            $Key = $existingVisionKey
        }
    }
    if ([string]::IsNullOrWhiteSpace($Key)) {
        $Key = Get-EnvValue $lines "AI_API_KEY"
    }
    if ([string]::IsNullOrWhiteSpace($Key)) {
        $Key = Read-SecretText "Paste $($providerConfig.DisplayName) API Key"
    }
}

if ([string]::IsNullOrWhiteSpace($Key)) {
    throw "AI API key is empty. Nothing was changed."
}

if ([string]::IsNullOrWhiteSpace($FallbackModel)) {
    $FallbackModel = $providerConfig.DefaultFallbackModel
}
if ([string]::IsNullOrWhiteSpace($FallbackBaseUrl)) {
    $FallbackBaseUrl = $providerConfig.BaseUrl
}
if ([string]::IsNullOrWhiteSpace($FallbackKey)) {
    if ($FallbackBaseUrl -match "dashscope|aliyuncs" -or $FallbackModel -match "^qwen") {
        $FallbackKey = Get-EnvValue $lines "DASHSCOPE_API_KEY"
        $deepSeekKey = Get-EnvValue $lines "DEEPSEEK_API_KEY"
        $existingVisionKey = Get-EnvValue $lines "AI_RESUME_VISION_API_KEY"
        if (-not [string]::IsNullOrWhiteSpace($existingVisionKey) -and
            ([string]::IsNullOrWhiteSpace($FallbackKey) -or $FallbackKey -eq $deepSeekKey)) {
            $FallbackKey = $existingVisionKey
        }
    }
    if ([string]::IsNullOrWhiteSpace($FallbackKey)) {
        $FallbackKey = $Key
    }
}

if (($PSBoundParameters.ContainsKey("VisionBaseUrl") -or $PSBoundParameters.ContainsKey("VisionModel")) -and
    -not $PSBoundParameters.ContainsKey("VisionKey") -and
    [string]::IsNullOrWhiteSpace((Get-EnvValue $lines "AI_RESUME_VISION_API_KEY"))) {
    $VisionKey = Read-SecretText "Paste vision model API Key"
    $PSBoundParameters["VisionKey"] = $VisionKey
}

if ($Provider -eq "DashScope") {
    if ([string]::IsNullOrWhiteSpace($VisionBaseUrl)) {
        $VisionBaseUrl = $providerConfig.BaseUrl
        $PSBoundParameters["VisionBaseUrl"] = $VisionBaseUrl
    }
    if ([string]::IsNullOrWhiteSpace($VisionModel)) {
        $VisionModel = "qwen-vl-plus"
        $PSBoundParameters["VisionModel"] = $VisionModel
    }
    if ([string]::IsNullOrWhiteSpace($VisionKey) -and
        [string]::IsNullOrWhiteSpace((Get-EnvValue $lines "AI_RESUME_VISION_API_KEY"))) {
        $VisionKey = $Key
        $PSBoundParameters["VisionKey"] = $VisionKey
    }
}

$lines = Set-EnvLine $lines "AI_ENABLED" "true"
$lines = Set-EnvLine $lines "AI_BASE_URL" $providerConfig.BaseUrl
$lines = Set-EnvLine $lines "AI_API_KEY" $Key
$lines = Set-EnvLine $lines $providerConfig.KeyName $Key
$lines = Set-EnvLine $lines "AI_MODEL" $Model
if (-not [string]::IsNullOrWhiteSpace($FallbackModel)) {
    $lines = Set-EnvLine $lines "AI_FALLBACK_BASE_URL" $FallbackBaseUrl
    $lines = Set-EnvLine $lines "AI_FALLBACK_API_KEY" $FallbackKey
    $lines = Set-EnvLine $lines "AI_FALLBACK_MODEL" $FallbackModel
} else {
    $lines = Set-EnvLine $lines "AI_FALLBACK_MODEL" ""
}
$lines = Set-EnvLine $lines "AI_RESUME_ENABLED" "true"
$lines = Set-EnvLine $lines "AI_RESUME_MODEL" $Model
if ($PSBoundParameters.ContainsKey("VisionBaseUrl")) {
    $lines = Set-EnvLine $lines "AI_RESUME_VISION_BASE_URL" $VisionBaseUrl
}
if ($PSBoundParameters.ContainsKey("VisionKey")) {
    $lines = Set-EnvLine $lines "AI_RESUME_VISION_API_KEY" $VisionKey
}
if ($PSBoundParameters.ContainsKey("VisionModel")) {
    $lines = Set-EnvLine $lines "AI_RESUME_VISION_MODEL" $VisionModel
}
$lines = Set-EnvLine $lines "AI_RESUME_MAX_TOKENS" "3200"
$lines = Set-EnvLine $lines "AI_RESUME_TEXT_MAX_CHARS" "18000"
$lines = Set-EnvLine $lines "AI_CONNECT_TIMEOUT_MS" "5000"
$lines = Set-EnvLine $lines "AI_READ_TIMEOUT_MS" "180000"
$lines = Set-EnvLine $lines "AI_MAX_TOKENS" "4096"
if ($DeepSeekForResumeAndLearning) {
    $deepSeekKey = Get-EnvValue $lines "DEEPSEEK_API_KEY"
    if ([string]::IsNullOrWhiteSpace($deepSeekKey)) {
        $deepSeekKey = Read-SecretText "Paste DeepSeek API Key for resume parser and learning planner"
    }
    if ([string]::IsNullOrWhiteSpace($deepSeekKey)) {
        throw "DeepSeek API key is empty. Cannot configure resume parser and learning planner."
    }
    $lines = Set-EnvLine $lines "DEEPSEEK_API_KEY" $deepSeekKey
    $lines = Set-EnvLine $lines "AI_RESUME_MODEL" "deepseek-chat"
    $lines = Set-EnvLine $lines "AI_LEARNING_MODEL" "deepseek-chat"
    $lines = Set-EnvLine $lines "AI_RESUME_EXTERNAL_PARSER_MODEL" "deepseek-chat"
    $lines = Set-EnvLine $lines "AI_RESUME_EXTERNAL_PARSER_BASE_URL" "https://api.deepseek.com/v1"
    $lines = Set-EnvLine $lines "AI_RESUME_EXTERNAL_PARSER_API_KEY" $deepSeekKey
}
Set-Content -LiteralPath $envPath -Value $lines -Encoding UTF8

Write-Host "[OK] $($providerConfig.DisplayName) API key configured in .env"
Write-Host "[OK] Model: $Model"
if (-not [string]::IsNullOrWhiteSpace($FallbackModel)) {
    Write-Host "[OK] Fallback model: $FallbackModel"
}
Write-Host "[OK] Resume parser model: $Model"
if ($DeepSeekForResumeAndLearning) {
    Write-Host "[OK] Resume parser model override: deepseek-chat"
    Write-Host "[OK] Learning planner model override: deepseek-chat"
}
$effectiveVisionModel = $VisionModel
if ([string]::IsNullOrWhiteSpace($effectiveVisionModel)) {
    $existingVisionModel = $lines | Where-Object { $_ -match "^\s*AI_RESUME_VISION_MODEL\s*=" } | Select-Object -First 1
    if ($existingVisionModel) {
        $effectiveVisionModel = $existingVisionModel -replace "^\s*AI_RESUME_VISION_MODEL\s*=", ""
    }
}

if ([string]::IsNullOrWhiteSpace($effectiveVisionModel)) {
    Write-Host "[WARN] No vision model configured. DeepSeek hosted API is text-only; image resumes will use local OCR / golden-sample fallback."
    Write-Host "[INFO] To enable online image OCR, rerun with -VisionBaseUrl, -VisionKey and -VisionModel pointing to an OpenAI-compatible vision endpoint."
} else {
    Write-Host "[OK] Resume vision model: $effectiveVisionModel"
}

if ($RestartBackend) {
    Write-Host "[INFO] Restarting backend on port 8080..."
    try {
        $processIds = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue |
            Select-Object -ExpandProperty OwningProcess -Unique
        foreach ($processId in $processIds) {
            if ($processId) {
                Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
            }
        }
    } catch {
        Write-Host "[WARN] Could not stop existing backend process automatically: $($_.Exception.Message)"
    }

    $backendStarter = Join-Path $scriptDir "start-backend.bat"
    $stdout = Join-Path $root "backend.log"
    $stderr = Join-Path $root "backend.err.log"
    Start-Process -WindowStyle Hidden -FilePath "cmd.exe" -ArgumentList "/d /s /c `"$backendStarter`" --no-pause" -WorkingDirectory $root -RedirectStandardOutput $stdout -RedirectStandardError $stderr

    Start-Sleep -Seconds 8
    try {
        $status = Invoke-RestMethod -UseBasicParsing "http://localhost:8080/api/agent/status"
        Write-Host "[OK] Backend started. AI mode: $($status.data.mode); model: $($status.data.model)"
    } catch {
        Write-Host "[WARN] Backend is still starting. Check backend.log if it does not become ready."
    }
}
