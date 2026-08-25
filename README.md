# RWmiao

LSPosed module for Rusted Warfare.

## Project layout

- `app/src/main/java/.../module` — LSPosed entrypoint and target-process hooks.
- `module/AutoReinforce.java` — automatic reinforcement, PopupWindow and list panel.
- `module/SelectionActions.java` — selection-menu actions and renamed-package payload loader.
- `module/Drawing.java` — attack-range and target-line rendering.
- `module/NoFog.java`, `ViewAll.java`, `EconomicPanel.java`, `FactoryOptimization.java` — one class per game feature.
- `app/src/main/java/.../ui` — injected settings surface; no game simulation access.
- `app/src/main/java/.../config` — shared preference keys and defaults.
- `app/src/main/java/.../app` — standalone module application activity.
- `app/src/main/assets/rwmiao_actions.dex` — target-loader action payload.
- `tools/action-payload` — reproducible source, API stubs, and payload build script.
- `docs/MIGRATION_ROADMAP.md` — feature migration and multiplayer-sync gate.
- `docs/COMPOSE_M3_PLAN.md` — next-stage Material 3 settings architecture.
- `docs/SCRIPTING_API.md` — simplified Lua unit-automation API, data model and native commands.
- `module/script` — all-unit snapshots, native command gateway, script hot reload and unit bindings.
- `factoryOpt` — factory production distribution optimization switch.

The module application ID remains `com.shizuku.rwmiao` to preserve upgrade compatibility.
Target matching is class-contract based rather than tied to one Rusted Warfare application ID.
The hook resolver first probes the original Java namespace and then the selected application's namespace, so a repackaged application ID or namespace can be recognized without depending on game textures.
With `staticScope=false`, enable the module for each variant package in LSPosed; unrelated apps are rejected by the class probes. Selection actions are loaded through a target-loader alias class loader, so the payload resolves the detected game Java namespace instead of assuming the original package prefix.
The version is defined only in `app/build.gradle`.

## Build

```powershell
./gradlew :app:assembleDebug
```

Rebuild the target-loader action payload when its source changes:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./tools/action-payload/build.ps1
```

Verify a release APK, including its LSPosed metadata and target-loader DEX:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./tools/verify-release.ps1
```

