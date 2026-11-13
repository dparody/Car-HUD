param(
    [string]$Toolchain = "$PSScriptRoot/../.tools/llvm-ios",
    [string]$SDK = "$PSScriptRoot/../.tools/iPhoneOS16.5.sdk"
)

$ErrorActionPreference = 'Stop'
$build = Join-Path $PSScriptRoot '../build/ios'
New-Item -ItemType Directory -Force -Path $build | Out-Null
$sources = (Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.m').FullName
$clangArguments = @(
    '-target', 'arm64-apple-ios15.0', '-isysroot', $SDK, '-fuse-ld=lld',
    '-fobjc-arc', '-fblocks', '-Os', '-Wall', '-Wextra', '-Werror', '-Wno-unused-parameter',
    '-Wl,-adhoc_codesign', '-framework', 'UIKit', '-framework', 'Foundation',
    '-framework', 'CoreGraphics', '-framework', 'QuartzCore', '-framework', 'CoreMotion',
    '-framework', 'CoreLocation', '-framework', 'MapKit'
) + $sources + @('-o', "$build/CarHUD")
& "$Toolchain/bin/clang.exe" @clangArguments
if ($LASTEXITCODE) { throw 'iOS compilation failed' }
python "$PSScriptRoot/package.py" "$build/CarHUD" "$PSScriptRoot/../CarHUD-iPhone.ipa"
if ($LASTEXITCODE) { throw 'IPA packaging failed' }
