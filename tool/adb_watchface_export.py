#!/usr/bin/env python3
"""Computer-side, read-only Xiaomi cache exporter (Python 3 + platform-tools).

  python3 tool/adb_watchface_export.py [--serial SERIAL] --list
  python3 tool/adb_watchface_export.py [--serial SERIAL] --path /.../resource.bin --output face.zip

ADB must already be authorized. No pairing, root escalation, device writes, or
permission bypass is performed. Some Android/OEM releases deny shell access to
Android/data; a permission error is a real failure, not an empty cache.
"""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import sys
import threading
import time
import zipfile
import xml.etree.ElementTree as ET

ROOTS = (
    '/storage/emulated/0/Android/data/com.mi.health/files/WatchFace',
    '/storage/emulated/0/Android/data/com.xiaomi.hm.health/files/watch_skin_local',
)
MIB = 1024 * 1024
MAX_BIN = 32 * MIB
MAX_XML = 512 * 1024
MAX_IMAGE = 8 * MIB
MAX_IMAGES = 16 * MIB
MAX_TOTAL = 40 * MIB
XML_PATHS = ('description.xml', 'resources/manifest.xml')


class ExportError(Exception):
    def __init__(self, code, exit_code=None, stderr_bytes=0):
        super().__init__(code)
        self.code = code
        self.exit_code = exit_code
        self.stderr_bytes = stderr_bytes


def source_root(path):
    if (len(path.encode('utf-8')) > 4096 or '\\' in path or
            any(ord(c) < 32 or ord(c) == 127 for c in path) or
            any(p in ('', '.', '..') for p in path.split('/')[1:])):
        raise ExportError('UNSAFE_SOURCE_PATH')
    for root in ROOTS:
        if path.startswith(root + '/'):
            return root
    raise ExportError('SOURCE_OUTSIDE_OFFICIAL_ROOTS')


def candidate(path):
    source_root(path)
    parts = path.split('/')
    return (parts[-1].lower() == 'resource.bin' and
            not any(p.lower() in ('resources', '_lua') for p in parts[:-1]))


# /storage/emulated/0 itself is an Android alias. Below it, reject every link,
# validate the canonical path, and read through one checked, opened descriptor.
GUARD = r'''
export LC_ALL=C
missing() {
  detail=$(stat -c %s "$1" 2>&1)
  case "$detail" in
    *'Permission denied'*|*'Operation not permitted'*) return 42;;
    *'No such file or directory'*|*'Not a directory'*) return 41;;
    *) return 48;;
  esac
}
checkdirs() {
  current=/storage/emulated/0
  [ -d "$current" ] || { missing "$current"; return $?; }
  [ -r "$current" ] && [ -x "$current" ] || return 42
  rest=${1#/storage/emulated/0/}
  while [ -n "$rest" ]; do
    part=${rest%%/*}; current="$current/$part"
    [ ! -L "$current" ] || return 43
    [ -e "$current" ] || { missing "$current"; return $?; }
    [ -d "$current" ] || return 45
    [ -r "$current" ] && [ -x "$current" ] || return 42
    [ "$rest" != "$part" ] || break
    rest=${rest#*/}
  done
  storage=$(readlink -f /storage/emulated/0) || return 48
  resolved=$(readlink -f "$1") || return 48
  [ "$resolved" = "$storage/${1#/storage/emulated/0/}" ] || return 44
}
'''


def read_command(path, limit):
    root = source_root(path)
    return GUARD + '\nr=' + shlex.quote(root) + '\np=' + shlex.quote(path) + r'''
checkdirs "$r" || exit $?
base=$resolved
checkdirs "${p%/*}" || exit $?
[ ! -L "$p" ] || exit 43
[ -e "$p" ] || { missing "$p"; exit $?; }
[ -f "$p" ] || exit 45
[ -r "$p" ] || exit 42
resolved=$(readlink -f "$p") || exit 48
[ "$resolved" = "$storage/${p#/storage/emulated/0/}" ] || exit 44
case "$resolved" in "$base"/*) ;; *) exit 44;; esac
exec 3< "$p" || exit 42
opened=$(readlink -f /proc/$$/fd/3) || exit 48
[ "$opened" = "$resolved" ] || exit 47
case "$opened" in "$base"/*) ;; *) exit 44;; esac
[ -f /proc/$$/fd/3 ] || exit 45
checkdirs "${p%/*}" || exit $?
[ ! -L "$p" ] || exit 43
[ "$(readlink -f "$p")" = "$opened" ] || exit 47
size=$(stat -L -c %s /proc/$$/fd/3) || exit 48
case "$size" in ''|*[!0-9]*) exit 48;; esac
''' + f'[ "$size" -le {limit} ] || exit 46\nexec head -c {limit + 1} <&3\n'


