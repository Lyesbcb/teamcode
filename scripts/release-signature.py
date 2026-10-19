#!/usr/bin/env python3
"""Sign/verify exact release-manifest bytes against an independently pinned RSA public key."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

ALGORITHM = 'RSA-PSS-SHA256'
MAX_MANIFEST = 16 * 1024
MAX_ENVELOPE = 2048
PSS = ['-sigopt', 'rsa_padding_mode:pss', '-sigopt', 'rsa_pss_saltlen:32', '-sigopt', 'rsa_mgf1_md:sha256']


def openssl(arguments):
    result = subprocess.run(['openssl', *arguments], capture_output=True, timeout=30,
                            env={name: value for name, value in os.environ.items() if name != 'TEAMCODE_RELEASE_SIGNING_KEY'})
    if result.returncode:
        raise ValueError('Release signature operation failed; diagnostic output withheld.')
    return result.stdout


def key_id(public_key):
    encoded = openssl(['pkey', '-pubin', '-in', str(public_key), '-outform', 'DER'])
    text = openssl(['pkey', '-pubin', '-in', str(public_key), '-text', '-noout'])
    if b'(3072 bit)' not in text or b'Modulus:' not in text:
        raise ValueError('The pinned release key must be RSA-3072.')
    return hashlib.sha256(encoded).hexdigest()


def manifest_bytes(path):
    with path.open('rb') as source:
        data = source.read(MAX_MANIFEST + 1)
    if not 1 <= len(data) <= MAX_MANIFEST:
        raise ValueError('Release manifest exceeds its size bound.')
    return data


def verify(manifest, signature, public_key):
    payload = manifest_bytes(manifest)
    with signature.open('rb') as source:
        encoded = source.read(MAX_ENVELOPE + 1)
    if not 1 <= len(encoded) <= MAX_ENVELOPE:
        raise ValueError('Release signature exceeds its size bound.')
    envelope = json.loads(encoded)
    if not isinstance(envelope, dict) or set(envelope) != {'schemaVersion', 'algorithm', 'keyId', 'signature'} or type(envelope['schemaVersion']) is not int or envelope['schemaVersion'] != 1 or envelope['algorithm'] != ALGORITHM or envelope['keyId'] != key_id(public_key):
        raise ValueError('Untrusted release signature algorithm, key or schema.')
    try:
        raw = base64.b64decode(envelope['signature'], validate=True)
    except (ValueError, TypeError):
        raise ValueError('Malformed release signature.') from None
    if len(raw) != 384:
        raise ValueError('Invalid RSA-3072 signature length.')
    with tempfile.TemporaryDirectory(prefix='teamcode-signature-verify-') as directory:
        raw_path = Path(directory) / 'signature.bin'; raw_path.write_bytes(raw)
        snapshot = Path(directory) / 'manifest.json'; snapshot.write_bytes(payload)
        openssl(['dgst', '-sha256', '-verify', str(public_key), '-signature', str(raw_path), *PSS, str(snapshot)])
        if manifest_bytes(manifest) != payload:
            raise ValueError('The release manifest changed during verification.')


def sign(manifest, private_key, public_key, signature):
    payload = manifest_bytes(manifest)
    if signature.exists():
        raise ValueError('Refusing to replace an existing release signature.')
    identifier = key_id(public_key)
    with tempfile.TemporaryDirectory(prefix='teamcode-release-sign-') as directory:
        output = Path(directory) / 'signature.bin'
        snapshot = Path(directory) / 'manifest.json'; snapshot.write_bytes(payload)
        openssl(['dgst', '-sha256', '-sign', str(private_key), '-out', str(output), *PSS, str(snapshot)])
        if output.stat().st_size != 384:
            raise ValueError('Release private key does not have the required size.')
        envelope = {'schemaVersion': 1, 'algorithm': ALGORITHM, 'keyId': identifier,
                    'signature': base64.b64encode(output.read_bytes()).decode('ascii')}
        staged = Path(directory) / 'manifest.sig'
        staged.write_text(json.dumps(envelope, sort_keys=True) + '\n')
        verify(snapshot, staged, public_key)
        if manifest_bytes(manifest) != payload:
            raise ValueError('The release manifest changed during signing.')
        # Creation without replacement prevents accidental signature/key rotation on retry.
        with signature.open('x') as destination:
            destination.write(staged.read_text())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('sign', 'verify'))
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--signature', type=Path, required=True)
    parser.add_argument('--public-key', type=Path, required=True)
    args = parser.parse_args()
    if args.command == 'verify':
        verify(args.manifest, args.signature, args.public_key)
    else:
        key = os.environ.get('TEAMCODE_RELEASE_SIGNING_KEY')
        if not key or len(key) > 16_384:
            raise ValueError('Configure the independent release-signing key before publishing.')
        with tempfile.TemporaryDirectory(prefix='teamcode-release-key-') as directory:
            private = Path(directory) / 'private.pem'
            descriptor = os.open(private, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
            with os.fdopen(descriptor, 'w') as output: output.write(key)
            sign(args.manifest, private, args.public_key, args.signature)
    print('Release manifest signature verified.')


if __name__ == '__main__':
    main()
