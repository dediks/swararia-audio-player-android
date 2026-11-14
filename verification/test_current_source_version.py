"""Current-source identity only; never claims a new APK was built."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]

class CurrentSourceVersion(unittest.TestCase):
    def test_patch_build_is_distinguishable_without_changing_app_identity(self):
        source = (ROOT / 'app/build.gradle').read_text()
        self.assertIn('versionCode 8', source)
        self.assertIn("versionName '0.6.0-spike'", source)
        self.assertIn("applicationId 'com.swararia.audiolab.spike'", source)

if __name__ == '__main__':
    unittest.main()
