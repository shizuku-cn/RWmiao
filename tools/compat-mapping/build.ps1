param(
    [string]$BaselineSmali = '',
    [string]$OutputPath = ''
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if ([string]::IsNullOrWhiteSpace($BaselineSmali)) {
    $BaselineSmali = Join-Path (Split-Path -Parent $ProjectRoot) `
        'references\baselines\apk-baseline-20260813\decompiled\apktool\original\smali\com\corrodinggames\rts'
}
if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = Join-Path $ProjectRoot 'app\src\main\assets\rwmiao-symbols.map'
}
$BaselineSmali = (Resolve-Path -LiteralPath $BaselineSmali).Path

function Sort-Ordinal([System.Collections.Generic.List[object]]$Items) {
    $Items.Sort([System.Comparison[object]]{
        param($Left, $Right)
        [StringComparer]::Ordinal.Compare($Left.Key, $Right.Key)
    })
}

$Classes = [System.Collections.Generic.List[object]]::new()
foreach ($File in Get-ChildItem -Recurse -File -LiteralPath $BaselineSmali -Filter '*.smali') {
    $Relative = $File.FullName.Substring($BaselineSmali.Length + 1) `
        -replace '\\', '/' -replace '\.smali$', ''
    $Classes.Add([pscustomobject]@{
        File = $File.FullName
        Relative = $Relative
        Key = ($Relative -replace '\$', '/')
    })
}
Sort-Ordinal $Classes

$IntermediaryClasses = [System.Collections.Generic.List[object]]::new()
foreach ($Class in $Classes) {
    $SimpleName = ($Class.Relative -split '/')[-1]
    $OuterName = ($SimpleName -split '\$')[0]
    if ($OuterName -cmatch '^[a-z]{1,3}$') {
        $IntermediaryClasses.Add($Class)
    }
}
Sort-Ordinal $IntermediaryClasses
if ($IntermediaryClasses.Count -ne 1338) {
    throw "Unexpected 1.15 class inventory: $($IntermediaryClasses.Count), expected 1338"
}

# Community source-remapped builds preserve a small set of semantic names.
# The keys are stable intermediary ordinals, not APK package names.
$SemanticClasses = @{
    299 = 'game/b/Map'
    324 = 'game/Player'
    325 = 'game/EnumColor'
    350 = 'game/units/a/PlayerAction'
    351 = 'game/units/a/UnitAction'
    426 = 'game/units/Unit'
    428 = 'game/units/MovementType'
    706 = 'game/units/Waypoint'
    707 = 'game/units/Actions'
    899 = 'gameFramework/e/FileManager'
    901 = 'gameFramework/e/FileLoader'
    992 = 'gameFramework/i/Modification'
    1001 = 'gameFramework/j/NetworkEngine'
    1061 = 'gameFramework/GameEngine'
}

$IntermediaryByCanonical = @{}
for ($Index = 0; $Index -lt $IntermediaryClasses.Count; $Index++) {
    $Class = $IntermediaryClasses[$Index]
    $Id = $Index + 1
    if ($SemanticClasses.ContainsKey($Id)) {
        $Actual = $SemanticClasses[$Id]
    } else {
        $Slash = $Class.Relative.LastIndexOf('/')
        $Directory = if ($Slash -ge 0) { $Class.Relative.Substring(0, $Slash) } else { '' }
        $SimpleName = if ($Slash -ge 0) { $Class.Relative.Substring($Slash + 1) } else { $Class.Relative }
        $OuterName = ($SimpleName -split '\$')[0]
        if ($SimpleName.Contains('$')) {
            $Actual = "$Directory/$OuterName/class_$Id"
        } else {
            $Actual = "$Directory/class_$Id"
        }
    }
    $IntermediaryByCanonical[$Class.Relative] = $Actual
}

$Lines = [System.Collections.Generic.List[string]]::new()
$Lines.Add('# RWmiao 1.15 semantic symbol profile v1')
$Lines.Add('# C canonical-class actual-intermediary-class')
$Lines.Add('# F canonical-class original-name canonical-type static')
$Lines.Add('# M canonical-class original-name canonical-descriptor static')

foreach ($Class in $Classes) {
    $Canonical = $Class.Relative -replace '/', '.'
    $ActualRelative = if ($IntermediaryByCanonical.ContainsKey($Class.Relative)) {
        $IntermediaryByCanonical[$Class.Relative]
    } else {
        $Class.Relative
    }
    $Lines.Add("C`t$Canonical`t$($ActualRelative -replace '/', '.')")

    $Fields = [System.Collections.Generic.List[object]]::new()
    $Methods = [System.Collections.Generic.List[object]]::new()
    foreach ($Line in Get-Content -LiteralPath $Class.File) {
        if ($Line -match '^\.field\s+(.*?)([^\s:]+):(.+)$') {
            $Flags = $Matches[1]
            $Name = $Matches[2]
            $Type = $Matches[3]
            if ($Name -cmatch '^[A-Za-z]{1,3}$') {
                $Fields.Add([pscustomobject]@{
                    Name = $Name
                    Type = $Type
                    Static = if ($Flags -match '(^|\s)static(\s|$)') { '1' } else { '0' }
                    Key = "$Name`:$Type"
                })
            }
        } elseif ($Line -match '^\.method\s+(.*?)([^\s]+)(\(.*)$') {
            $Flags = $Matches[1]
            $Name = $Matches[2]
            $Descriptor = $Matches[3]
            if ($Name -cmatch '^[A-Za-z]{1,3}$') {
                $Methods.Add([pscustomobject]@{
                    Name = $Name
                    Descriptor = $Descriptor
                    Static = if ($Flags -match '(^|\s)static(\s|$)') { '1' } else { '0' }
                    Key = "$Name$Descriptor"
                })
            }
        }
    }
    Sort-Ordinal $Fields
    Sort-Ordinal $Methods
    foreach ($Field in $Fields) {
        $Lines.Add("F`t$Canonical`t$($Field.Name)`t$($Field.Type)`t$($Field.Static)")
    }
    foreach ($Method in $Methods) {
        $Lines.Add("M`t$Canonical`t$($Method.Name)`t$($Method.Descriptor)`t$($Method.Static)")
    }
}

$OutputDirectory = Split-Path -Parent $OutputPath
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
[IO.File]::WriteAllLines($OutputPath, $Lines, [Text.UTF8Encoding]::new($false))

[pscustomobject]@{
    Output = (Resolve-Path -LiteralPath $OutputPath).Path
    Classes = ($Lines | Where-Object { $_.StartsWith("C`t") }).Count
    Fields = ($Lines | Where-Object { $_.StartsWith("F`t") }).Count
    Methods = ($Lines | Where-Object { $_.StartsWith("M`t") }).Count
    Sha256 = (Get-FileHash -LiteralPath $OutputPath -Algorithm SHA256).Hash
}
