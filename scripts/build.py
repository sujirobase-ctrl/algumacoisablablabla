"""Build the extension on Windows or Linux without packaging host libraries."""
import argparse
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]

def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version-code', type=int, required=True)
    parser.add_argument('--version-name', required=True)
    args = parser.parse_args()
    if not 4 < args.version_code <= 2100000000:
        parser.error('version-code must be above 4 and at most 2100000000')
    if args.version_name != f'14.{args.version_code}':
        parser.error('version-name must be 14.<version-code> for host API compatibility')
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])
    jdk = Path(os.environ['JAVA_HOME']) / 'bin'
    suffix = '.exe' if os.name == 'nt' else ''
    java, javac, jar = [jdk / (name + suffix) for name in ('java', 'javac', 'jar')]
    bt = sdk / 'build-tools/36.0.0'
    android = sdk / 'platforms/android-36/android.jar'
    for line in (ROOT / 'deps/SHA256SUMS').read_text().splitlines():
        expected, relative = line.split()
        if hashlib.sha256((ROOT / relative).read_bytes()).hexdigest() != expected:
            raise RuntimeError(f'Dependency checksum mismatch: {relative}')
    libs = [ROOT / 'deps' / name for name in ('api.jar', 'okhttp.jar', 'kotlin.jar', 'rxjava.jar')]
    output_dir = ROOT / 'dist'
    output_dir.mkdir(exist_ok=True)
    output = output_dir / f'aniyomi-pt.tomatobr-v{args.version_name}.apk'
    with tempfile.TemporaryDirectory(prefix='tomatobr-build-') as temporary:
        work = Path(temporary)
        for name in ('classes', 'stubs', 'dex', 'tests'):
            (work / name).mkdir()
        run(javac, '-encoding', 'UTF-8', '-d', work / 'tests', ROOT / 'src/TitleMatcher.java', ROOT / 'test/TitleMatcherTest.java')
        run(java, '-cp', work / 'tests', 'TitleMatcherTest')
        run(javac, '--release', '8', '-d', work / 'stubs', ROOT / 'compile-stubs/okio/ByteString.java')
        cp = os.pathsep.join(map(str, [android, work / 'stubs', *libs]))
        run(javac, '-encoding', 'UTF-8', '--release', '8', '-classpath', cp, '-d', work / 'classes', *sorted((ROOT / 'src').glob('*.java')))
        run(jar, 'cf', work / 'classes.jar', '-C', work / 'classes', '.')
        d8 = [java, '-cp', bt / 'lib/d8.jar', 'com.android.tools.r8.D8', '--min-api', '21', '--lib', android, '--output', work / 'dex']
        for lib in [work / 'stubs', *libs]:
            d8.extend(['--classpath', lib])
        run(*d8, work / 'classes.jar')
        namespace = 'http://schemas.android.com/apk/res/android'
        ET.register_namespace('android', namespace)
        manifest = ET.parse(ROOT / 'AndroidManifest.xml')
        manifest.getroot().set(f'{{{namespace}}}versionCode', str(args.version_code))
        manifest.getroot().set(f'{{{namespace}}}versionName', args.version_name)
        manifest.write(work / 'AndroidManifest.xml', encoding='utf-8', xml_declaration=True)
        run(bt / ('aapt' + suffix), 'package', '-f', '-M', work / 'AndroidManifest.xml', '-S', ROOT / 'res', '-I', android, '-F', work / 'unsigned.apk')
        with zipfile.ZipFile(work / 'unsigned.apk', 'a') as apk:
            apk.write(work / 'dex/classes.dex', 'classes.dex')
        run(bt / ('zipalign' + suffix), '-f', '4', work / 'unsigned.apk', work / 'aligned.apk')
        run(java, '-jar', bt / 'lib/apksigner.jar', 'sign', '--ks', os.environ['ANDROID_KEYSTORE_PATH'], '--ks-key-alias', os.environ['ANDROID_KEY_ALIAS'], '--ks-pass', 'env:ANDROID_KEYSTORE_PASSWORD', '--key-pass', 'env:ANDROID_KEY_PASSWORD', '--out', output, work / 'aligned.apk')
        run(java, '-jar', bt / 'lib/apksigner.jar', 'verify', '--verbose', output)
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    (output_dir / 'SHA256SUMS').write_text(f'{digest}  {output.name}\n')
    print(f'APK: {output.name}')

if __name__ == '__main__':
    main()
