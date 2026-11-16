import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('verify_apk_abi', Path(__file__).resolve().parents[2] / 'tool/verify_apk_abi.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ApkAbiTest(unittest.TestCase):
    def apk(self, directory, libraries):
        path = Path(directory) / 'fixture.apk'
        with zipfile.ZipFile(path, 'w') as archive:
            for name, machine in libraries.items():
                header = bytearray(20)
                header[:6] = b'\x7fELF\x02\x01'
                struct.pack_into('<H', header, 18, machine)
                archive.writestr(name, header)
        return path

    def test_jit_debug_and_aot_release_have_distinct_requirements(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = self.apk(directory, {'lib/arm64-v8a/libflutter.so': 183})
            self.assertEqual(module.verify(apk, {'arm64-v8a'}, debug=True), 1)
            with self.assertRaisesRegex(ValueError, 'Missing Flutter'):
                module.verify(apk, {'arm64-v8a'})
            apk = self.apk(directory, {'lib/arm64-v8a/libflutter.so': 183, 'lib/arm64-v8a/libapp.so': 183})
            self.assertEqual(module.verify(apk, {'arm64-v8a'}), 2)

    def test_extra_abi_is_rejected_even_if_flutter_is_arm64(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = self.apk(directory, {'lib/arm64-v8a/libflutter.so': 183, 'lib/arm64-v8a/libapp.so': 183, 'lib/x86_64/libdependency.so': 62})
            with self.assertRaisesRegex(ValueError, 'outside'):
                module.verify(apk, {'arm64-v8a'})

    def test_wrong_elf_under_correct_directory_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = self.apk(directory, {'lib/arm64-v8a/libflutter.so': 62, 'lib/arm64-v8a/libapp.so': 183})
            with self.assertRaisesRegex(ValueError, 'ELF'):
                module.verify(apk, {'arm64-v8a'})


if __name__ == '__main__':
    unittest.main()
