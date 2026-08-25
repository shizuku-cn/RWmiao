#!/usr/bin/env python3
"""Download the pinned RWmiao Compose dependency closure into local-maven.

This intentionally keeps Maven layout and both POM/module metadata. It is a
small offline-cache bootstrapper, not a replacement for Gradle resolution.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from collections import deque
from dataclasses import dataclass, field
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
DEST = ROOT / "local-maven"
REPOSITORIES = (
    "https://maven.google.com",
    "https://dl.google.com/dl/android/maven2",
    "https://repo1.maven.org/maven2",
)
USER_AGENT = "RWmiao-ui-dependency-fetch/1.0"


@dataclass(frozen=True, order=True)
class Coordinate:
    group: str
    artifact: str
    version: str

    @property
    def relative_dir(self) -> str:
        return f"{self.group.replace('.', '/')}/{self.artifact}/{self.version}"


@dataclass
class Model:
    coordinate: Coordinate
    group: str
    artifact: str
    version: str
    packaging: str = "jar"
    properties: dict[str, str] = field(default_factory=dict)
    managed: dict[tuple[str, str], str] = field(default_factory=dict)
    dependencies: list[Coordinate] = field(default_factory=list)


opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
opener.addheaders = [("User-Agent", USER_AGENT)]
models: dict[Coordinate, Model] = {}
loading: set[Coordinate] = set()
downloaded: dict[str, dict[str, object]] = {}
missing: list[str] = []


def local_path(relative: str) -> Path:
    return DEST / relative


def save(relative: str, data: bytes, url: str) -> None:
    target = local_path(relative)
    target.parent.mkdir(parents=True, exist_ok=True)
    if not target.exists() or target.read_bytes() != data:
        target.write_bytes(data)
    downloaded[relative] = {
        "url": url,
        "sha256": hashlib.sha256(data).hexdigest(),
        "bytes": len(data),
    }


def fetch(relative: str, required: bool = False) -> tuple[bytes | None, str | None]:
    cached = local_path(relative)
    if cached.exists():
        data = cached.read_bytes()
        downloaded[relative] = {
            "url": "local-cache",
            "sha256": hashlib.sha256(data).hexdigest(),
            "bytes": len(data),
        }
        return data, "local-cache"

    for base in REPOSITORIES:
        url = f"{base}/{relative}"
        for attempt in range(2 if required else 1):
            try:
                with opener.open(url, timeout=15 if required else 4) as response:
                    data = response.read()
                save(relative, data, url)
                return data, url
            except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError, OSError):
                continue

    if required:
        missing.append(relative)
        print(f"MISSING {relative}", file=sys.stderr)
    return None, None


def local_name(element: ET.Element) -> str:
    return element.tag.rsplit("}", 1)[-1]


def child(element: ET.Element, name: str) -> ET.Element | None:
    if element is None:
        return None
    for item in list(element):
        if local_name(item) == name:
            return item
    return None


def children(element: ET.Element | None, name: str) -> list[ET.Element]:
    if element is None:
        return []
    return [item for item in list(element) if local_name(item) == name]


def text(element: ET.Element | None) -> str | None:
    if element is None or element.text is None:
        return None
    value = element.text.strip()
    return value or None


def resolve(value: str | None, properties: dict[str, str]) -> str | None:
    if value is None:
        return None
    result = value.strip()
    for _ in range(12):
        changed = False

        def replace(match: re.Match[str]) -> str:
            nonlocal changed
            key = match.group(1)
            replacement = properties.get(key)
            if replacement is None:
                return match.group(0)
            changed = True
            return replacement

        result = re.sub(r"\$\{([^}]+)\}", replace, result)
        if not changed:
            break
    return result


def dependency_coordinate(dep: ET.Element, properties: dict[str, str], managed: dict[tuple[str, str], str]) -> Coordinate | None:
    group = resolve(text(child(dep, "groupId")), properties)
    artifact = resolve(text(child(dep, "artifactId")), properties)
    version = resolve(text(child(dep, "version")), properties)
    if not group or not artifact:
        return None
    if not version:
        version = managed.get((group, artifact))
    if version and len(version) >= 2 and version[0] == "[" and version[-1] == "]":
        version = version[1:-1]
    elif version and version[0] in "[(":
        # Maven ranges are not expected in this pinned graph; use the lower
        # bound so the exact version can still be fetched deterministically.
        version = version[1:].split(",", 1)[0]
    if not version or "${" in version:
        print(f"SKIP unresolved dependency {group}:{artifact}:{version}", file=sys.stderr)
        return None
    return Coordinate(group, artifact, version)


def load_model(coordinate: Coordinate) -> Model | None:
    if coordinate in models:
        return models[coordinate]
    if coordinate in loading:
        return None
    loading.add(coordinate)

    relative_pom = f"{coordinate.relative_dir}/{coordinate.artifact}-{coordinate.version}.pom"
    pom_data, _ = fetch(relative_pom, required=True)
    if pom_data is None:
        loading.discard(coordinate)
        return None

    try:
        root = ET.fromstring(pom_data)
    except ET.ParseError as error:
        print(f"BAD POM {relative_pom}: {error}", file=sys.stderr)
        loading.discard(coordinate)
        return None

    parent_element = child(root, "parent")
    parent_model = None
    parent_group = coordinate.group
    parent_version = coordinate.version
    if parent_element is not None:
        pg = text(child(parent_element, "groupId")) or coordinate.group
        pa = text(child(parent_element, "artifactId"))
        pv = text(child(parent_element, "version")) or coordinate.version
        if pa:
            parent_model = load_model(Coordinate(pg, pa, pv))
            if parent_model:
                parent_group = parent_model.group
                parent_version = parent_model.version

    group = resolve(text(child(root, "groupId")), {}) or parent_group
    version = resolve(text(child(root, "version")), {}) or parent_version
    properties: dict[str, str] = {}
    if parent_model:
        properties.update(parent_model.properties)
    properties.update({
        "project.groupId": group,
        "project.artifactId": coordinate.artifact,
        "project.version": version,
        "pom.groupId": group,
        "pom.artifactId": coordinate.artifact,
        "pom.version": version,
    })
    properties_element = child(root, "properties")
    if properties_element is not None:
        for item in list(properties_element):
            value = text(item)
            if value is not None:
                properties[local_name(item)] = value

    managed: dict[tuple[str, str], str] = {}
    if parent_model:
        managed.update(parent_model.managed)
    dependency_management = child(root, "dependencyManagement")
    for dep in children(child(dependency_management, "dependencies"), "dependency"):
        scope = resolve(text(child(dep, "scope")), properties)
        dep_type = resolve(text(child(dep, "type")), properties) or "jar"
        coordinate_dep = dependency_coordinate(dep, properties, managed)
        if coordinate_dep is None:
            continue
        if scope == "import" and dep_type == "pom":
            imported = load_model(coordinate_dep)
            if imported:
                managed.update(imported.managed)
        else:
            managed[(coordinate_dep.group, coordinate_dep.artifact)] = coordinate_dep.version

    dependencies: list[Coordinate] = []
    dependency_container = child(root, "dependencies")
    for dep in children(dependency_container, "dependency"):
        scope = resolve(text(child(dep, "scope")), properties) or "compile"
        optional = resolve(text(child(dep, "optional")), properties)
        if optional == "true" or scope in {"test", "provided", "system", "import"}:
            continue
        coordinate_dep = dependency_coordinate(dep, properties, managed)
        if coordinate_dep is not None:
            dependencies.append(coordinate_dep)

    packaging = resolve(text(child(root, "packaging")), properties) or "jar"
    model = Model(coordinate, group, coordinate.artifact, version, packaging, properties, managed, dependencies)
    models[coordinate] = model
    loading.discard(coordinate)
    return model


def fetch_artifacts(coordinate: Coordinate, model: Model | None) -> None:
    base = coordinate.relative_dir
    stem = f"{coordinate.artifact}-{coordinate.version}"
    fetch(f"{base}/{stem}.module")
    if model is None or model.packaging == "pom":
        return
    extension = "aar" if model.packaging == "aar" else "jar"
    fetch(f"{base}/{stem}.{extension}")


def module_json(coordinate: Coordinate) -> dict | None:
    """Read Gradle module metadata already cached for a coordinate."""
    relative = f"{coordinate.relative_dir}/{coordinate.artifact}-{coordinate.version}.module"
    path = local_path(relative)
    if not path.exists():
        return None
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    return value if isinstance(value, dict) else None


def android_targets(coordinate: Coordinate) -> list[Coordinate]:
    """Return Android variant coordinates published via available-at metadata."""
    metadata = module_json(coordinate)
    if not metadata:
        return []
    targets: list[Coordinate] = []
    for variant in metadata.get("variants", []):
        if not isinstance(variant, dict):
            continue
        attrs = variant.get("attributes", {})
        if not isinstance(attrs, dict):
            continue
        if attrs.get("org.gradle.jvm.environment") != "android":
            continue
        if attrs.get("org.gradle.usage") not in {"java-api", "java-runtime"}:
            continue
        available = variant.get("available-at")
        if not isinstance(available, dict):
            continue
        group = available.get("group")
        artifact = available.get("module")
        version = available.get("version")
        if group and artifact and version:
            target = Coordinate(str(group), str(artifact), str(version))
            if target not in targets:
                targets.append(target)
    return targets


def module_dependencies(metadata: dict) -> list[Coordinate]:
    """Read dependencies from the Android runtime variant of module metadata."""
    variants = metadata.get("variants", [])
    candidates = []
    for variant in variants:
        if not isinstance(variant, dict):
            continue
        attrs = variant.get("attributes", {})
        if not isinstance(attrs, dict):
            continue
        if attrs.get("org.gradle.usage") != "java-runtime":
            continue
        if attrs.get("org.gradle.libraryelements") not in {"aar", "jar", None}:
            continue
        candidates.append(variant)
    if not candidates:
        return []
    variant = next((item for item in candidates if "android" in str(item.get("name", "")).lower()), candidates[0])
    result: list[Coordinate] = []
    for dependency in variant.get("dependencies", []):
        if not isinstance(dependency, dict):
            continue
        group = dependency.get("group")
        artifact = dependency.get("module")
        version_block = dependency.get("version", {})
        if not group or not artifact or not isinstance(version_block, dict):
            continue
        version = version_block.get("requires") or version_block.get("strictly") or version_block.get("prefers")
        if version and not any(ch in str(version) for ch in "[(),"):
            result.append(Coordinate(str(group), str(artifact), str(version)))
    return result


def fetch_android_variant(coordinate: Coordinate) -> list[Coordinate]:
    """Fetch an Android variant module, its runtime artifact, and dependencies."""
    base = coordinate.relative_dir
    stem = f"{coordinate.artifact}-{coordinate.version}"
    # A regular Maven/JVM dependency may not publish Gradle module metadata;
    # its POM/artifact was already handled by the main resolver.
    data, _ = fetch(f"{base}/{stem}.module")
    if data is None:
        return []
    try:
        metadata = json.loads(data)
    except json.JSONDecodeError:
        return []
    variants = metadata.get("variants", [])
    runtime = []
    for variant in variants:
        if not isinstance(variant, dict):
            continue
        attrs = variant.get("attributes", {})
        if not isinstance(attrs, dict):
            continue
        if attrs.get("org.gradle.usage") == "java-runtime" and attrs.get("org.gradle.libraryelements") in {"aar", "jar", None}:
            runtime.append(variant)
    selected = next((item for item in runtime if "android" in str(item.get("name", "")).lower()), runtime[0] if runtime else None)
    if selected:
        for item in selected.get("files", []):
            if isinstance(item, dict) and item.get("name"):
                # Gradle metadata's `name` is the logical variant name; the
                # Maven repository path is stored in `url`.
                fetch(f"{base}/{item.get('url') or item['name']}", required=True)
    return module_dependencies(metadata)


def fetch_android_runtime_variants(processed: set[Coordinate]) -> set[Coordinate]:
    """Resolve and cache the Android runtime side of KMP-published libraries."""
    pending = deque(processed)
    seen: set[Coordinate] = set()
    while pending:
        coordinate = pending.popleft()
        if coordinate in seen:
            continue
        seen.add(coordinate)
        for target in android_targets(coordinate):
            if target in seen:
                continue
            dependencies = fetch_android_variant(target)
            processed.add(target)
            pending.append(target)
            for dependency in dependencies:
                pending.append(dependency)
    return seen


def main() -> int:
    roots = [
        Coordinate("androidx.compose", "compose-bom", "2025.08.00"),
        Coordinate("androidx.compose.material3", "material3", "1.3.2"),
        Coordinate("org.jetbrains.kotlin.android", "org.jetbrains.kotlin.android.gradle.plugin", "2.0.21"),
        Coordinate("org.jetbrains.kotlin.plugin.compose", "org.jetbrains.kotlin.plugin.compose.gradle.plugin", "2.0.21"),
        Coordinate("org.jetbrains.kotlin", "kotlin-gradle-plugin", "2.0.21"),
        Coordinate("org.jetbrains.kotlin", "kotlin-stdlib", "2.0.21"),
    ]

    DEST.mkdir(parents=True, exist_ok=True)
    queue = deque(roots)
    queued = set(roots)
    processed: set[Coordinate] = set()
    while queue:
        coordinate = queue.popleft()
        if coordinate in processed:
            continue
        model = load_model(coordinate)
        fetch_artifacts(coordinate, model)
        processed.add(coordinate)
        if model:
            for dependency in model.dependencies:
                if dependency not in queued:
                    queued.add(dependency)
                    queue.append(dependency)

    fetch_android_runtime_variants(processed)

    manifest = {
        "repositories": list(REPOSITORIES),
        "roots": [f"{c.group}:{c.artifact}:{c.version}" for c in roots],
        "coordinates": [f"{c.group}:{c.artifact}:{c.version}" for c in sorted(processed)],
        "files": downloaded,
        "missing": sorted(set(missing)),
    }
    (DEST / "download-manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(f"Resolved coordinates: {len(processed)}")
    print(f"Downloaded files: {len(downloaded)}")
    print(f"Missing optional/unsupported files: {len(set(missing))}")
    print(f"Destination: {DEST}")
    return 0 if not missing else 2


if __name__ == "__main__":
    raise SystemExit(main())
