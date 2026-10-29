param([string]$AndroidSdk = $env:ANDROID_HOME, [string]$JdkHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (!$AndroidSdk) { $AndroidSdk = $env:ANDROID_SDK_ROOT }
if (!$AndroidSdk) { $AndroidSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (!$JdkHome) { throw 'Configure JAVA_HOME com o JDK 17 ou informe -JdkHome.' }
if (!(Test-Path -LiteralPath (Join-Path $AndroidSdk 'platforms\android-35\android.jar'))) {
    throw 'Informe um SDK com a plataforma Android 35 usando ANDROID_HOME ou -AndroidSdk.'
}
$env:JAVA_HOME = $JdkHome
$env:ANDROID_HOME = $AndroidSdk
$sdkPath = $AndroidSdk.Replace('\', '/')
"sdk.dir=$sdkPath" | Set-Content -LiteralPath (Join-Path $projectRoot 'android\local.properties') -Encoding ascii
Push-Location (Join-Path $projectRoot 'android')
try {
    & '.\gradlew.bat' --no-daemon --console=plain testDebugUnitTest assembleDebug lintDebug
    if ($LASTEXITCODE -ne 0) { throw 'Falha nos testes, na compilação ou no lint.' }
    Copy-Item -LiteralPath '.\app\build\outputs\apk\debug\app-debug.apk' -Destination (Join-Path $projectRoot 'ViaAgora.apk') -Force
    Write-Output 'APK gerado: ViaAgora.apk, na raiz do projeto.'
} finally {Pop-Location}
