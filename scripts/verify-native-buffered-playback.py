#!/usr/bin/env python3
"""Check the exact official APK's PCM/recovery descriptors without modifying it or requesting licenses."""
import argparse
import importlib.util
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('asset_contract', ROOT/'scripts/verify-native-asset-preload.py')
contract = importlib.util.module_from_spec(spec); spec.loader.exec_module(contract)
verify = contract.verify

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    contract.verify_apk(parser.parse_args().apk, 'bufferedDrmRecovery')


if __name__ == '__main__':
    main()
