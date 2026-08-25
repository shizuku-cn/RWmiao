param(
    [string]$JavaHome = 'D:\jdk-21\jdk-21.0.12+8',
    [string]$WorkspaceRoot = (Resolve-Path "$PSScriptRoot\..\..\..").Path
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path "$PSScriptRoot\..\..").Path
$BuildRoot = Join-Path $PSScriptRoot '.build'
$StubClasses = Join-Path $BuildRoot 'stub-classes'
$PayloadClasses = Join-Path $BuildRoot 'payload-classes'
$DexOutput = Join-Path $BuildRoot 'dex'
$AndroidJar = (Get-ChildItem (Join-Path $WorkspaceRoot 'local-sdk\platforms') -Recurse -Filter android.jar | Select-Object -First 1).FullName
$D8 = Join-Path $WorkspaceRoot 'local-sdk\build-tools\36.0.0\d8.bat'
$DexDump = Join-Path $WorkspaceRoot 'local-sdk\build-tools\36.0.0\dexdump.exe'
$GameApk = Join-Path $WorkspaceRoot 'work\apk_baseline_20260813\inputs\original.apk'
$AssetOutput = Join-Path $ProjectRoot 'app\src\main\assets\rwmiao_actions.dex'

New-Item -ItemType Directory -Force -Path $StubClasses, $PayloadClasses, $DexOutput | Out-Null
$env:JAVA_HOME = $JavaHome
$Javac = Join-Path $JavaHome 'bin\javac.exe'

& $Javac -encoding UTF-8 -source 8 -target 8 -cp $AndroidJar -d $StubClasses `
    (Get-ChildItem (Join-Path $PSScriptRoot 'stubs') -Recurse -Filter *.java | ForEach-Object FullName)
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

& $Javac -encoding UTF-8 -source 8 -target 8 -cp "$AndroidJar;$StubClasses" -d $PayloadClasses `
    (Get-ChildItem (Join-Path $PSScriptRoot 'src') -Recurse -Filter *.java | ForEach-Object FullName)
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

& $D8 --release --min-api 26 --lib $AndroidJar --classpath $GameApk --output $DexOutput `
    (Get-ChildItem $PayloadClasses -Recurse -Filter *.class | ForEach-Object FullName)
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Copy-Item -LiteralPath (Join-Path $DexOutput 'classes.dex') -Destination $AssetOutput -Force

# Runtime package access includes the defining class loader. The payload is
# loaded by InMemoryDexClassLoader, so it must never directly access the
# package-private gameFramework.f.i.m/n fields. The module resolves those
# anchors reflectively and passes only the insertion index into this payload.
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
    'RWMiaoBridge.maybeAdd:(Ljava/util/ArrayList;IZZZZZZZ)Ljava/util/ArrayList;'
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
