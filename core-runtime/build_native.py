"""Developer/CI build only. Uses a package exported by the production renderer.

No credentials or user projects are included in the generic launcher artifact.
Each OS/architecture builds its own binary; no cross-compilation is claimed.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import tempfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--package', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    source = args.package.resolve(strict=True)
    runner = source / 'runners/python/runner.py'
    if not runner.is_file():
        parser.error('Export the production renderer fixture before building')
    system = platform.system()
    arch = {'AMD64': 'x86_64', 'aarch64': 'arm64'}.get(platform.machine(), platform.machine())
    if (system, arch) not in {('Windows', 'x86_64'), ('Darwin', 'x86_64'), ('Darwin', 'arm64')}:
        parser.error('Supported build hosts: Windows x64, macOS Intel or Apple Silicon')
    target = ('windows' if system == 'Windows' else 'macos') + '-' + arch
    output = args.output.resolve() / target
    output.mkdir(parents=True, exist_ok=False)
    with tempfile.TemporaryDirectory(prefix='agentown-native-build-') as raw:
        temp = Path(raw)
        command = [sys.executable, '-m', 'PyInstaller', '--noconfirm', '--onedir', '--name', 'Agentown',
                   '--distpath', str(temp / 'dist'), '--workpath', str(temp / 'work'), '--specpath', str(temp),
                   '--paths', str(source / 'runtime'), '--collect-all', 'tframex',
                   '--collect-data', 'jsonschema_specifications', '--collect-all', 'pptx',
                   '--collect-all', 'docx', '--collect-all', 'openpyxl', str(runner)]
        if system == 'Darwin':
            command.insert(-1, '--windowed')
        subprocess.run(command, check=True)
        built = temp / 'dist' / ('Agentown.app' if system == 'Darwin' else 'Agentown')
        shipped = output / built.name
        shutil.copytree(built, shipped, symlinks=True)
        # Test the frozen binary against deterministic data, without Python on PATH.
        # This is a dependency/startup check, not live AI or consumer acceptance.
        smoke = temp / 'smoke'
        shutil.copytree(source, smoke)
        if system == 'Darwin':
            shutil.copytree(shipped, smoke / shipped.name, symlinks=True)
            executable = smoke / 'Agentown.app/Contents/MacOS/Agentown'
        else:
            shutil.copytree(shipped, smoke, dirs_exist_ok=True)
            executable = smoke / 'Agentown.exe'
        environment = dict(os.environ)
        environment['PATH'] = os.environ.get('SystemRoot', 'C:\\Windows') + '\\System32' if system == 'Windows' else '/usr/bin:/bin'
        checked = subprocess.run([str(executable), '--check'], env=environment, cwd=temp,
                                 capture_output=True, text=True, timeout=60)
        if checked.returncode:
            raise RuntimeError('Frozen startup check failed: ' + checked.stdout + checked.stderr)
        # macOS windowed executables need not have stdout; exit code is the startup gate.
        hashes = {str(p.relative_to(output)): hashlib.sha256(p.read_bytes()).hexdigest()
                  for p in output.rglob('*') if p.is_file() and not p.is_symlink()}
        (output / 'build-manifest.json').write_text(json.dumps({
            'target': target, 'sourceSha': os.environ.get('GITHUB_SHA', 'LOCAL_UNRELEASED'),
            'startupCheck': 'PASSED', 'liveAi': 'NOT_TESTED', 'consumerReady': False,
            'aiRequirement': 'Authenticated Codex CLI still required for AI workflows',
            'files': hashes,
        }, indent=2))
    print('NATIVE_BUILD_PASSED ' + target + ' CONSUMER_NOT_APPROVED', flush=True)


if __name__ == '__main__':
    main()
