import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('release_signature', Path(__file__).with_name('release-signature.py'))
signing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(signing)


class ReleaseSignatureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = tempfile.TemporaryDirectory(prefix='teamcode-signature-test-')
        cls.root = Path(cls.fixture.name)
        cls.keys = []
        for number in (1, 2):
            private = cls.root / f'fixture-{number}.pem'; public = cls.root / f'fixture-{number}.pub'
            signing.openssl(['genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:3072', '-out', str(private)])
            signing.openssl(['pkey', '-in', str(private), '-pubout', '-out', str(public)])
            cls.keys.append((private, public))

    @classmethod
    def tearDownClass(cls): cls.fixture.cleanup()

    def test_exact_bytes_are_signed_and_tampering_unknown_keys_and_overwrites_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); manifest = root / 'manifest.json'; signature = root / 'manifest.sig'
            manifest.write_text('{"version":"0.1.0-alpha.1"}\n')
            private, public = self.keys[0]
            signing.sign(manifest, private, public, signature)
            signing.verify(manifest, signature, public)
            with self.assertRaises(ValueError): signing.verify(manifest, signature, self.keys[1][1])
            with self.assertRaises(ValueError): signing.sign(manifest, private, public, signature)
            manifest.write_text(manifest.read_text() + ' ')
            with self.assertRaises(ValueError): signing.verify(manifest, signature, public)

    def test_substituted_signature_schema_algorithm_key_or_bytes_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); manifest = root / 'manifest.json'; signature = root / 'manifest.sig'
            manifest.write_text('{}\n')
            signing.sign(manifest, *self.keys[0], signature)
            original = json.loads(signature.read_text())
            for field, value in [('schemaVersion', 2), ('schemaVersion', True), ('algorithm', 'RSA-NONE'), ('keyId', 'a' * 64), ('signature', 'invalid!'), ('signature', 'AAAA')]:
                with self.subTest(field=field, value=value):
                    signature.write_text(json.dumps({**original, field: value}))
                    with self.assertRaises(ValueError): signing.verify(manifest, signature, self.keys[0][1])
            signature.write_text(json.dumps({**original, 'extra': 'untrusted'}))
            with self.assertRaises(ValueError): signing.verify(manifest, signature, self.keys[0][1])

    def test_wrong_private_key_and_oversized_inputs_do_not_publish_a_signature(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); manifest = root / 'manifest.json'; signature = root / 'manifest.sig'
            manifest.write_text('{}')
            with self.assertRaises(ValueError): signing.sign(manifest, self.keys[1][0], self.keys[0][1], signature)
            self.assertFalse(signature.exists())
            manifest.write_bytes(b'x' * (signing.MAX_MANIFEST + 1))
            with self.assertRaises(ValueError): signing.sign(manifest, *self.keys[0], signature)
            self.assertFalse(signature.exists())

    def test_mutating_the_manifest_during_signing_cannot_publish_a_signature(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); manifest = root / 'manifest.json'; signature = root / 'manifest.sig'
            manifest.write_text('{}')
            original = signing.openssl
            def change_after_signing(arguments):
                result = original(arguments)
                if '-sign' in arguments: manifest.write_text('{"changed":true}')
                return result
            with patch.object(signing, 'openssl', change_after_signing):
                with self.assertRaises(ValueError): signing.sign(manifest, *self.keys[0], signature)
            self.assertFalse(signature.exists())


if __name__ == '__main__': unittest.main()
