param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidSdk = $env:ANDROID_SDK_ROOT
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path "$PSScriptRoot\..\..").Path
$BuildRoot = Join-Path $PSScriptRoot '.build'
$StubClasses = Join-Path $BuildRoot 'stub-classes'
$PayloadClasses = Join-Path $BuildRoot 'payload-classes'
$DexOutput = Join-Path $BuildRoot 'dex'
$AssetOutput = Join-Path $ProjectRoot 'app\src\main\assets\rwmiao_actions.dex'

if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    throw 'JAVA_HOME is required.'
}
if ([string]::IsNullOrWhiteSpace($AndroidSdk)) {
    $AndroidSdk = $env:ANDROID_HOME
}
if ([string]::IsNullOrWhiteSpace($AndroidSdk)) {
    $LocalProperties = Join-Path $ProjectRoot 'local.properties'
    if (Test-Path -LiteralPath $LocalProperties) {
        $SdkLine = Get-Content -LiteralPath $LocalProperties |
            Where-Object { $_ -match '^sdk\.dir=' } |
            Select-Object -First 1
        if ($SdkLine) {
            $AndroidSdk = ($SdkLine -replace '^sdk\.dir=', '') -replace '\\\\', '\'
        }
    }
}
if ([string]::IsNullOrWhiteSpace($AndroidSdk)) {
    throw 'ANDROID_SDK_ROOT, ANDROID_HOME, or sdk.dir in local.properties is required.'
}

$JavaHome = (Resolve-Path $JavaHome).Path
$AndroidSdk = (Resolve-Path $AndroidSdk).Path
$AndroidJar = Join-Path $AndroidSdk 'platforms\android-35\android.jar'
if (-not (Test-Path -LiteralPath $AndroidJar)) {
    throw "Android 35 platform is missing: $AndroidJar"
}
$BuildTools = Get-ChildItem (Join-Path $AndroidSdk 'build-tools') -Directory |
    Sort-Object { try { [version]$_.Name } catch { [version]'0.0' } } -Descending |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'd8.bat') } |
    Select-Object -First 1
if ($null -eq $BuildTools) {
    throw 'Android build-tools with d8.bat are required.'
}

$env:JAVA_HOME = $JavaHome
$Javac = Join-Path $JavaHome 'bin\javac.exe'
$D8 = Join-Path $BuildTools.FullName 'd8.bat'
$DexDump = Join-Path $BuildTools.FullName 'dexdump.exe'
New-Item -ItemType Directory -Force -Path $StubClasses, $PayloadClasses, $DexOutput | Out-Null

& $Javac -encoding UTF-8 -source 8 -target 8 -cp $AndroidJar -d $StubClasses `
    (Get-ChildItem (Join-Path $PSScriptRoot 'stubs') -Recurse -Filter *.java | ForEach-Object FullName)
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

& $Javac -encoding UTF-8 -source 8 -target 8 -cp "$AndroidJar;$StubClasses" -d $PayloadClasses `
    (Get-ChildItem (Join-Path $PSScriptRoot 'src') -Recurse -Filter *.java | ForEach-Object FullName)
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

& $D8 --release --min-api 26 --lib $AndroidJar --classpath $StubClasses --output $DexOutput `
    (Get-ChildItem $PayloadClasses -Recurse -Filter *.class | ForEach-Object FullName)
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Copy-Item -LiteralPath (Join-Path $DexOutput 'classes.dex') -Destination $AssetOutput -Force

# Validate loader boundaries without requiring a proprietary game APK.
# 无需专有游戏 APK 即可校验类加载边界。
$DumpText = (& $DexDump -d $AssetOutput | Out-String)
$RequiredDexSymbols = @(
    "Class descriptor  : 'Lcom/shizuku/rwmiao/payload/RWMiaoBridge;'",
    "Class descriptor  : 'Lcom/shizuku/rwmiao/payload/RWMiaoDrawAction;'",
    "Class descriptor  : 'Lcom/shizuku/rwmiao/payload/RWMiaoLineAction;'",
    "Class descriptor  : 'Lcom/shizuku/rwmiao/payload/RWMiaoReinforceAction;'",
    "Class descriptor  : 'Lcom/shizuku/rwmiao/payload/RWMiaoSegmentAction;'",
    "Class descriptor  : 'Lcom/shizuku/rwmiao/payload/RWMiaoSmartPathAction;'",
    "Class descriptor  : 'Lcom/shizuku/rwmiao/payload/RWMiaoScriptsAction;'",
    "Class descriptor  : 'Lcom/shizuku/rwmiao/payload/RWMiaoMotherRallyAction;'",
    "Class descriptor  : 'Lcom/shizuku/rwmiao/payload/RWMiaoFreeSelectionAction;'",
    "Class descriptor  : 'Lcom/shizuku/rwmiao/payload/RWMiaoFreeBuildAction;'",
    "Class descriptor  : 'Lcom/shizuku/rwmiao/payload/RWMiaoSelectAllAction;'",
    "Class descriptor  : 'Lcom/shizuku/rwmiao/payload/RWMiaoCombatViewAction;'",
    "Class descriptor  : 'Lcom/shizuku/rwmiao/payload/RWMiaoMutePanelAction;'",
    'RWMiaoBridge.maybeAdd:(Ljava/util/ArrayList;IZZZZZZZZZZZ)Ljava/util/ArrayList;',
    'RWMiaoBridge.maybeAddCombatView:(Ljava/util/ArrayList;IZ)Ljava/util/ArrayList;'
)
foreach ($Symbol in $RequiredDexSymbols) {
    if (-not $DumpText.Contains($Symbol)) {
        throw "Action payload verification failed: missing $Symbol"
    }
}
if ($DumpText -match 'Lcom/corrodinggames/rts/gameFramework/f/i;\.(m|n):') {
    throw 'Action payload verification failed: illegal direct i.m/i.n field reference'
}
if ($DumpText -match 'Lcom/corrodinggames/rts/game/units/a/[tu];\.infoOnly:') {
    throw 'Action payload verification failed: target enum infoOnly field must be resolved dynamically'
}

Get-FileHash -Algorithm SHA256 -LiteralPath $AssetOutput
