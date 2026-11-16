"""Host-only exporter tests; never invoke adb or touch a device."""
import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SPEC = importlib.util.spec_from_file_location('exporter', Path(__file__).resolve().parents[4] / 'tool/adb_watchface_export.py')
exporter = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(exporter)
ROOT = exporter.ROOTS[0]
BIN_PATH = ROOT + '/123456789012/resource.bin'


def bin_bytes():
    data = bytearray(0xa8)
    data[:4] = b'\x5a\xa5\x34\x12'
    data[0x28:0x34] = b'123456789012'
    return bytes(data)


class FakeAdb:
    def read(self, path, maximum):
        assert path.startswith(ROOT + '/')
        if path == BIN_PATH:
            return bin_bytes()
        if path.endswith('/description.xml'):
            return b'<watch><pkgName>123456789012</pkgName><name>XML face</name></watch>'
        if path.endswith('/preview/preview.png'):
            return b'\x89PNG\r\n\x1a\n'
        raise exporter.ExportError('MISSING', 41)

    def list_paths(self, path, **kwargs):
        raise exporter.ExportError('MISSING', 41)


class ExporterTests(unittest.TestCase):
    def test_only_fixed_roots_and_candidate_path(self):
        for path in ('/sdcard/resource.bin', ROOT + '/../resource.bin', ROOT + '//resource.bin', ROOT + '/a/./resource.bin', ROOT + '/a\n/resource.bin', ROOT + '\\resource.bin', ROOT + '-other/resource.bin'):
            with self.subTest(path=path), self.assertRaises(exporter.ExportError):
                exporter.candidate(path)
        self.assertTrue(exporter.candidate(BIN_PATH))
        self.assertFalse(exporter.candidate(ROOT + '/a/resources/resource.bin'))
        command = exporter.read_command(BIN_PATH, exporter.MAX_BIN)
        self.assertIn('exec head -c 33554433 <&3', command)
        self.assertIn('/proc/$$/fd/3', command)
        self.assertNotIn('su ', command)
        self.assertNotIn('adb push', command)

    def test_bundle_hashes_xml_previews_and_no_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'face.zip'
            result = exporter.export_bundle(FakeAdb(), BIN_PATH, output)
            self.assertTrue(result['ok'])
            with zipfile.ZipFile(output) as archive:
                manifest = json.loads(archive.read('manifest.json'))
                self.assertEqual(manifest['metadata']['name'], 'XML face')
                self.assertEqual(manifest['metadata']['id'], '123456789012')
                self.assertEqual(manifest['metadata']['nameSource'], 'xml')
                for record in manifest['files']:
                    data = archive.read(record['path'])
                    self.assertEqual(record['sha256'], hashlib.sha256(data).hexdigest())
                    self.assertEqual(record['size'], len(data))
            before = output.read_bytes()
            with self.assertRaises(FileExistsError):
                exporter.export_bundle(FakeAdb(), BIN_PATH, output)
            self.assertEqual(output.read_bytes(), before)

    def test_xml_requires_matching_id_and_rejects_dtd(self):
        for data in (b'<watch><pkgName>999999999999</pkgName></watch>', b'<!DOCTYPE watch [<!ENTITY x "secret">]><watch/>'):
            with self.assertRaises(exporter.ExportError):
                exporter.xml_name(data, 'description.xml', '123456789012')

    def test_captures_denied_stderr_even_when_pipeline_returns_zero(self):
        real_popen = subprocess.Popen
        def fake_popen(argv, **kwargs):
            self.assertNotIn('top-secret-serial', ' '.join(argv[3:]))
            return real_popen([sys.executable, '-c', 'import sys; sys.stderr.write("Permission denied token=secret-value")'], **kwargs)
        with patch.object(exporter.subprocess, 'Popen', side_effect=fake_popen):
            with self.assertRaises(exporter.ExportError) as caught:
                exporter.Adb('top-secret-serial').run('true', 100)
        self.assertEqual(caught.exception.code, 'PERMISSION_DENIED')
        self.assertEqual(caught.exception.exit_code, 0)
        self.assertNotIn('secret-value', str(caught.exception))

    def test_output_limit_and_failure_exit_are_observed(self):
        real_popen = subprocess.Popen
        for code, script in [('ADB_OUTPUT_LIMIT', 'print("x" * 1000)'), ('ADB_UNAUTHORIZED', 'import sys; sys.stderr.write("unauthorized token=hidden"); sys.exit(9)')]:
            with self.subTest(code=code), patch.object(exporter.subprocess, 'Popen', side_effect=lambda *a, **kw: real_popen([sys.executable, '-c', script], **kw)):
                with self.assertRaises(exporter.ExportError) as caught:
                    exporter.Adb().run('true', 10)
                self.assertEqual(caught.exception.code, code)
                self.assertNotIn('hidden', str(caught.exception))

    def test_timeout_and_stderr_limit_terminate_host_process(self):
        real_popen = subprocess.Popen
        for code, script, timeout in [
            ('ADB_STDERR_LIMIT', 'import sys; sys.stderr.write("x" * 9000)', 2),
            ('ADB_TIMEOUT', 'import time; time.sleep(5)', 0.05),
        ]:
            with self.subTest(code=code), patch.object(exporter.subprocess, 'Popen', side_effect=lambda *a, **kw: real_popen([sys.executable, '-c', script], **kw)):
                with self.assertRaises(exporter.ExportError) as caught:
                    exporter.Adb().run('true', 10, timeout=timeout)
                self.assertEqual(caught.exception.code, code)

    def test_optional_permission_failure_is_recorded_without_silent_success(self):
        class DeniedOptional(FakeAdb):
            def read(self, path, maximum):
                if path.endswith('description.xml'):
                    raise exporter.ExportError('PERMISSION_DENIED', 42)
                return super().read(path, maximum)
        with tempfile.TemporaryDirectory() as directory:
            result = exporter.export_bundle(DeniedOptional(), BIN_PATH, Path(directory) / 'face.zip')
        self.assertIn('XML_PERMISSION_DENIED', result['warnings'])

    def test_list_reports_permission_failure_and_keeps_other_candidates(self):
        class ListAdb:
            def __init__(self, serial):
                pass
            def list_paths(self, root):
                if root == ROOT:
                    return [ROOT, BIN_PATH]
                raise exporter.ExportError('PERMISSION_DENIED', 42, 15)
        output = io.StringIO()
        with patch.object(exporter, 'Adb', ListAdb), contextlib.redirect_stdout(output):
            code = exporter.main(['--list'])
        result = json.loads(output.getvalue())
        self.assertEqual(code, 1)
        self.assertEqual(result['candidates'], [BIN_PATH])
        self.assertEqual(result['diagnostics'][0]['code'], 'PERMISSION_DENIED')


if __name__ == '__main__':
    unittest.main()
