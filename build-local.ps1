param(
    [string[]]$Tasks = @('testFossDebugUnitTest', 'assembleFossDebug')
)
$ErrorActionPreference = 'Stop'
$workspaceRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$taskTools = Join-Path $workspaceRoot 'work/toolchain'
$taskJdk = Join-Path $taskTools 'jdk/jdk-21.0.13.0.2+1'
$taskGradle = Join-Path $taskTools 'gradle/gradle-9.3.1/bin/gradle.bat'
$taskSdk = Join-Path $taskTools 'android-sdk'
if (-not (Test-Path -LiteralPath $taskGradle) -or -not (Test-Path -LiteralPath (Join-Path $taskJdk 'bin/java.exe'))) {
    throw 'Workspace build tools are missing. Install JDK 21, Gradle 9.3.1 and Android SDK 36, then use gradlew.bat with ANDROID_HOME configured.'
}
$taskTemp = Join-Path $workspaceRoot 'work/t'
New-Item -ItemType Directory -Path $taskTemp -Force | Out-Null
$env:JAVA_HOME = $taskJdk
$env:GRADLE_USER_HOME = Join-Path $taskTools 'gradle-home'
$env:ANDROID_HOME = $taskSdk
$env:ANDROID_SDK_ROOT = $taskSdk
$env:ANDROID_USER_HOME = Join-Path $taskTools 'android-user'
# Windows packaged desktop environments can expose an unusable AF_UNIX temp path.
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$taskTemp -Duser.home=$(Join-Path $taskTools 'java-user')"
& $taskGradle -p $PSScriptRoot @Tasks --console=plain --no-daemon
exit $LASTEXITCODE
