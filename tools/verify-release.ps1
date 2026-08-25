param(
    [string]$ApkPath = (Join-Path $PSScriptRoot '..\app\build\outputs\apk\release\app-release.apk'),
    [string]$JavaHome = 'D:\jdk-21\jdk-21.0.12+8',
    [string]$WorkspaceRoot = (Resolve-Path "$PSScriptRoot\..\..").Path
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path "$PSScriptRoot\..").Path
$ApkPath = (Resolve-Path $ApkPath).Path
$BuildTools = Join-Path $WorkspaceRoot 'local-sdk\build-tools\36.0.0'
$Aapt = Join-Path $BuildTools 'aapt.exe'
$ApkSigner = Join-Path $BuildTools 'apksigner.bat'
$ZipAlign = Join-Path $BuildTools 'zipalign.exe'
$DexDump = Join-Path $BuildTools 'dexdump.exe'
$env:JAVA_HOME = $JavaHome

$Gradle = Get-Content -Raw -Encoding UTF8 (Join-Path $ProjectRoot 'app\build.gradle')
$VersionMatch = [regex]::Match($Gradle, "versionName\s+'([^']+)'")
if (-not $VersionMatch.Success) {
    throw 'Unable to determine versionName from app/build.gradle'
}
$ExpectedVersion = $VersionMatch.Groups[1].Value

$Badging = (& $Aapt dump badging $ApkPath | Out-String)
if ($LASTEXITCODE -ne 0) { throw 'aapt verification failed' }
if (-not $Badging.Contains("versionName='$ExpectedVersion'")) {
    throw "APK version does not match app/build.gradle ($ExpectedVersion)"
}
if (-not $Badging.Contains("application-label:'RWmiao'")) {
    throw 'APK application label is not RWmiao'
}

$Signature = (& $ApkSigner verify --verbose --print-certs $ApkPath | Out-String)
if ($LASTEXITCODE -ne 0 -or -not $Signature.Contains('Verified using v2 scheme (APK Signature Scheme v2): true')) {
    throw 'APK v2 signature verification failed'
}

& $ZipAlign -c 4 $ApkPath
if ($LASTEXITCODE -ne 0) { throw 'zipalign verification failed' }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$Zip = [System.IO.Compression.ZipFile]::OpenRead($ApkPath)
try {
    function Read-ZipText([string]$Name) {
        $Entry = $Zip.GetEntry($Name)
        if ($null -eq $Entry) { throw "Missing APK entry: $Name" }
        $Reader = [System.IO.StreamReader]::new($Entry.Open(), [System.Text.Encoding]::UTF8)
        try { return $Reader.ReadToEnd().Trim() } finally { $Reader.Dispose() }
    }

    if ((Read-ZipText 'META-INF/xposed/java_init.list') -ne 'com.shizuku.rwmiao.module.RWmiaoModule') {
        throw 'Unexpected LSPosed entrypoint'
    }
    $ModuleProp = Read-ZipText 'META-INF/xposed/module.prop'
    if (-not $ModuleProp.Contains('staticScope=false')) {
        throw 'Module must allow user-selected variant package scopes'
    }
    if ((Read-ZipText 'META-INF/xposed/scope.list') -ne 'com.corrodinggames.rts') {
        throw 'Unexpected LSPosed scope'
    }

    $DexEntry = $Zip.GetEntry('assets/rwmiao_actions.dex')
    if ($null -eq $DexEntry) { throw 'Missing action payload' }
    $TemporaryDex = Join-Path ([System.IO.Path]::GetTempPath()) ("rwmiao-actions-{0}.dex" -f [guid]::NewGuid())
    $Input = $DexEntry.Open()
    $Output = [System.IO.File]::Create($TemporaryDex)
    try { $Input.CopyTo($Output) } finally { $Input.Dispose(); $Output.Dispose() }
} finally {
    $Zip.Dispose()
}

try {
    $DumpText = (& $DexDump -d $TemporaryDex | Out-String)
    foreach ($Symbol in @(
        "Class descriptor  : 'Lcom/shizuku/rwmiao/payload/RWMiaoBridge;'",
        "Class descriptor  : 'Lcom/shizuku/rwmiao/payload/RWMiaoDrawAction;'",
        "Class descriptor  : 'Lcom/shizuku/rwmiao/payload/RWMiaoLineAction;'",
        "Class descriptor  : 'Lcom/shizuku/rwmiao/payload/RWMiaoReinforceAction;'",
        "Class descriptor  : 'Lcom/shizuku/rwmiao/payload/RWMiaoSegmentAction;'",
        "Class descriptor  : 'Lcom/shizuku/rwmiao/payload/RWMiaoSmartPathAction;'",
        "Class descriptor  : 'Lcom/shizuku/rwmiao/payload/RWMiaoScriptsAction;'",
        "Class descriptor  : 'Lcom/shizuku/rwmiao/payload/RWMiaoMotherRallyAction;'",
        'RWMiaoBridge.maybeAdd:(Ljava/util/ArrayList;IZZZZZZZ)Ljava/util/ArrayList;'
    )) {
        if (-not $DumpText.Contains($Symbol)) { throw "Missing action DEX symbol: $Symbol" }
    }
    if ($DumpText -match 'Lcom/corrodinggames/rts/gameFramework/f/i;\.(m|n):') {
        throw 'Action DEX contains an illegal direct i.m/i.n reference'
    }
    if ($DumpText -match 'Lcom/corrodinggames/rts/game/units/a/[tu];\.infoOnly:') {
        throw 'Action DEX contains a direct target enum infoOnly field reference'
    }
} finally {
    Remove-Item -LiteralPath $TemporaryDex -Force -ErrorAction SilentlyContinue
}

$Hash = Get-FileHash -Algorithm SHA256 -LiteralPath $ApkPath
[pscustomobject]@{
    Apk = $ApkPath
    Version = $ExpectedVersion
    Sha256 = $Hash.Hash
    SignatureV2 = $true
    ZipAligned = $true
    XposedEntrypoint = 'com.shizuku.rwmiao.module.RWmiaoModule'
    ActionPayload = 'verified'
}
