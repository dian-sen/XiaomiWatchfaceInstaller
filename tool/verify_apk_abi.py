"""Verify actual APK ZIP entries and ELF headers rather than aapt's preferred ABI."""
import argparse
from pathlib import Path
import struct
import zipfile


def verify(apk: Path, expected: set[str], *, debug: bool = False) -> int:
    architectures = {'arm64-v8a': (2, 183), 'armeabi-v7a': (1, 40), 'x86_64': (2, 62)}
    if not expected or not expected <= architectures.keys():
        raise ValueError('Unsupported expected architecture')
    with zipfile.ZipFile(apk) as archive:
        entries = archive.namelist()
        if len(entries) != len(set(entries)) or archive.testzip() is not None:
            raise ValueError('APK ZIP integrity failed')
        libraries = [n for n in entries if n.startswith('lib/') and n.endswith('.so')]
        if {n.split('/')[1] for n in libraries} != expected:
            raise ValueError('APK contains native libraries outside the requested ABIs')
        for abi in expected:
            required = ('libflutter.so',) if debug else ('libapp.so', 'libflutter.so')
            for name in required:
                if f'lib/{abi}/{name}' not in libraries:
                    raise ValueError('Missing Flutter engine or application for a requested ABI')
        for name in libraries:
            with archive.open(name) as stream:
                header = stream.read(20)
            elf_class, machine = architectures[name.split('/')[1]]
            if len(header) != 20 or header[:4] != b'\x7fELF' or header[4] != elf_class or header[5] != 1 or struct.unpack_from('<H', header, 18)[0] != machine:
                raise ValueError('Native library ELF architecture mismatch')
        return len(libraries)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--abi', action='append', required=True)
    parser.add_argument('--debug', action='store_true', help='JIT debug APK does not contain libapp.so')
    args = parser.parse_args()
    print(f'Actual APK native ABI and ZIP CRC: PASS ({verify(args.apk, set(args.abi), debug=args.debug)} libraries)')
