from __future__ import annotations

import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock
import zipfile


SCRIPT_PATH = Path(__file__).resolve().parents[1] / "plugin.py"
SPEC = importlib.util.spec_from_file_location("inkleaf_plugin_tool", SCRIPT_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Unable to load {SCRIPT_PATH}")
plugin_tool = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = plugin_tool
SPEC.loader.exec_module(plugin_tool)


class PluginToolTest(unittest.TestCase):
    def test_current_plugins_are_discoverable(self) -> None:
        plugins = plugin_tool.discover_plugins()

        self.assertEqual(
            ["copycomic", "inkleaf-fixture", "zaimanhua"],
            [plugin.name for plugin in plugins],
        )
        zaimanhua = next(plugin for plugin in plugins if plugin.name == "zaimanhua")
        self.assertEqual("runtime.js", zaimanhua.runtime.name)

    def test_packages_have_the_host_layout(self) -> None:
        expected_entries = {
            "copycomic": ["main.js", "manifest.json"],
            "inkleaf-fixture": ["assets/icon.txt", "main.js", "manifest.json"],
            "zaimanhua": ["main.js", "manifest.json"],
        }

        for plugin in plugin_tool.discover_plugins():
            with self.subTest(plugin=plugin.name):
                output = plugin_tool.package_plugin(plugin)
                with zipfile.ZipFile(output) as archive:
                    self.assertEqual(expected_entries[plugin.name], sorted(archive.namelist()))

    def test_output_outside_dist_is_rejected(self) -> None:
        plugin = plugin_tool.find_plugin("copycomic")

        with self.assertRaisesRegex(plugin_tool.PluginToolError, "must remain below"):
            plugin_tool.resolve_output_path(plugin, "outside.zip")

    def test_output_inside_staging_is_rejected(self) -> None:
        plugin = plugin_tool.find_plugin("copycomic")

        with self.assertRaisesRegex(plugin_tool.PluginToolError, "staging directory"):
            plugin_tool.resolve_output_path(
                plugin, "plugin-fixtures/dist/build/copycomic/nested.zip"
            )

    def test_output_directory_link_is_rejected(self) -> None:
        plugin = plugin_tool.find_plugin("copycomic")
        with tempfile.TemporaryDirectory(dir=plugin_tool.DIST_ROOT) as temporary:
            target = Path(temporary) / "target"
            target.mkdir()
            link = Path(temporary) / "link"
            try:
                link.symlink_to(target, target_is_directory=True)
            except OSError:
                self.skipTest("Directory symlinks are not available on this host")

            with self.assertRaisesRegex(plugin_tool.PluginToolError, "must not contain a link"):
                plugin_tool.resolve_output_path(plugin, str(link / "plugin.zip"))

    def test_source_parent_link_is_rejected(self) -> None:
        with (
            tempfile.TemporaryDirectory(dir=plugin_tool.FIXTURES_ROOT) as temporary,
            tempfile.TemporaryDirectory(dir=plugin_tool.DIST_ROOT) as external_temporary,
        ):
            plugin_root = Path(temporary)
            (plugin_root / "manifest.json").write_text(
                '{"id":"test.plugin","version":"1.0"}', encoding="utf-8"
            )
            external = Path(external_temporary)
            (external / "main.js").write_text("inkleaf.register({});", encoding="utf-8")
            try:
                (plugin_root / "src").symlink_to(external, target_is_directory=True)
            except OSError:
                self.skipTest("Directory symlinks are not available on this host")

            with self.assertRaisesRegex(plugin_tool.PluginToolError, "must not contain a link"):
                plugin_tool.load_plugin(plugin_root)

    def test_manifest_link_is_rejected(self) -> None:
        with (
            tempfile.TemporaryDirectory(dir=plugin_tool.FIXTURES_ROOT) as temporary,
            tempfile.TemporaryDirectory(dir=plugin_tool.DIST_ROOT) as external_temporary,
        ):
            plugin_root = Path(temporary)
            (plugin_root / "src").mkdir()
            (plugin_root / "src" / "main.js").write_text(
                "inkleaf.register({});", encoding="utf-8"
            )
            target = Path(external_temporary) / "manifest.json"
            target.write_text('{"id":"test.plugin","version":"1.0"}', encoding="utf-8")
            try:
                (plugin_root / "manifest.json").symlink_to(target)
            except OSError:
                self.skipTest("File symlinks are not available on this host")

            with self.assertRaisesRegex(plugin_tool.PluginToolError, "must not contain a link"):
                plugin_tool.load_plugin(plugin_root)

    def test_non_interactive_multiple_devices_requires_serial(self) -> None:
        devices = [
            plugin_tool.AdbDevice("device-a", "device"),
            plugin_tool.AdbDevice("device-b", "device"),
        ]
        with mock.patch.object(plugin_tool, "list_adb_devices", return_value=devices):
            with self.assertRaisesRegex(plugin_tool.PluginToolError, "--serial"):
                plugin_tool.select_device(Path("adb"), None, interactive=False)

    def test_broadcast_result_parser_preserves_install_result(self) -> None:
        output = 'Broadcasting: Intent { ... }\nBroadcast completed: result=-1, data="INSTALLED|id|1.0"\n'
        completed = mock.Mock(returncode=0, stdout=output)
        with mock.patch.object(plugin_tool, "run_adb", return_value=completed):
            result = plugin_tool.install_broadcast(
                Path("adb"), "device", plugin_tool.DEBUG_PACKAGE_ID, []
            )

        self.assertEqual("INSTALLED|id|1.0", result)


if __name__ == "__main__":
    unittest.main()
