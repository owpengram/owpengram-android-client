#Requires -Version 5.1
param(
    [ValidateSet('debug','release')]
    [string]$BuildType = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = (Resolve-Path (Join-Path $ScriptDir '..')).Path
$BuildVarsFile = Join-Path $RepoRoot 'TMessagesProj\src\main\java\org\telegram\messenger\BuildVars.java'
$LocalPropertiesFile = Join-Path $RepoRoot 'local.properties'
$GradlewBat = Join-Path $RepoRoot 'gradlew.bat'

$TestApiId = '17349'
$TestApiHash = '344583e45741c457fe1862106095a5eb'

function Get-ApiCredentials {
    $candidates = @(
        (Join-Path $RepoRoot 'api_credentials.local.ps1'),
        (Join-Path (Split-Path $RepoRoot -Parent) 'api_credentials.local.ps1')
    )
    foreach ($file in $candidates) {
        if (-not (Test-Path $file)) { continue }
        . $file
        if ([string]::IsNullOrWhiteSpace($TDESKTOP_API_ID) -or [string]::IsNullOrWhiteSpace($TDESKTOP_API_HASH)) {
            throw "$file must set `$TDESKTOP_API_ID and `$TDESKTOP_API_HASH"
        }
        return @{
            Id = $TDESKTOP_API_ID.Trim()
            Hash = $TDESKTOP_API_HASH.Trim()
            Source = (Split-Path $file -Leaf)
        }
    }

    Write-Host '[WARN] api_credentials.local.ps1 not found - using TEST credentials.' -ForegroundColor Yellow
    Write-Host '       Copy api_credentials.local.ps1.example and add your api_id/api_hash for Telegram login.' -ForegroundColor Yellow
    return @{
        Id = $TestApiId
        Hash = $TestApiHash
        Source = 'test defaults'
    }
}

function Write-Step([string]$Text) { Write-Host "`n>> $Text" -ForegroundColor Yellow }
function Write-Ok([string]$Text)   { Write-Host "[OK] $Text" -ForegroundColor Green }
function Write-Err([string]$Text)   { Write-Host "[X] $Text" -ForegroundColor Red }

function Read-BuildType {
    Write-Host ''
    Write-Host '  Build type:' -ForegroundColor Cyan
    Write-Host '    [1] debug   - fast, unsigned, for testing'
    Write-Host '    [2] release - optimized, requires keystore'
    Write-Host ''
    do {
        $choice = (Read-Host '  Choice [1/2]').Trim()
    } while ($choice -ne '1' -and $choice -ne '2')
    if ($choice -eq '2') { return 'release' }
    return 'debug'
}

function Patch-Api([string]$ApiId, [string]$ApiHash) {
    $content = Get-Content -Path $BuildVarsFile -Raw -Encoding UTF8
    $content = [regex]::Replace($content, 'public static int APP_ID = \d+;', "public static int APP_ID = $ApiId;")
    $content = [regex]::Replace($content, 'public static String APP_HASH = "[^"]*";', "public static String APP_HASH = `"$ApiHash`";")
    [System.IO.File]::WriteAllText($BuildVarsFile, $content, (New-Object System.Text.UTF8Encoding $false))
    Write-Ok "API patched (id $ApiId)"
}

function Get-JavaMajorVersion {
    param([string]$JavaExe)
    if ([string]::IsNullOrWhiteSpace($JavaExe)) { return $null }
    if (-not (Test-Path -LiteralPath $JavaExe)) { return $null }
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = (& $JavaExe -version 2>&1 | ForEach-Object { "$_" }) -join "`n"
    }
    finally {
        $ErrorActionPreference = $prev
    }
    # Java 8 prints "1.8.0_…"; Java 9+ prints "17.0.…" — match the 1.x form first.
    if ($output -match 'version "1\.(\d+)') { return [int]$Matches[1] }
    if ($output -match 'version "(\d+)') { return [int]$Matches[1] }
    return $null
}

function Find-Jdk17Home {
    $candidates = [System.Collections.Generic.List[string]]::new()
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $candidates.Add($env:JAVA_HOME)
    }
    foreach ($path in @(
        'C:\Program Files\Eclipse Adoptium\jdk-17.0.6.10-hotspot',
        'C:\Program Files\Android\Android Studio\jbr'
    )) {
        $candidates.Add($path)
    }
    if (-not [string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
        $candidates.Add((Join-Path $env:LOCALAPPDATA 'Programs\Android\Android Studio\jbr'))
    }
    foreach ($glob in @(
        'C:\Program Files\Eclipse Adoptium\jdk-17*',
        'C:\Program Files\Java\jdk-17*',
        'C:\Program Files\Microsoft\jdk-17*'
    )) {
        foreach ($hit in @(Get-Item -Path $glob -ErrorAction SilentlyContinue)) {
            if ($hit -and $hit.FullName) { $candidates.Add($hit.FullName) }
        }
    }

    foreach ($jdkDir in $candidates) {
        if ([string]::IsNullOrWhiteSpace($jdkDir)) { continue }
        $javaExe = Join-Path $jdkDir 'bin\java.exe'
        $major = Get-JavaMajorVersion -JavaExe $javaExe
        if ($major -ge 17) { return $jdkDir }
    }
    return $null
}

function Ensure-Java17 {
    $currentMajor = $null
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $currentMajor = Get-JavaMajorVersion -JavaExe (Join-Path $env:JAVA_HOME 'bin\java.exe')
    }
    if (-not $currentMajor) {
        $javaCmd = Get-Command java -ErrorAction SilentlyContinue
        if ($javaCmd -and $javaCmd.Source) {
            $currentMajor = Get-JavaMajorVersion -JavaExe $javaCmd.Source
        }
    }
    if ($currentMajor -ge 17) {
        if ($env:JAVA_HOME) {
            Write-Ok "JAVA_HOME: $env:JAVA_HOME (JDK $currentMajor)"
        }
        else {
            Write-Ok "java: JDK $currentMajor"
        }
        return
    }

    $jdkHome = Find-Jdk17Home
    if ([string]::IsNullOrWhiteSpace($jdkHome)) {
        throw "JDK 17+ required (found: $(if ($currentMajor) { $currentMajor } else { 'none' })). Install Temurin/Oracle JDK 17 and set JAVA_HOME."
    }

    $env:JAVA_HOME = $jdkHome
    $env:Path = "$(Join-Path $jdkHome 'bin');$env:Path"
    $switched = Get-JavaMajorVersion -JavaExe (Join-Path $jdkHome 'bin\java.exe')
    Write-Ok "JAVA_HOME switched to JDK $switched : $jdkHome"
}

