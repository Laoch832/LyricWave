"""Release trust boundaries: identity, provenance, versions and certificate pinning."""
import importlib.util
from pathlib import Path
import unittest
from unittest import mock
from types import SimpleNamespace
import json

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
                    unsignedApk='LyricWave-1.6.8-custom.1-unsigned.apk')

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

    def test_published_tag_event_skips_duplicate_build_but_manual_publish_refuses_overwrite(self):
        response = SimpleNamespace(returncode=0, stdout=json.dumps(dict(
            draft=False, author=dict(login='Laoch832', id=173591322, type='User'))))
        with mock.patch.object(RELEASE, 'context', return_value=(self.source, self.commit)), \
                mock.patch.object(RELEASE.subprocess, 'run', return_value=response), \
                mock.patch.dict(RELEASE.os.environ, {'GITHUB_REF': 'refs/tags/v1.6.8-custom.1'}, clear=True):
            self.assertFalse(RELEASE.plan())
            with mock.patch.dict(RELEASE.os.environ, {'AMPP_PUBLISH_REQUESTED': 'true'}):
                with self.assertRaises(ValueError):
                    RELEASE.plan()

    def test_mismatched_tag_is_rejected_before_any_remote_operation(self):
        with mock.patch.object(RELEASE, 'context', return_value=(self.source, self.commit)), \
                mock.patch.object(RELEASE.subprocess, 'run') as remote, \
                mock.patch.dict(RELEASE.os.environ, {'GITHUB_REF': 'refs/tags/v9.0.0'}, clear=True):
            with self.assertRaises(ValueError):
                RELEASE.plan()
            remote.assert_not_called()

    def test_unknown_release_state_fails_instead_of_bypassing_preflight(self):
        for status, stderr in [(1, 'HTTP 403'), (1, 'network unavailable')]:
            response = SimpleNamespace(returncode=status, stderr=stderr)
            with mock.patch.object(RELEASE, 'context', return_value=(self.source, self.commit)), \
                    mock.patch.object(RELEASE.subprocess, 'run', return_value=response), \
                    mock.patch.dict(RELEASE.os.environ, {'GITHUB_REF': 'refs/tags/v1.6.8-custom.1'}, clear=True):
                with self.assertRaises(ValueError):
                    RELEASE.plan()

    def test_new_publications_are_stable_only(self):
        RELEASE.require_stable("1.7.0")
        for name in ("1.7.0-beta.1", "1.7.0-rc.1", "1.7.0-custom.1"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                RELEASE.require_stable(name)

    def test_release_notes_are_per_version_not_historic_boilerplate(self):
        content = RELEASE.release_content("1.7.0")
        metadata = dict(versionName="1.7.0", versionCode=132, commit=self.commit)
        text = RELEASE.render_release_notes(content, metadata, "42")
        self.assertIn("128", text)
        self.assertIn("中国大陆", text)
        self.assertNotIn("新增 HDR 歌词", text)
        other = dict(content, summary="Another release", changes=[dict(type="fix", title="Other bug", description="Other fix")])
        self.assertNotEqual(text, RELEASE.render_release_notes(other, metadata, "43"))

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