class Adb:
    def __init__(self, serial=None):
        self.prefix = ['adb'] + (['-s', serial] if serial else [])
        self.budget_end = time.monotonic() + 180

    def run(self, command, maximum, timeout=25):
        # Both pipes are drained concurrently and bounded; stderr contents never
        # enter JSON/logs because an OEM/adb diagnostic may contain credentials.
        argv = self.prefix + ['shell', '-T', 'sh -c ' + shlex.quote(command)]
        try:
            proc = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        except FileNotFoundError:
            raise ExportError('ADB_NOT_FOUND') from None
        out, err = bytearray(), bytearray()
        errors = []
        changed = threading.Event()
        deadline = min(self.budget_end, time.monotonic() + timeout)

        def drain(pipe, buffer, bound, limit_code):
            try:
                while True:
                    chunk = pipe.read1(65536)
                    if not chunk:
                        break
                    if len(buffer) + len(chunk) > bound:
                        errors.append(limit_code)
                        break
                    buffer.extend(chunk)
            except (OSError, ValueError):
                errors.append('ADB_PIPE_IO')
            finally:
                changed.set()

        readers = [
            threading.Thread(target=drain, args=(proc.stdout, out, maximum, 'ADB_OUTPUT_LIMIT'), daemon=True),
            threading.Thread(target=drain, args=(proc.stderr, err, 8192, 'ADB_STDERR_LIMIT'), daemon=True),
        ]
        for reader in readers:
            reader.start()
        try:
            while proc.poll() is None or any(reader.is_alive() for reader in readers):
                if errors:
                    break
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    errors.append('ADB_TIMEOUT')
                    break
                changed.wait(min(0.1, remaining))
                changed.clear()
            if errors and proc.poll() is None:
                proc.kill()
            try:
                code = proc.wait(timeout=max(0.1, deadline - time.monotonic()))
            except subprocess.TimeoutExpired:
                proc.kill()
                code = proc.wait(timeout=2)
                errors.append('ADB_TIMEOUT')
        finally:
            if proc.poll() is None:
                proc.kill()
                proc.wait(timeout=2)
            for reader in readers:
                reader.join(timeout=1)
            for pipe, reader in zip((proc.stdout, proc.stderr), readers):
                if not reader.is_alive():
                    pipe.close()
        if errors:
            raise ExportError(errors[0], code, len(err))
        diagnostic = err.decode('utf-8', errors='replace').lower()
        if 'permission denied' in diagnostic or 'operation not permitted' in diagnostic:
            raise ExportError('PERMISSION_DENIED', code, len(err))
        if code == 0 and ('find:' in diagnostic or 'stat:' in diagnostic):
            raise ExportError('REMOTE_IO', code, len(err))
        if code != 0:
            reason = {41: 'MISSING', 42: 'PERMISSION_DENIED', 43: 'SYMLINK_REJECTED',
                      44: 'PATH_ESCAPE', 45: 'NOT_REGULAR', 46: 'FILE_TOO_LARGE',
                      47: 'SOURCE_CHANGED', 48: 'REMOTE_IO'}.get(code, 'ADB_FAILED')
            if 'permission denied' in diagnostic or 'operation not permitted' in diagnostic:
                reason = 'PERMISSION_DENIED'
            elif 'unauthorized' in diagnostic:
                reason = 'ADB_UNAUTHORIZED'
            raise ExportError(reason, code, len(err))
        # Successful adb may emit startup status; captured and deliberately omitted.
        return bytes(out)

    def read(self, path, maximum):
        return self.run(read_command(path, maximum), maximum)

    def list_paths(self, root, depth=8, entries=10000):
        if root not in ROOTS:
            source_root(root + '/placeholder')
        command = GUARD + '\nr=' + shlex.quote(root) + r'''
checkdirs "$r" || exit $?
find "$r" -maxdepth ''' + str(depth) + r''' -print0 | (
  count=0
  while IFS= read -r -d '' p; do
    count=$((count+1))
    [ "$count" -le ''' + str(entries) + r''' ] || { printf '!LIMIT\000'; break; }
    [ ! -L "$p" ] && [ -f "$p" ] || continue
    printf '%s\000' "$p"
  done
) | head -c ''' + str(MIB + 1)
        data = self.run(command, MIB)
        if b'!LIMIT\0' in data:
            raise ExportError('SCAN_ENTRY_LIMIT')
        paths = data.split(b'\0')
        if paths[-1] != b'' or len(paths) - 1 > entries:
            raise ExportError('SCAN_ENTRY_LIMIT')
        return [p.decode('utf-8') for p in paths[:-1]]


