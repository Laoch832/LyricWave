import copy
import importlib.util
import json
import unittest
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('buffered', ROOT/'scripts/verify-native-buffered-playback.py')
buffered = importlib.util.module_from_spec(spec); spec.loader.exec_module(buffered)

class BufferedPlaybackContractTest(unittest.TestCase):
    def setUp(self):
        self.contract = json.loads((ROOT/'host-applemusic/src/main/resources/host-profiles/7.0.0-1614.json').read_text())['bufferedDrmRecovery']
        self.classes = {name: {'method_access': dict(c['methods']), 'fields': {f:v['descriptor'] for f,v in c['fields'].items()},
                              'field_access': {f:v['access'] for f,v in c['fields'].items()}}
                        for name,c in self.contract['verification']['classes'].items()}
    def test_reset_audio_interface_and_fatal_contract_changes_fail_closed(self):
        self.assertEqual([], buffered.verify(self.classes, self.contract))
        for kind in ('method', 'field', 'class'):
            changed = copy.deepcopy(self.classes)
            owner = next(n for n in changed if n.endswith('/SVAudioRendererV2;'))
            if kind == 'method':
                internal = next(n for n in changed if n.endswith('/ExoPlayerImplInternal;'))
                changed[internal]['method_access'].clear()
            elif kind == 'field': changed[owner]['field_access']['audioSink'] |= 8
            else: changed.pop(owner)
            self.assertTrue(buffered.verify(changed, self.contract))
    def test_buffering_is_gated_to_verified_exact_host_and_device_support_is_not_claimed(self):
        profiles = ROOT/'host-applemusic/src/main/resources/host-profiles'
        self.assertEqual(['7.0.0-1614.json'], [p.name for p in profiles.glob('*.json') if 'bufferedDrmRecovery' in json.loads(p.read_text())])
        self.assertFalse(self.contract['verification']['deviceValidated'])
        self.assertEqual(64, len(self.contract['verification']['apkSha256']))
        # The native helper may evict a streaming asset only after the runtime's downloaded guard.
        source = (ROOT/'host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicBufferedDrmTarget.kt').read_text()
        self.assertLess(source.index('call("downloaded", media)'), source.index('call("hostRemoveCache", host, media)'))
if __name__ == '__main__': unittest.main()
