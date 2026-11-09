import copy
import importlib.util
import unittest
from pathlib import Path

root = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("native_prefetch", root / "scripts/verify-native-prefetch-contract.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class NativePrefetchContractTest(unittest.TestCase):
    def setUp(self):
        self.candidate = {"status": "candidate-not-enabled", "classes": {"LHost;": {
            "methods": {"next()I": 1}, "fields": {"handler": {"descriptor": "Landroid/os/Handler;", "access": 2}}}}}
        self.classes = {"LHost;": {"method_access": {"next()I": 1}, "fields": {"handler": "Landroid/os/Handler;"}, "field_access": {"handler": 2}}}
    def test_descriptor_or_access_changes_fail_closed(self):
        self.assertEqual([], module.verify(self.classes, self.candidate))
        for mutate in [lambda c: c["method_access"].update({"next()I": 9}), lambda c: c["fields"].update({"handler": "Ljava/lang/Object;"}), lambda c: c["method_access"].clear()]:
            changed = copy.deepcopy(self.classes); mutate(changed["LHost;"])
            self.assertTrue(module.verify(changed, self.candidate))
    def test_missing_class_and_claimed_runtime_support_are_rejected(self):
        self.assertTrue(module.verify({}, self.candidate))
        self.candidate["status"] = "supported"
        self.assertTrue(module.verify(self.classes, self.candidate))