def bin_header(data):
    if len(data) < 0xa8 or data[:4] != b'\x5a\xa5\x34\x12':
        raise ExportError('INVALID_BIN')
    raw_id = data[0x28:0x68].split(b'\0', 1)[0]
    identifier = raw_id.decode('ascii', errors='replace')
    if not re.fullmatch(r'(?:[0-9]{9}|[0-9]{12})', identifier) or len(raw_id) == 64:
        identifier = None
    name = None
    raw_name = data[0x68:0xa4].split(b'\0', 1)[0]
    for encoding in ('utf-8', 'gb18030'):
        try:
            text = raw_name.decode(encoding).strip()
            if clean_name(text):
                name = text
                break
        except UnicodeDecodeError:
            pass
    return identifier, name


def clean_name(text):
    return (isinstance(text, str) and 0 < len(text) <= 256 and
            not any(ord(c) < 32 or 127 <= ord(c) <= 159 or c == '\ufffd' for c in text))


def xml_name(data, relative, identifier):
    if re.search(br'<!\s*(DOCTYPE|ENTITY)', data, re.IGNORECASE):
        raise ExportError('XML_DTD_FORBIDDEN')
    try:
        tree = ET.fromstring(data.decode('utf-8-sig'))
    except (ET.ParseError, UnicodeDecodeError):
        raise ExportError('XML_INVALID') from None
    stack = [(tree, 1)]
    count = 0
    while stack:
        node, depth = stack.pop()
        count += 1
        if count > 4096 or depth > 32 or len(node.attrib) > 64:
            raise ExportError('XML_LIMIT')
        stack.extend((child, depth + 1) for child in node)
    if relative == 'description.xml' and tree.tag == 'watch':
        ids, names = tree.findall('pkgName'), tree.findall('name')
        if len(ids) != 1 or len(names) > 1:
            raise ExportError('XML_INVALID')
        declared = (ids[0].text or '').strip()
        name = (names[0].text or '').strip() if names else None
    elif relative == 'resources/manifest.xml' and tree.tag == 'Watchface':
        declared, name = tree.get('id'), tree.get('name')
        if name and name.startswith('@'):
            translation = next((t for t in tree.findall('./Resources/Translation')
                                if t.get('name') == name[1:]), None)
            values = {} if translation is None else {
                item.get('language'): item.get('str') for item in translation.findall('Item')}
            name = next((values.get(lang) for lang in ('zh_CN', 'zh_TW', 'en_US')
                         if clean_name(values.get(lang))), None)
    else:
        raise ExportError('XML_INVALID')
    if identifier is None or declared != identifier:
        raise ExportError('XML_ID_MISMATCH')
    return name if clean_name(name) else None


