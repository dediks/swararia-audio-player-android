from pathlib import Path
import unittest,json
from PIL import Image
ROOT=Path(__file__).resolve().parent
NAMES=['01-search','02-playlist-select','03-downloads','04-library-delete','05-player','06-search-loading']
class PreviewContract(unittest.TestCase):
    def test_all_phone_screens_decode_and_are_labeled_concepts(self):
        meta=json.loads((ROOT/'manifest.json').read_text())
        self.assertEqual(meta['screens'],NAMES)
        self.assertTrue(meta['sample_data'])
        for name in NAMES:
            with Image.open(ROOT/'exports'/f'{name}.png') as im:
                self.assertEqual(im.size,(864,1920)); im.load()
    def test_loading_is_animated(self):
        with Image.open(ROOT/'exports/06-search-loading.gif') as im:
            self.assertGreater(im.n_frames,1)
    def test_application_and_apks_unchanged(self):
        import hashlib
        for name,expected in json.loads((ROOT/'before.json').read_text()).items():
            p=ROOT.parents[1]/name
            with p.open('rb') as f:self.assertEqual(hashlib.file_digest(f,'sha256').hexdigest(),expected,name)
if __name__=='__main__':unittest.main(verbosity=2)
