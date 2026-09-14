"""Generate untracked native configuration from the CI secret; never log its value."""
import json
import os
from pathlib import Path

root = Path(__file__).resolve().parent.parent
key = os.environ.get('YANDEX_MAPKIT_API_KEY', '').strip()
if not key:
    raise SystemExit('Missing YANDEX_MAPKIT_API_KEY repository secret')
(root / 'mobile/ios/Terra/MapSecrets.swift').write_text(
    'enum MapSecrets { static let yandex = ' + json.dumps(key) + ' }\n', encoding='utf-8')
print('Native map configuration generated.')
