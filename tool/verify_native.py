#!/usr/bin/env python3
"""Fetch hash-pinned JVM test dependencies; never connects to a phone or invokes su."""
import hashlib
import os
from pathlib import Path
import subprocess
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CACHE = Path(os.environ.get('RUNNER_TEMP', '/tmp')) / 'watchface-native-deps'
MAVEN = 'https://repo.maven.apache.org/maven2/'
ARTIFACTS = [
 ('org/jetbrains/kotlin/kotlin-compiler-embeddable/1.9.23/kotlin-compiler-embeddable-1.9.23.jar', 'cc94064974bf9ebf59945e31217cf2d16a0cebaaf2487eb0748fc1cbd1787943'),
 ('org/jetbrains/kotlin/kotlin-stdlib/1.9.23/kotlin-stdlib-1.9.23.jar', '8910cc238807d86ef550cb1f0b10dd5ed40b35a4ec1a52525f760aede84ead37'),
 ('org/jetbrains/kotlin/kotlin-script-runtime/1.9.23/kotlin-script-runtime-1.9.23.jar', '75137e414a1a5b4b4d090f812d0e35eb30b4f0c923a53d69585456bb24fc1df8'),
 ('org/jetbrains/kotlin/kotlin-reflect/1.6.10/kotlin-reflect-1.6.10.jar', '3277ac102ae17aad10a55abec75ff5696c8d109790396434b496e75087854203'),
 ('org/jetbrains/annotations/13.0/annotations-13.0.jar', 'ace2a10dc8e2d5fd34925ecac03e4988b2c0f851650c94b8cef49ba1bd111478'),
 ('org/jetbrains/intellij/deps/trove4j/1.0.20200330/trove4j-1.0.20200330.jar', 'c5fd725bffab51846bf3c77db1383c60aaaebfe1b7fe2f00d23fe1b7df0a439d'),
 ('junit/junit/4.13.2/junit-4.13.2.jar', '8e495b634469d64fb8acfa3495a065cbacc8a0fff55ce1e31007be4c16dc57d3'),
 ('org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar', '66fdef91e9739348df7a096aa384a5685f4e875584cce89386a7a47251c4d8e9'),
]

def fetch(path, digest=None):
    target = CACHE / path.split('/')[-1]
    if not target.is_file() or (digest and hashlib.sha256(target.read_bytes()).hexdigest() != digest):
        with urllib.request.urlopen(MAVEN + path, timeout=60) as response:
            data = response.read(80 * 1024 * 1024)
        if digest and hashlib.sha256(data).hexdigest() != digest:
            raise RuntimeError('Dependency checksum mismatch: ' + target.name)
        target.write_bytes(data)
    return target


def main():
    CACHE.mkdir(parents=True, exist_ok=True)
    for path, digest in ARTIFACTS:
        fetch(path, digest)
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    android = Path(os.environ.get('ORONBOX_TEST_ANDROID_JAR', str(Path(sdk or '/opt/android-sdk') / 'platforms/android-35/android.jar')))
    if not android.is_file():
        raise SystemExit('Android Platform 35 android.jar is required')
    api = CACHE / 'shizuku-api-13.1.5.jar'
    digest = '4315fcd853f487321548be5a997749fe43105fa29aaaa6f6e7a8cdc958a38357'
    if not api.is_file() or hashlib.sha256(api.read_bytes()).hexdigest() != digest:
        aar = fetch('dev/rikka/shizuku/api/13.1.5/api-13.1.5.aar')
        with zipfile.ZipFile(aar) as archive:
            data = archive.read('classes.jar')
        if hashlib.sha256(data).hexdigest() != digest:
            raise RuntimeError('Shizuku API checksum mismatch')
        api.write_bytes(data)
    env = dict(os.environ, ORONBOX_TEST_GRADLE_LIB=str(CACHE), ORONBOX_TEST_ANDROID_JAR=str(android), WATCHFACE_TEST_SHIZUKU_JAR=str(api))
    subprocess.run(['bash', 'test/native/watchface_cache/run_jvm_tests.sh'], cwd=ROOT, env=env, check=True)

if __name__ == '__main__':
    main()
