"""Release trust boundaries: identity, provenance, versions and certificate pinning."""
import importlib.util
from pathlib import Path
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'ci/release.py'
SPEC = importlib.util.spec_from_file_location('ampp_release', SCRIPT)
RELEASE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RELEASE)


class ReleasePolicyTest(unittest.TestCase):
    source = 'versionCode = 119\nversionName = "1.6.8-custom.1"\n'
    commit = 'a' * 40

    def metadata(self):
        return dict(commit=self.commit, versionName='1.6.8-custom.1', versionCode=119,
                    applicationId=RELEASE.APP_ID,
                    unsignedApk='AM-plus-plus-1.6.8-custom.1-unsigned.apk')

    def test_valid_source_revision(self):
        self.assertEqual(('1.6.8-custom.1', 119),
                         RELEASE.validate_metadata(self.metadata(), self.commit, self.source))

    def test_source_version_and_filename_cannot_be_replaced(self):
        for field, value in [('commit', 'b' * 40), ('versionName', '1.6.9'),
                             ('versionCode', 118), ('applicationId', 'other.app'),
                             ('unsignedApk', '../../foreign.apk')]:
            with self.subTest(field=field), self.assertRaises(ValueError):
                data = self.metadata()
                data[field] = value
                RELEASE.validate_metadata(data, self.commit, self.source)

    def test_short_or_non_sha_source_ref_is_rejected(self):
        for value in ('main', 'a' * 7, '../artifact', 'A' * 40):
            with self.subTest(value=value), self.assertRaises(ValueError):
                RELEASE.validate_metadata(self.metadata(), value, self.source)

    def test_ambiguous_or_unsafe_versions_are_rejected(self):
        for source in (self.source + self.source, '', 'versionName = "$(cmd)"\nversionCode = 1',
                       'versionName = "../1.0.0"\nversionCode = 1'):
            with self.subTest(source=source), self.assertRaises(ValueError):
                RELEASE.version(source)

    def test_only_the_exact_personal_account_can_publish(self):
        RELEASE.validate_identity(dict(login='Laoch832', id=173591322, type='User'))
        for profile in (dict(login='Laoch832', id=173591322, type='Bot'),
                        dict(login='Laoch832', id=1, type='User'),
                        dict(login='github-actions[bot]', id=41898282, type='Bot'),
                        dict(login='other', id=173591322, type='User')):
            with self.subTest(profile=profile), self.assertRaises(ValueError):
                RELEASE.validate_identity(profile)

    def signature(self, digest='a' * 64):
        return ('Verified using v2 scheme (APK Signature Scheme v2): true\n'
                'Number of signers: 1\n'
                f'V3.0 Signer: certificate SHA-256 digest: {digest}\n')

    def test_expected_certificate_and_v2_signature_are_required(self):
        RELEASE.validate_signature(self.signature(), 'a' * 64)
        for output in (self.signature('b' * 64), self.signature().replace('true', 'false'),
                       self.signature() + 'CN=Android Debug',
                       self.signature().replace('signers: 1', 'signers: 2'),
                       self.signature() + 'certificate SHA-256 digest: ' + 'b' * 64,
                       ''):
            with self.subTest(output=output), self.assertRaises(ValueError):
                RELEASE.validate_signature(output, 'a' * 64)


if __name__ == '__main__':
    unittest.main()
