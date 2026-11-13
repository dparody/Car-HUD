param(
    [string]$JavaHome = "$PSScriptRoot/.tools/jdk-17.0.16+8",
    [string]$AndroidJar = "$PSScriptRoot/.tools/android-35/android.jar",
    [string]$BuildTools = "$PSScriptRoot/.tools/android-15",
    [string]$KeyStore = "$PSScriptRoot/.tools/carhud-test.jks"
)

$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = $JavaHome
$project = $PSScriptRoot
$output = Join-Path $project 'build'
$classes = Join-Path $output 'classes'
$dex = Join-Path $output 'dex'
New-Item -ItemType Directory -Force -Path $output, $classes, $dex | Out-Null

function Invoke-BuildStep([string]$program, [string[]]$arguments) {
    & $program @arguments
    if ($LASTEXITCODE) { throw "$program failed with exit code $LASTEXITCODE" }
}

$resources = Join-Path $output 'resources.zip'
$base = Join-Path $output 'base.apk'
$aligned = Join-Path $output 'aligned.apk'
$apk = Join-Path $project 'CarHUD.apk'
$source = Get-ChildItem -Path "$project/src" -Recurse -Filter '*.java'
Invoke-BuildStep "$BuildTools/aapt2.exe" @('compile', '--dir', "$project/res", '-o', $resources)
Invoke-BuildStep "$BuildTools/aapt2.exe" @('link', '-I', $AndroidJar, '--manifest',
    "$project/AndroidManifest.xml", '-A', "$project/assets", '-o', $base, $resources)
Invoke-BuildStep "$JavaHome/bin/javac.exe" (@('--release', '8', '-encoding', 'UTF-8',
    '-classpath', $AndroidJar, '-d', $classes) + $source.FullName)
$classFiles = Get-ChildItem -Path $classes -Recurse -Filter '*.class'
Invoke-BuildStep "$BuildTools/d8.bat" (@('--min-api', '26', '--lib', $AndroidJar,
    '--output', $dex) + $classFiles.FullName)
Invoke-BuildStep "$JavaHome/bin/jar.exe" @('uf', $base, '-C', $dex, 'classes.dex')
Invoke-BuildStep "$BuildTools/zipalign.exe" @('-f', '4', $base, $aligned)
if (!(Test-Path -LiteralPath $KeyStore)) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $KeyStore) | Out-Null
    Invoke-BuildStep "$JavaHome/bin/keytool.exe" @('-genkeypair', '-keystore', $KeyStore, '-storetype',
        'JKS', '-storepass', 'android', '-keypass', 'android', '-alias', 'carhud-test', '-keyalg', 'RSA',
        '-keysize', '2048', '-validity', '10000', '-dname', 'CN=Car HUD Test Build')
}
Invoke-BuildStep "$BuildTools/apksigner.bat" @('sign', '--ks', $KeyStore, '--ks-key-alias',
    'carhud-test', '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', $apk, $aligned)
Invoke-BuildStep "$BuildTools/apksigner.bat" @('verify', '--verbose', $apk)
