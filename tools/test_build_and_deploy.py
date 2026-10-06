import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SPEC = importlib.util.spec_from_file_location("deploy", Path(__file__).with_name("build_and_deploy.py"))
deploy = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(deploy)


def jar(path, mod_id="voxy", omit=None, label="baseline-test"):
    files = {
        "fabric.mod.json": json.dumps({"id": mod_id, "version": "0.2.8-alpha", "custom": {"buildLabel": label},
            "accessWidener": "voxy.accesswidener", "jars": [{"file": "META-INF/jars/runtime.jar"}]}).encode(),
        "voxy.accesswidener": b"accessWidener v2 intermediary\n",
        "voxy-build.json": json.dumps({"label": label, "sourceDigest": "a" * 64, "upstreamTarget": deploy.UPSTREAM}).encode(),
        "natives/macos-arm64/libvoxy_metal.dylib": bytes.fromhex("cffaedfe0c000001") + b"fixture",
    }
    nested = io.BytesIO()
    with zipfile.ZipFile(nested, "w") as z:
        for name in deploy.REQUIRED_RUNTIME:
            if name != omit:
                z.writestr(name, b"test fixture")
    files["META-INF/jars/runtime.jar"] = nested.getvalue()
    with zipfile.ZipFile(path, "w") as z:
        for name, data in files.items():
            if name != omit:
                z.writestr(name, data)


class DeploymentTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.mods = self.root / "mods"
        self.mods.mkdir()
        self.backups = self.root / "backups"
        self.source = self.root / "candidate.jar"
        jar(self.source)

    def tearDown(self):
        self.temp.cleanup()

    def test_deployment_has_no_machine_specific_default(self):
        with patch.dict(deploy.os.environ, {}, clear=True):
            self.assertIsNone(deploy.default_mods(self.root))

    def test_private_local_destination_is_used(self):
        (self.root / ".voxy-deploy-mods").write_text(str(self.mods) + "\n")
        with patch.dict(deploy.os.environ, {}, clear=True):
            self.assertEqual(deploy.default_mods(self.root), self.mods)

    def test_environment_destination_overrides_local_file(self):
        (self.root / ".voxy-deploy-mods").write_text(str(self.root / "other"))
        with patch.dict(deploy.os.environ, {"VOXY_MODS_DIR": str(self.mods)}, clear=True):
            self.assertEqual(deploy.default_mods(self.root), self.mods)

    def test_missing_destination_fails_before_building(self):
        with patch.object(deploy, "default_mods", return_value=None), \
             patch("sys.argv", ["build_and_deploy.py", "--phase", "privacy-test"]), \
             patch("sys.stderr", new_callable=io.StringIO), \
             patch.object(deploy.subprocess, "run") as build:
            with self.assertRaises(SystemExit) as error:
                deploy.main()
            self.assertEqual(error.exception.code, 2)
            build.assert_not_called()

    def test_packaging_and_build_identity(self):
        identity = deploy.verify_jar(self.source, "baseline-test")
        self.assertEqual(identity["sourceDigest"], "a" * 64)
        with self.assertRaises(ValueError):
            deploy.verify_jar(self.source, "wrong-build")

    def test_loom_v1_tab_separated_access_widener(self):
        with zipfile.ZipFile(self.source, "a") as z:
            z.writestr("voxy.accesswidener", b"accessWidener\tv1\tintermediary\n")
        deploy.verify_jar(self.source, "baseline-test")

    def test_each_required_native_and_class_is_mandatory(self):
        for name in (*deploy.REQUIRED_RUNTIME, "natives/macos-arm64/libvoxy_metal.dylib"):
            with self.subTest(name=name):
                jar(self.source, omit=name)
                with self.assertRaises(ValueError):
                    deploy.verify_jar(self.source, "baseline-test")

    def test_dev_jar_and_test_classes_are_rejected(self):
        for name, data in (("voxy.accesswidener", b"accessWidener v2 named\n"),
                           ("me/cortex/voxy/tools/MetalTriangleSmokeTest.class", b"test"),
                           ("me/cortex/voxy/client/core/interop/ResolveRegressionTest.class", b"test")):
            jar(self.source)
            with zipfile.ZipFile(self.source, "a") as z:
                z.writestr(name, data)
            with self.assertRaises(ValueError):
                deploy.verify_jar(self.source, "baseline-test")

    def test_only_fabric_id_voxy_is_replaced_even_if_renamed(self):
        old = self.mods / "renamed.jar"
        disabled = self.mods / "older.jar.disabled"
        unrelated = self.mods / "voxy-looking-but-unrelated.jar"
        jar(old)
        jar(disabled)
        jar(unrelated, "sodium")
        other_sha = deploy.sha256(unrelated)
        result = deploy.deploy_jar(self.source, self.mods, self.backups, "baseline-test")
        self.assertFalse(old.exists())
        self.assertFalse(disabled.exists())
        self.assertEqual(deploy.sha256(unrelated), other_sha)
        self.assertEqual(deploy.sha256(Path(result["installed"])), deploy.sha256(self.source))
        self.assertEqual(len(list(self.backups.rglob("*.jar*"))), 2)

    def test_failed_install_restores_previous_mods(self):
        old = self.mods / "old.jar"
        jar(old)
        before = deploy.sha256(old)
        real_replace = deploy.os.replace
        def fail_install(src, dst):
            if str(src).endswith(".staging"):
                raise OSError("simulated installation failure")
            return real_replace(src, dst)
        with patch.object(deploy.os, "replace", side_effect=fail_install):
            with self.assertRaises(OSError):
                deploy.deploy_jar(self.source, self.mods, self.backups, "baseline-test")
        self.assertEqual(deploy.sha256(old), before)
        self.assertEqual([p.name for p in self.mods.iterdir()], ["old.jar"])

    def test_manifest_records_source_and_installed_hashes(self):
        result = deploy.deploy_jar(self.source, self.mods, self.backups, "baseline-test")
        manifest = json.loads((Path(result["backup"]) / "deployment.json").read_text())
        self.assertEqual(manifest["source"], str(self.source.resolve()))
        self.assertEqual(manifest["sourceSHA256"], deploy.sha256(self.source))
        self.assertEqual(manifest["installedSHA256"], deploy.sha256(Path(result["installed"])))
        self.assertEqual(manifest["sourceSHA256"], manifest["installedSHA256"])

    def test_failure_before_mutation_preserves_old_mod(self):
        old = self.mods / "old.jar"
        jar(old)
        jar(self.source, omit="librocksdbjni-osx-arm64.jnilib")
        with self.assertRaises(ValueError):
            deploy.deploy_jar(self.source, self.mods, self.backups, "baseline-test")
        self.assertTrue(old.exists())

    def test_unrelated_file_change_rolls_back_voxy(self):
        old = self.mods / "old.jar"
        jar(old)
        unrelated = self.mods / "unrelated.jar"
        jar(unrelated, "iris")
        real_replace = deploy.os.replace
        def mutate_unrelated(src, dst):
            result = real_replace(src, dst)
            if str(src).endswith(".staging"):
                unrelated.write_bytes(b"external concurrent change")
            return result
        with patch.object(deploy.os, "replace", side_effect=mutate_unrelated):
            with self.assertRaises(RuntimeError):
                deploy.deploy_jar(self.source, self.mods, self.backups, "baseline-test")
        self.assertTrue(old.exists())
        self.assertEqual(unrelated.read_bytes(), b"external concurrent change")


if __name__ == "__main__":
    unittest.main()
