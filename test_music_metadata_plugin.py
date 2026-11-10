"""Extractor adapter tests using the pinned engine; synthetic renderer fixtures only."""
from pathlib import Path
import importlib.util
import sys
import unittest
ROOT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT/'app/src/main/res/raw/ytdlp'))
PLUGIN=ROOT/'app/src/main/assets/search_plugins/yt_dlp_plugins/extractor/swararia_music.py'

class MusicMetadataTests(unittest.TestCase):
    def test_song_keeps_real_duration_artist_and_thumbnail_without_inventing_channel(self):
        self.assertTrue(PLUGIN.is_file(),'Music metadata adapter is missing')
        spec=importlib.util.spec_from_file_location('swararia_music_test',PLUGIN)
        module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        renderer={'playlistItemData':{'videoId':'BaW_jenozKc'},'flexColumns':[
          {'musicResponsiveListItemFlexColumnRenderer':{'text':{'runs':[{'text':'Song title'}]}}},
          {'musicResponsiveListItemFlexColumnRenderer':{'text':{'runs':[{'text':'Artist name','navigationEndpoint':{'browseEndpoint':{'browseId':'UCabcdefghijklmnopqrstuv'}}}]}}}],
          'fixedColumns':[{'musicResponsiveListItemFixedColumnRenderer':{'text':{'runs':[{'text':'3:42'}]}}}],
          'thumbnail':{'musicThumbnailRenderer':{'thumbnail':{'thumbnails':[{'url':'https://i.ytimg.com/vi/BaW_jenozKc/hqdefault.jpg'}]}}}}
        hit=module.SwarariaMusicSearchIE()._music_reponsive_list_entry(renderer)
        self.assertEqual(222,hit.get('duration'))
        self.assertEqual('Artist name',hit.get('artist'))
        self.assertEqual('Song title',hit.get('title'))
        self.assertIn('i.ytimg.com',hit.get('thumbnail',''))
        self.assertNotIn('channel',hit,'An artist name is not proof of the upload channel')

    def playlist_hit(self, endpoint, *, linked_creator=True, subtitle_runs=None, song_id=None):
        spec=importlib.util.spec_from_file_location('swararia_music_test',PLUGIN)
        module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        creator={'text':'Creator'}
        if linked_creator:
            creator['navigationEndpoint']={'browseEndpoint':{'browseId':'UCabcdefghijklmnopqrstuv'}}
        renderer={'navigationEndpoint':endpoint,'flexColumns':[
            {'musicResponsiveListItemFlexColumnRenderer':{'text':{'runs':[{'text':'Collection'}]}}},
            {'musicResponsiveListItemFlexColumnRenderer':{'text':{'runs':[
                {'text':'Playlist'}, {'text':' • '}, creator,
                {'text':' • '}, {'text':'12 songs'}]}}},
        ]}
        if subtitle_runs is not None:
            renderer['flexColumns'][1]['musicResponsiveListItemFlexColumnRenderer']['text']['runs']=subtitle_runs
        if song_id is not None:
            renderer['playlistItemData']={'videoId':song_id}
        return module.SwarariaMusicSearchIE()._music_reponsive_list_entry(renderer)

    def test_watch_playlist_keeps_title_and_linked_creator(self):
        playlist_id='PLabcdefghijklmnop'
        hit=self.playlist_hit({'watchEndpoint':{'playlistId':playlist_id}})
        self.assertEqual('Collection',hit.get('title'))
        self.assertEqual('Creator',hit.get('channel'))
        self.assertEqual(playlist_id,hit.get('id'))
        self.assertEqual('YoutubeTab',hit.get('ie_key'))
        self.assertEqual(f'https://www.youtube.com/playlist?list={playlist_id}',hit.get('url'))

    def test_playlist_keeps_textual_creator_without_channel_link(self):
        playlist_id='PLabcdefghijklmnop'
        for endpoint in (
                {'watchEndpoint':{'playlistId':playlist_id}},
                {'browseEndpoint':{'browseId':f'VL{playlist_id}'}}):
            with self.subTest(endpoint=endpoint):
                hit=self.playlist_hit(endpoint,linked_creator=False)
                self.assertEqual('Collection',hit.get('title'))
                self.assertEqual('Creator',hit.get('channel'))
                self.assertEqual(playlist_id,hit.get('id'))
                self.assertEqual('YoutubeTab',hit.get('ie_key'))
                self.assertEqual(f'https://www.youtube.com/playlist?list={playlist_id}',hit.get('url'))

    def test_playlist_creator_only_subtitle_needs_no_channel_link(self):
        for endpoint in (
                {'watchEndpoint':{'playlistId':'PLabcdefghijklmnop'}},
                {'browseEndpoint':{'browseId':'VLPLabcdefghijklmnop'}}):
            with self.subTest(endpoint=endpoint):
                hit=self.playlist_hit(endpoint,subtitle_runs=[{'text':'Creator'}])
                self.assertEqual('Collection',hit.get('title'))
                self.assertEqual('Creator',hit.get('channel'))

    def test_vl_playlist_preserves_linked_creator(self):
        hit=self.playlist_hit({'browseEndpoint':{'browseId':'VLPLabcdefghijklmnop'}})
        self.assertEqual('Collection',hit.get('title'))
        self.assertEqual('Creator',hit.get('channel'))

    def test_watch_playlist_with_video_keeps_playlist_metadata(self):
        hit=self.playlist_hit({'watchEndpoint':{'playlistId':'PLabcdefghijklmnop','videoId':'BaW_jenozKc'}})
        self.assertEqual('Collection',hit.get('title'))
        self.assertEqual('Creator',hit.get('channel'))
        self.assertEqual('https://www.youtube.com/playlist?list=PLabcdefghijklmnop',hit.get('url'))

    def test_playlist_without_creator_does_not_use_type_or_count_as_channel(self):
        for runs in ([], [{'text':'12 songs'}], [{'text':'Playlist • 12 songs'}]):
            with self.subTest(runs=runs):
                hit=self.playlist_hit({'watchEndpoint':{'playlistId':'PLabcdefghijklmnop'}},subtitle_runs=runs)
                self.assertNotIn('channel',hit)

    def test_song_in_playlist_does_not_turn_artist_into_channel(self):
        hit=self.playlist_hit({'watchEndpoint':{'playlistId':'PLabcdefghijklmnop'}},song_id='BaW_jenozKc')
        self.assertEqual('BaW_jenozKc',hit.get('id'))
        self.assertEqual('Creator',hit.get('artist'))
        self.assertNotIn('channel',hit)
        self.assertEqual('Youtube',hit.get('ie_key'))

if __name__=='__main__':unittest.main(verbosity=2)
