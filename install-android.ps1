$ErrorActionPreference = 'Stop'

$sdkPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$jdkSearch = Join-Path $env:ProgramFiles 'Eclipse Adoptium\jdk-21*'
$jdkPath = Get-ChildItem -Path $jdkSearch -Directory -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending |
    Select-Object -First 1 -ExpandProperty FullName

if (-not (Test-Path -LiteralPath $sdkPath)) {
    throw "Android SDK not found at $sdkPath. Install Android command-line SDK tools first."
}
if (-not $jdkPath) {
    throw 'Java 21 was not found. Install a JDK 21 build, then run this script again.'
}

$env:ANDROID_HOME = $sdkPath
$env:ANDROID_SDK_ROOT = $sdkPath
$env:JAVA_HOME = $jdkPath
$env:Path = "$jdkPath\bin;$sdkPath\platform-tools;$env:Path"

Push-Location (Join-Path $PSScriptRoot 'android')
try {
    & .\gradlew.bat assembleDebug
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    $apk = Join-Path (Get-Location) 'app\build\outputs\apk\debug\app-debug.apk'
    $apkCopy = Join-Path $PSScriptRoot 'Hearm-android.apk'
    Copy-Item -LiteralPath $apk -Destination $apkCopy -Force

    $adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $adbOutput = @(& $adb devices 2>&1 | ForEach-Object { $_.ToString() })
    $ErrorActionPreference = $previousErrorAction
    $deviceLines = $adbOutput | Where-Object { $_ -match '^\S+\s+device$' }
    if ($deviceLines) {
        & $adb install -r $apkCopy
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        Write-Output 'Hearm installed on the connected phone.'
    }
    else {
        Write-Output 'No Android phone detected. The APK is ready at:'
        Write-Output $apkCopy
        Write-Output 'Connect your phone with USB debugging enabled and run this script again, or transfer the APK to your phone and open it.'
    }
}
finally {
    Pop-Location
}
