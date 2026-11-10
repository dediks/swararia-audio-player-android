"""Host asset/source contracts, not a claim of Android runtime rendering."""
from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parent
class UiIntegrationContracts(unittest.TestCase):
    def test_thumbnail_loader_is_bounded_and_network_setup_is_inside_error_boundary(self):
        code=(ROOT/'app/src/main/java/com/swararia/audiolab/YoutubeThumbnail.kt').read_text()
        loader=code.split('private fun loadYoutubeThumbnail(',1)[1]
        self.assertLess(loader.index('return try {'),loader.index('URL(url).openConnection()'))
        for needle in ['instanceFollowRedirects = false','connectTimeout = 2500','readTimeout = 3500',
                       'ThumbnailTransfer.read','withTimeoutOrNull(6000)','connection?.disconnect()']:
            self.assertIn(needle,code)

    def test_activity_uses_new_ui_and_fresh_online_dispatch_guards(self):
        code=(ROOT/'app/src/main/java/com/swararia/audiolab/MainActivity.kt').read_text()
        self.assertIn('SwarariaUi(', code)
        self.assertIn('model.search.collectAsStateWithLifecycle()',code)
        self.assertIn('onCancelSearch = { model.cancelSearch() }',code)
        self.assertIn('model.search.value.busy',code)
        self.assertIn('IngestStatus.state.value.busy',code)
        self.assertIn('OnlineWorkRules.canStart',code)
        self.assertGreaterEqual(code.count('whenOnlineIdle {'),7)
        self.assertNotIn('Scaffold(',code)

    def test_brand_logo_and_font_preserve_approved_asset_bytes(self):
        self.assertEqual((ROOT/'app/src/main/res/drawable-nodpi/swararia_logo.png').read_bytes(),
                         (ROOT/'design/ui-preview-v2-logo/assets/swararia-logo-512.png').read_bytes())
        self.assertEqual((ROOT/'app/src/main/res/font/inter_variable.ttf').read_bytes(),
                         (ROOT/'design/ui-preview-v1/assets/Inter-variable.ttf').read_bytes())
        for index in range(5):
            self.assertTrue((ROOT/f'app/src/main/res/drawable-nodpi/cover_{index}.png').is_file())
if __name__=='__main__':unittest.main(verbosity=2)
