"""Publish only the APK/checksum, retaining the highest version as latest."""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile

def gh(*args):
    return subprocess.check_output(['gh', *args], text=True)

parser = argparse.ArgumentParser()
parser.add_argument('--version', required=True)
parser.add_argument('--sha', required=True)
args = parser.parse_args()
tag = 'v' + args.version
with tempfile.TemporaryDirectory() as tmp:
    notes = Path(tmp) / 'notes.md'
    notes.write_text(f'APK da extensão Tomato BR.\n\nCommit: `{args.sha}`\n\nInstale o APK abaixo no Dantotsu/host compatível com API 14. Configure sua própria sessão Tomato nas preferências.\n\nSHA-256 disponível em `SHA256SUMS`.\n', encoding='utf-8')
    exists = subprocess.run(['gh', 'release', 'view', tag], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
    if not exists:
        gh('release', 'create', tag, '--target', args.sha, '--title', 'Tomato BR ' + args.version, '--notes-file', str(notes), '--latest=false')
    gh('release', 'upload', tag, f'dist/aniyomi-pt.tomatobr-v{args.version}.apk', 'dist/SHA256SUMS', '--clobber')
releases = json.loads(gh('release', 'list', '--limit', '1000', '--json', 'tagName,isDraft,isPrerelease'))
candidates = [r['tagName'] for r in releases if not r['isDraft'] and not r['isPrerelease'] and r['tagName'].startswith('v14.') and r['tagName'][4:].isdigit()]
latest = max(candidates, key=lambda name: int(name[4:]))
gh('release', 'edit', latest, '--latest')