def export_bundle(adb, path, output):
    if not candidate(path):
        raise ExportError('NOT_RESOURCE_BIN_CANDIDATE')
    bin_data = adb.read(path, MAX_BIN)
    identifier, name = bin_header(bin_data)
    metadata = {'id': identifier, 'name': name, 'nameSource': 'bin' if name else 'filename'}
    files = {'resource.bin': bin_data}
    warnings = []
    parent = path.rsplit('/', 1)[0]
    # A resource.bin directly in the official root is a valid bounded source.
    for relative in XML_PATHS:
        try:
            data = adb.read(parent + '/' + relative, MAX_XML)
            files[relative] = data
            parsed = xml_name(data, relative, identifier)
            if parsed and metadata['nameSource'] != 'xml':
                metadata.update(name=parsed, nameSource='xml')
        except ExportError as error:
            if error.code != 'MISSING':
                warnings.append('XML_' + error.code.removeprefix('XML_'))
    preview_paths = ['preview/preview.png', 'preview/preview.jpg']
    try:
        directory = parent + '/resources/_preview'
        for item in adb.list_paths(directory, depth=1, entries=256):
            if item.startswith(directory + '/'):
                relative = item[len(parent) + 1:]
                if re.fullmatch(r'resources/_preview/[^/\\\x00-\x1f]{1,240}\.(?:png|jpe?g)', relative, re.IGNORECASE):
                    preview_paths.append(relative)
        if len(preview_paths) > 34:
            warnings.append('PREVIEW_CANDIDATE_LIMIT')
    except ExportError as error:
        if error.code != 'MISSING':
            warnings.append('PREVIEW_' + error.code)
    image_total = image_count = 0
    for relative in dict.fromkeys(preview_paths[:34]):
        if image_count >= 8:
            warnings.append('PREVIEW_BUNDLE_LIMIT')
            break
        try:
            data = adb.read(parent + '/' + relative, MAX_IMAGE)
            if not (data.startswith(b'\x89PNG\r\n\x1a\n') or data.startswith(b'\xff\xd8\xff')):
                warnings.append('PREVIEW_INVALID_IMAGE')
                continue
            if image_total + len(data) > MAX_IMAGES or sum(map(len, files.values())) + len(data) > MAX_TOTAL - 128 * 1024:
                warnings.append('PREVIEW_BUNDLE_LIMIT')
                continue
            files[relative] = data
            image_total += len(data)
            image_count += 1
        except ExportError as error:
            if error.code != 'MISSING':
                warnings.append('PREVIEW_' + error.code)
    if not image_count:
        warnings.append('PREVIEW_MISSING')
    manifest = {
        'format': 'oronbox.watchface.bundle', 'version': 1,
        'createdAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'source': {'method': 'computer-adb-read-only', 'root': source_root(path), 'path': path},
        'metadata': metadata, 'warnings': list(dict.fromkeys(warnings))[:64],
        'files': [{'path': relative, 'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
                  for relative, data in files.items()],
    }
    manifest_bytes = json.dumps(manifest, ensure_ascii=False, indent=2).encode('utf-8')
    if sum(map(len, files.values())) + len(manifest_bytes) > MAX_TOTAL - 4096:
        raise ExportError('BUNDLE_TOO_LARGE')
    # Exclusive local create prevents accidentally replacing another export.
    destination = Path(output)
    with destination.open('xb') as handle:
        try:
            with zipfile.ZipFile(handle, 'w', compression=zipfile.ZIP_STORED) as archive:
                for relative, data in files.items():
                    archive.writestr(relative, data)
                archive.writestr('manifest.json', manifest_bytes)
            if handle.tell() > MAX_TOTAL:
                raise ExportError('BUNDLE_TOO_LARGE')
        except BaseException:
            destination.unlink(missing_ok=True)
            raise
    return {'ok': True, 'files': len(files), 'bytes': destination.stat().st_size,
            'warnings': manifest['warnings']}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--serial', help='Already-authorized adb device serial (never logged)')
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--list', action='store_true', help='List candidates in the two fixed official roots')
    mode.add_argument('--path', help='Exact resource.bin candidate returned by --list')
    parser.add_argument('--output', help='New local ZIP filename; existing files are never overwritten')
    args = parser.parse_args(argv)
    if args.path and not args.output:
        parser.error('--path requires --output')
    if args.list and args.output:
        parser.error('--output requires --path')
    try:
        if args.path:
            if not candidate(args.path):
                raise ExportError('NOT_RESOURCE_BIN_CANDIDATE')
            result = export_bundle(Adb(args.serial), args.path, args.output)
        else:
            adb = Adb(args.serial)
            candidates, diagnostics = [], []
            for root in ROOTS:
                try:
                    paths = adb.list_paths(root)
                    for path in paths:
                        if path == root:
                            continue
                        if candidate(path):
                            candidates.append(path)
                            if len(candidates) > 200:
                                raise ExportError('SCAN_CANDIDATE_LIMIT')
                except ExportError as error:
                    diagnostics.append({'root': root, 'code': error.code,
                                        'exitCode': error.exit_code, 'stderrBytes': error.stderr_bytes})
            result = {'ok': not diagnostics, 'candidates': candidates[:200], 'diagnostics': diagnostics}
        print(json.dumps(result, ensure_ascii=False))
        return 0 if result['ok'] else 1
    except ExportError as error:
        print(json.dumps({'ok': False, 'error': error.code, 'exitCode': error.exit_code,
                          'stderrBytes': error.stderr_bytes}), file=sys.stderr)
    except (OSError, UnicodeError):
        # Do not echo arbitrary OS/OEM diagnostics or the supplied serial.
        print(json.dumps({'ok': False, 'error': 'LOCAL_IO_OR_ENCODING'}), file=sys.stderr)
    return 1


if __name__ == '__main__':
    sys.exit(main())