function Find-AndroidSdk {
    foreach ($path in @($env:OWPNG_ANDROID_SDK, $env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
        if ($path -and (Test-Path $path)) { return (Resolve-Path $path).Path }
    }
    $default = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    if (Test-Path $default) { return (Resolve-Path $default).Path }
    return $null
}

function Ensure-LocalProperties([string]$SdkPath) {
    $escaped = $SdkPath -replace '\\', '\\'
    $line = "sdk.dir=$escaped"
    if (Test-Path $LocalPropertiesFile) {
        $content = Get-Content -Path $LocalPropertiesFile -Raw -Encoding UTF8
        if ($content -match '(?m)^sdk\.dir=.*$') {
            $updated = [regex]::Replace($content, '(?m)^sdk\.dir=.*$', $line)
            [System.IO.File]::WriteAllText($LocalPropertiesFile, $updated, (New-Object System.Text.UTF8Encoding $false))
            return
        }
    }
    [System.IO.File]::WriteAllText($LocalPropertiesFile, "$line`r`n", (New-Object System.Text.UTF8Encoding $false))
}

function Invoke-Gradle([string]$Task, [string]$Label) {
    $logDir = Join-Path $RepoRoot 'logs'
    New-Item -ItemType Directory -Force -Path $logDir | Out-Null
    $logFile = Join-Path $logDir ("{0}-{1}.log" -f $Label, (Get-Date -Format 'yyyyMMdd-HHmmss'))

    $escapedWd = $RepoRoot.Replace('"', '""')
    $escapedLog = $logFile.Replace('"', '""')
    $escapedGradlew = $GradlewBat.Replace('"', '""')
    $full = "cd /d `"$escapedWd`" && call `"$escapedGradlew`" $Task >> `"$escapedLog`" 2>&1"

    Write-Host "gradle: $Task" -ForegroundColor DarkGray
    Write-Host "log: $logFile" -ForegroundColor DarkGray

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = 'cmd.exe'
    $psi.Arguments = "/c `"$full`""
    $psi.WorkingDirectory = $RepoRoot
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $psi
    $null = $process.Start()

    $offset = 0L
    $lastBeat = [datetime]::UtcNow
    while (-not $process.HasExited) {
        if (Test-Path $logFile) {
            $stream = [System.IO.File]::Open($logFile, 'Open', 'Read', 'ReadWrite')
            try {
                $stream.Seek($offset, 'Begin') | Out-Null
                $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::UTF8)
                while (-not $reader.EndOfStream) {
                    $line = $reader.ReadLine()
                    if (-not [string]::IsNullOrWhiteSpace($line)) { Write-Host $line }
                }
                $offset = $stream.Position
            }
            finally { $stream.Dispose() }
        }
        if (([datetime]::UtcNow - $lastBeat).TotalSeconds -ge 30) {
            $lastBeat = [datetime]::UtcNow
            Write-Host "[$(Get-Date -Format 'HH:mm:ss')] $Label - still running..." -ForegroundColor Cyan
        }
        Start-Sleep -Milliseconds 500
    }

    if ($process.ExitCode -ne 0) {
        throw "Gradle failed (exit $($process.ExitCode)). Log: $logFile"
    }
    Write-Ok "Finished: $Label"
}

try {
    Write-Host ''
    Write-Host 'OwpenGram Android build' -ForegroundColor Cyan
    Write-Host "Repo: $RepoRoot"
    Write-Host 'Tip: run build-android.bat from cmd or PowerShell (not Git Bash).' -ForegroundColor DarkGray

    # --- Build type ---
    if ([string]::IsNullOrWhiteSpace($BuildType)) {
        $BuildType = Read-BuildType
    }

    # :TMessagesProj_App is the Google Play flavor (ApplicationLoaderImpl.isStandalone()
    # = false) -- Play Store builds carry restrictions stock Telegram itself doesn't
    # apply to the direct/telegram.org-style download (e.g. some bot chats refuse to
    # open). :TMessagesProj_AppStandalone (ApplicationLoaderImpl.isStandalone() = true)
    # is that unrestricted flavor and shares this repo's same applicationId/keystore, so
    # it installs as an update over an existing build rather than a second app. It has no
    # "release" build type -- its signed/optimized type is named "standalone" instead.
    $GradleModule  = ':TMessagesProj_AppStandalone'
    $ApkBuildTypeDir = if ($BuildType -eq 'release') { 'standalone' } else { 'debug' }
    $BuildTypeCap  = (Get-Culture).TextInfo.ToTitleCase($ApkBuildTypeDir)  # debug->Debug / standalone->Standalone
    $GradleTask    = "${GradleModule}:assembleAfat$BuildTypeCap"
    $ApkPath = Join-Path $RepoRoot "TMessagesProj_AppStandalone\build\outputs\apk\afat\$ApkBuildTypeDir\app.apk"

    Write-Ok "Build type: $BuildType"

    # --- API credentials ---
    $api = Get-ApiCredentials
    Write-Ok "API credentials: $($api.Source) (id $($api.Id))"
    Patch-Api -ApiId $api.Id -ApiHash $api.Hash

    # --- Tools check ---
    Write-Step 'Check tools'
    if (-not (Get-Command git -ErrorAction SilentlyContinue)) { throw 'Missing git in PATH' }
    Ensure-Java17
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw 'Missing java in PATH (JDK 17+)' }
    if (-not (Test-Path $GradlewBat)) { throw "Missing: $GradlewBat" }

    $sdk = Find-AndroidSdk
    if (-not $sdk) {
        throw 'Android SDK not found. Install Android Studio or set ANDROID_HOME.'
    }
    Ensure-LocalProperties -SdkPath $sdk
    Write-Ok "SDK: $sdk"

    Write-Step 'Git submodules'
    Push-Location $RepoRoot
    try {
        $env:GIT_TERMINAL_PROMPT = '0'
        & git -c advice.detachedHead=false submodule update --init --recursive --quiet
        if ($LASTEXITCODE -ne 0) { throw "git submodule failed ($LASTEXITCODE)" }
    }
    finally { Pop-Location }
    Write-Ok 'Submodules OK'

    Write-Step "Gradle $GradleTask"
    Invoke-Gradle -Task $GradleTask -Label "assemble-$BuildType"

    if (Test-Path $ApkPath) {
        Write-Ok "APK: $ApkPath"
    }
    else {
        Write-Host "[WARN] APK not found at: $ApkPath" -ForegroundColor Yellow
        Write-Host "       Check folder: $(Join-Path $RepoRoot 'TMessagesProj_AppStandalone\build\outputs\apk')" -ForegroundColor Yellow
    }
}
catch {
    Write-Err $_.Exception.Message
    exit 1
}
