#!/usr/bin/env python3
"""Prepare public, same-build signed installation fixtures; never read a signing key."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil

spec = importlib.util.spec_from_file_location('release_bundle', Path(__file__).with_name('release-bundle.py'))
bundle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bundle)


def variant(source, output):
    payload = (source / 'manifest.json').read_bytes()
    if not 1 <= len(payload) < 16 * 1024 or not isinstance(json.loads(payload), dict):
        raise ValueError('Invalid bounded release manifest.')
    output.mkdir(parents=True, exist_ok=False)
    # Distinct authenticated bytes select a distinct immutable installation directory.
    # Version, source commit and all binary claims remain identical to the real build.
    (output / 'manifest.json').write_bytes(payload + b'\n')


def prepare(source, alternate, output, target):
    payload = (source / 'manifest.json').read_bytes()
    changed = (alternate / 'manifest.json').read_bytes()
    if not 1 <= len(payload) < 16 * 1024 or changed != payload + b'\n':
        raise ValueError('The acceptance variant must preserve every build/asset claim.')
    manifest = json.loads(payload)
    asset = manifest['assets'][target]
    name = bundle.asset_name(manifest['version'], target)
    binary = source / name
    if asset['fileName'] != name or binary.is_symlink() or not binary.is_file() or not 1 <= binary.stat().st_size <= bundle.MAX_BINARY:
        raise ValueError('Invalid acceptance asset.')
    if binary.stat().st_size != asset['size'] or bundle.digest(binary) != asset['sha256'] or bundle.binary_target(binary) != target:
        raise ValueError('Acceptance asset integrity or architecture mismatch.')
    output.mkdir(parents=True, exist_ok=False)
    for label, metadata in (('first', source), ('second', alternate)):
        destination = output / label
        destination.mkdir()
        for filename, maximum in (('manifest.json', 16 * 1024), ('manifest.sig', 2048)):
            data = (metadata / filename).read_bytes()
            if not 1 <= len(data) <= maximum:
                raise ValueError('Acceptance metadata exceeds its bound.')
            (destination / filename).write_bytes(data)
        shutil.copyfile(binary, destination / name)
        (destination / name).chmod(0o755)
    # Native smoke verifies both publisher signatures before installing either payload.
    assert hashlib.sha256(payload).digest() != hashlib.sha256(changed).digest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    for name in ('variant', 'prepare'):
        command = commands.add_parser(name)
        command.add_argument('--bundle', type=Path, required=True)
        command.add_argument('--output', type=Path, required=True)
        if name == 'prepare':
            command.add_argument('--variant', type=Path, required=True)
            command.add_argument('--target', choices=bundle.TARGETS, required=True)
    args = parser.parse_args()
    if args.command == 'variant': variant(args.bundle, args.output)
    else: prepare(args.bundle, args.variant, args.output, args.target)
    print('Prepared same-build installation acceptance fixture; no version-transition claim.')


if __name__ == '__main__': main()
