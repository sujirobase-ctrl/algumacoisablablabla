"""List every newly pushed commit and allocate monotonically increasing versions."""
import json
import os
import subprocess

before = os.environ['BEFORE']
after = os.environ['AFTER']
run = int(os.environ['GITHUB_RUN_NUMBER'])
if before and set(before) != {'0'}:
    revision = f'{before}..{after}'
else:
    revision = after
commits = [after] if os.environ.get('GITHUB_EVENT_NAME') == 'workflow_dispatch' else subprocess.check_output(['git', 'rev-list', '--reverse', revision], text=True).splitlines()
if not commits or len(commits) > 256:
    raise SystemExit('Expected 1 to 256 new commits. Split larger pushes.')
matrix = []
for index, sha in enumerate(commits, 1):
    code = 4 + run * 1000 + index
    if code > 2100000000:
        raise SystemExit('Android versionCode limit exceeded')
    matrix.append({'sha': sha, 'code': code, 'version': f'14.{code}'})
with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
    output.write('matrix=' + json.dumps({'include': matrix}) + '\n')
