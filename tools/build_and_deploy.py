#!/usr/bin/env python3
"""Clean-build, validate, and transactionally install only Fabric ID 'voxy'."""
import argparse
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
UPSTREAM = "59b62bee821518e06612e1d5c2c58c487bda761d"
REQUIRED_RUNTIME = (
    "org/lwjgl/util/shaderc/Shaderc.class", "org/lwjgl/util/spvc/Spvc.class",
    "org/rocksdb/RocksDB.class", "librocksdbjni-osx-arm64.jnilib",
    "macos/arm64/org/lwjgl/shaderc/libshaderc.dylib",
    "macos/arm64/org/lwjgl/spvc/libspirv-cross.dylib",
    "macos/arm64/org/lwjgl/lmdb/liblwjgl_lmdb.dylib",
    "macos/arm64/org/lwjgl/zstd/liblwjgl_zstd.dylib",
)


def default_mods(root=ROOT):
    destination = os.environ.get("VOXY_MODS_DIR")
    if not destination:
        local = root / ".voxy-deploy-mods"
        destination = local.read_text().strip() if local.is_file() else None
    return Path(destination) if destination else None


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def fabric_id(path):
    try:
        with zipfile.ZipFile(path) as z:
            return json.loads(z.read("fabric.mod.json"))["id"]
    except (zipfile.BadZipFile, KeyError, ValueError):
        return None


def verify_jar(path, label):
    try:
        with zipfile.ZipFile(path) as z:
            if z.testzip() is not None:
                raise ValueError("Corrupt installable JAR")
            metadata = json.loads(z.read("fabric.mod.json"))
            identity = json.loads(z.read("voxy-build.json"))
            if metadata["id"] != "voxy" or metadata["custom"]["buildLabel"] != label or identity["label"] != label:
                raise ValueError("Wrong Fabric ID or build identity")
            if not re.fullmatch(r"[0-9a-f]{64}", identity["sourceDigest"]) or identity["upstreamTarget"] != UPSTREAM:
                raise ValueError("Missing source digest or wrong upstream target")
            header = z.read(metadata["accessWidener"]).splitlines()[0].split()
            if len(header) != 3 or header[0] != b"accessWidener" or header[1] not in (b"v1", b"v2") or header[2] != b"intermediary":
                raise ValueError("JAR is not remapped for installation")
            if any(name.startswith("me/cortex/voxy/tools/") or re.search(r"(?:RegressionTest|PipelineTest|/test/).*\.class$", name) for name in z.namelist()):
                raise ValueError("Test classes leaked into installable JAR")
            native = z.read("natives/macos-arm64/libvoxy_metal.dylib")
            if native[:8] != bytes.fromhex("cffaedfe0c000001"):
                raise ValueError("Metal library is not Mach-O ARM64")
            runtime = set()
            for entry in metadata.get("jars", []):
                with zipfile.ZipFile(io.BytesIO(z.read(entry["file"]))) as nested:
                    if nested.testzip() is not None:
                        raise ValueError("Corrupt nested runtime JAR")
                    runtime.update(nested.namelist())
            missing = set(REQUIRED_RUNTIME) - runtime
            if missing:
                raise ValueError(f"Missing runtime entries: {sorted(missing)}")
            return identity
    except (KeyError, zipfile.BadZipFile) as error:
        raise ValueError(f"Invalid installable Voxy JAR: {error}") from error


def file_hashes(directory, exclude=()):
    return {p.name: sha256(p) for p in directory.iterdir() if p.is_file() and p.name not in exclude}


def deploy_jar(source, mods, backups, label):
    identity = verify_jar(source, label)
    if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9._-]*", label):
        raise ValueError("Build label must be safe for a filename")
    if not mods.is_dir():
        raise ValueError(f"Mods directory does not exist: {mods}")
    old = [p for p in mods.iterdir() if p.is_file() and
           (p.name.endswith(".jar") or p.name.endswith(".jar.disabled")) and fabric_id(p) == "voxy"]
    target = mods / f"voxy-{label}.jar"
    staging = mods / f".voxy-{label}.staging"
    if target.exists() and target not in old:
        raise ValueError("Destination belongs to an unrelated file")
    if staging.exists():
        raise ValueError("Staging filename is already in use")
    unrelated = file_hashes(mods, {p.name for p in old})
    source_sha = sha256(source)
    backup = backups / (datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ-") + label)
    backup.mkdir(parents=True)
    for previous in old:
        shutil.copy2(previous, backup / previous.name)
        if sha256(backup / previous.name) != sha256(previous):
            raise RuntimeError("Backup verification failed before modifying mods")
    removed = []
    installed = False
    try:
        shutil.copy2(source, staging)
        if sha256(staging) != source_sha:
            raise RuntimeError("Staged JAR checksum mismatch")
        for previous in old:
            previous.unlink()
            removed.append(previous)
        os.replace(staging, target)
        installed = True
        if sha256(target) != source_sha:
            raise RuntimeError("Installed JAR checksum mismatch")
        verify_jar(target, label)
        if file_hashes(mods, {target.name}) != unrelated:
            raise RuntimeError("Unrelated mods changed during deployment; Voxy rolled back")
        result = {"source": str(source.resolve()), "sourceSHA256": source_sha,
                  "installed": str(target), "installedSHA256": sha256(target),
                  "sha256": source_sha, "identity": identity,
                  "backup": str(backup), "replaced": [p.name for p in old], "unrelated": unrelated}
        (backup / "deployment.json").write_text(json.dumps(result, indent=2) + "\n")
        return result
    except BaseException:
        if installed:
            target.unlink(missing_ok=True)
        for previous in removed:
            shutil.copy2(backup / previous.name, previous)
        raise
    finally:
        staging.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phase", required=True, help="Unique phase; timestamp is appended automatically")
    parser.add_argument("--mods", type=Path, default=default_mods(),
                        help="Mods directory; defaults to VOXY_MODS_DIR or the ignored .voxy-deploy-mods file")
    parser.add_argument("--check", action="append", default=[], help="Additional Gradle verification task")
    args = parser.parse_args()
    if args.mods is None:
        parser.error("Specify --mods, VOXY_MODS_DIR, or a local .voxy-deploy-mods file")
    if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9._-]*", args.phase):
        parser.error("Invalid phase label")
    label = args.phase + "-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")
    environment = os.environ.copy()
    environment.setdefault("JAVA_HOME", "/opt/homebrew/opt/openjdk@21")
    subprocess.run([str(ROOT / "gradlew"), "clean", "remapJar", *args.check, f"-PbuildLabel={label}"],
                   cwd=ROOT, env=environment, check=True)
    candidates = [p for p in (ROOT / "build/libs").glob("*.jar") if fabric_id(p) == "voxy" and
                  not any(s in p.name for s in ("-sources", "-dev"))]
    if len(candidates) != 1:
        raise RuntimeError(f"Expected exactly one remapped installable JAR, found {candidates}")
    source = candidates[0]
    verify_jar(source, label)
    with zipfile.ZipFile(source) as z:
        if z.read("natives/macos-arm64/libvoxy_metal.dylib") != (ROOT / "src/main/resources/natives/macos-arm64/libvoxy_metal.dylib").read_bytes():
            raise RuntimeError("Packaged Metal library differs from source resource")
    result = deploy_jar(source, args.mods, ROOT.parent / "voxy-test-build-backups", label)
    print(json.dumps({k: v for k, v in result.items() if k != "unrelated"}, indent=2))
    print(f"Verified {len(result['unrelated'])} unrelated files unchanged.")


if __name__ == "__main__":
    main()
