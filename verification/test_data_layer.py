"""Lightweight host evidence; NOT Android MigrationTestHelper/device evidence."""
import json
import pathlib
import re
import sqlite3
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/main/java/com/swararia/audiolab'

class MigrationSqlTest(unittest.TestCase):
    def test_publication_inspection_uses_owned_readonly_descriptor_not_indexed_size(self):
        # Host source/API contract, NOT execution against an Android MediaProvider.
        library = (SOURCE / 'MediaLibrary.kt').read_text()
        inspect = library.split('override suspend fun inspect(uri: String)', 1)[1].split('override suspend fun discardPending', 1)[0]
        self.assertNotIn('MediaStore.Audio.Media.SIZE', inspect,
                         'Pending rows can retain indexed SIZE=0 after the writer closes')
        self.assertNotIn('MediaColumns.SIZE', inspect)
        self.assertNotIn('makeVisible(', inspect, 'Inspection must not publish to force a scan')
        self.assertNotIn('resolver.update(', inspect)
        validations = ['ManagedCopyRules.validUri(uri)', 'MediaStore.Audio.Media._ID',
                       'ContentUris.parseId(Uri.parse(uri))', 'MediaStore.Audio.Media.RELATIVE_PATH',
                       'ManagedCopyRules.PATH', 'MediaStore.MediaColumns.OWNER_PACKAGE_NAME',
                       '== context.packageName', 'MediaStore.Audio.Media.IS_PENDING']
        opening = inspect.index('resolver.openFileDescriptor(Uri.parse(uri), "r")')
        for validation in validations:
            self.assertIn(validation, inspect[:opening], 'Validate provider identity/ownership/pending before opening')
        self.assertRegex(inspect, r'openFileDescriptor\(Uri.parse\(uri\), "r"\)\s*\?: error\(')
        self.assertRegex(inspect, r'descriptor\.use\s*\{\s*it\.statSize\s*\}')
        self.assertIn('check(bytes >= 0)', inspect, 'Unknown descriptor size must fail closed')
        self.assertIn('DownloadQueuePublication.Media(pending, bytes)', inspect)

    def test_publication_marker_lookup_includes_pending_rows_then_checks_owner(self):
        # API 29 collection queries otherwise exclude the hidden insert-before-associate row.
        # Host query/source contract only; real platform API compilation runs separately.
        library = (SOURCE / 'MediaLibrary.kt').read_text()
        locate = library.split('override suspend fun locate(name: String)', 1)[1].split('override suspend fun inspect', 1)[0]
        self.assertIn('resolver.query(MediaStore.setIncludePending(collection),', locate)
        self.assertIn('MediaStore.Audio.Media.RELATIVE_PATH}=? AND ${MediaStore.Audio.Media.DISPLAY_NAME}=?', locate)
        self.assertIn('arrayOf(ManagedCopyRules.PATH, name)', locate)
        self.assertIn('Regex("swararia-', locate)
        self.assertIn('check(!it.moveToNext())', locate)
        self.assertLess(locate.index('checkNotNull(inspect(uri))'), locate.rindex('uri'))
        self.assertNotIn('resolver.update(', locate)

    def test_native_journal_wiring_is_not_a_second_completion_commit(self):
        library = (SOURCE / 'MediaLibrary.kt').read_text()
        queue = (SOURCE / 'DownloadQueueRepository.kt').read_text()
        service = (SOURCE / 'IngestService.kt').read_text()
        self.assertIn('database.downloadPublicationRecovery =', library, 'Existing startup queue recovery must reconcile physical publications too')
        self.assertIn('database.downloadPublicationRecovery?.invoke()', queue)
        self.assertIn('control.commitDurably', service)
        self.assertIn('addDownloadedFile(output.single(), job.id)', service)
        self.assertNotIn('control.commit {', service)
        transaction = library[library.index('return database.withTransaction {'):library.index('init { database.downloadPublicationRecovery')]
        self.assertIn('database.tracks().insert(', transaction)
        self.assertIn('database.downloads().complete(id, trackId)', transaction)

    def test_queue_sql_guards_sequential_work_and_preserves_running_on_batch_cancel(self):
        migration = (SOURCE / 'AudioMigrations.kt').read_text()
        db = sqlite3.connect(':memory:')
        db.execute(re.findall(r'const val CREATE_DOWNLOADS = "([^"]+)"', migration)[0])
        source = (SOURCE / 'DownloadQueueRepository.kt').read_text()
        statements = dict((method, sql) for sql, method in re.findall(r'@Query\("([^"]+)"\)\s*(?:\n\s*)?(?:suspend )?fun (\w+)', source))
        def insert(batch, url):
            return db.execute("INSERT INTO download_jobs(batchId,sourceUrl,title,status,stage,authorized,createdAt) VALUES (?,?,'Audio','QUEUED','Menunggu',1,9)", (batch, url)).lastrowid
        first = insert('a', 'https://example.com/1')
        second = insert('a', 'https://example.com/2')
        self.assertEqual(first, db.execute(statements['nextQueued'], {'batchId':'a'}).fetchone()[0])
        self.assertEqual(1, db.execute(statements['markRunning'], {'id':first}).rowcount)
        self.assertEqual(0, db.execute(statements['markRunning'], {'id':second}).rowcount)
        db.execute(statements['cancelBatch'], {'batchId':'a'})
        self.assertEqual([(first,'RUNNING'),(second,'CANCELED')], db.execute('SELECT id,status FROM download_jobs ORDER BY id').fetchall())
        db.execute(statements['recoverInterrupted'])
        self.assertEqual('FAILED', db.execute('SELECT status FROM download_jobs WHERE id=?',(first,)).fetchone()[0])
        db.execute(statements['retryFailed'], {'batchId':'a'})
        self.assertEqual([(first,'QUEUED'),(second,'CANCELED')], db.execute('SELECT id,status FROM download_jobs ORDER BY id').fetchall())
        latest = insert('b', 'https://example.com/3')
        self.assertEqual([latest], [r[0] for r in db.execute(statements['observeLatestBatch'])])
        self.assertEqual(1, db.execute(statements['markRunning'], {'id':first}).rowcount)
        db.execute(statements['complete'], {'id':first, 'trackId':22})
        self.assertEqual(('COMPLETED',22,1.0), db.execute('SELECT status,trackId,progress FROM download_jobs WHERE id=?',(first,)).fetchone())
        db.execute(statements['cancel'], {'id':first})
        self.assertEqual('COMPLETED', db.execute('SELECT status FROM download_jobs WHERE id=?',(first,)).fetchone()[0])
        db.execute(statements['clearTrack'], {'trackId':22})
        self.assertIsNone(db.execute('SELECT trackId FROM download_jobs WHERE id=?',(first,)).fetchone()[0])

    def test_durable_insert_boundary_rejects_streams_and_drops_sensitive_thumbnails(self):
        db = sqlite3.connect(':memory:')
        migration = (SOURCE / 'AudioMigrations.kt').read_text()
        db.execute(re.findall(r'const val CREATE_DOWNLOADS = "([^"]+)"', migration)[0])
        source = (SOURCE / 'DownloadQueueRepository.kt').read_text()
        statements = dict((method, sql) for sql, method in re.findall(r'@Query\("([^\"]+)"\)\s*(?:\n\s*)?(?:suspend )?fun (\w+)', source))
        self.assertIn('insertSafe', statements, 'DAO insertion needs a SQL guard, not just UI validation')
        params = dict(batchId='b', sourceUrl='https://www.youtube.com/watch?v=abcdefgh_12', title='Audio', thumbnailUrl='https://i.ytimg.com/vi/abcdefgh_12/hqdefault.jpg?token=secret', createdAt=1)
        for url in ['https://cdn.example/a?sig=secret', params['sourceUrl']+'&token=secret', params['sourceUrl']+'\x00&token=secret', 'https://www.youtube.com/watch?v=abc%20defghi', 'https://www.youtube.com/watch?v=abcdefgh_1!']:
            db.execute(statements['insertSafe'], params | {'sourceUrl':url})
        self.assertEqual(0, db.execute('SELECT count(*) FROM download_jobs').fetchone()[0])
        for video_id in ['abcdefghij\x00', 'abc\x00?sig=xx']:
            url = 'https://www.youtube.com/watch?v=' + video_id
            with self.subTest(sourceUrl=url):
                self.assertEqual(43, len(url.encode('utf-8')))
                db.execute('DELETE FROM download_jobs')
                db.execute(statements['insertSafe'], params | {'sourceUrl':url})
                self.assertEqual(0, db.execute('SELECT count(*) FROM download_jobs').fetchone()[0])
        for thumbnail in ['https://user:password@i.ytimg.com/a','https://evil.example/image', 'https://i.ytimg.com/a#secret', 'https://i.ytimg.com/a\x00?token=secret', params['thumbnailUrl']]:
            db.execute('DELETE FROM download_jobs')
            db.execute(statements['insertSafe'], params | {'thumbnailUrl':thumbnail})
            self.assertIsNone(db.execute('SELECT thumbnailUrl FROM download_jobs').fetchone()[0])
        db.execute('DELETE FROM download_jobs')
        db.execute(statements['insertSafe'], params)
        self.assertEqual((params['sourceUrl'],None), db.execute('SELECT sourceUrl,thumbnailUrl FROM download_jobs').fetchone())

    def test_publication_journal_blocks_retry_until_reconciled(self):
        db = sqlite3.connect(':memory:')
        migration = (SOURCE / 'AudioMigrations.kt').read_text()
        db.execute(re.findall(r'const val CREATE_DOWNLOADS = "([^"]+)"', migration)[0])
        source = (SOURCE / 'DownloadQueueRepository.kt').read_text()
        sql = dict((method, statement) for statement, method in re.findall(r'@Query\("([^\"]+)"\)\s*(?:\n\s*)?(?:suspend )?fun (\w+)', source))
        self.assertIn('beginPublication', sql, 'Publication intent must precede MediaStore creation')
        db.execute("INSERT INTO download_jobs(batchId,sourceUrl,title,status,stage,authorized,createdAt) VALUES ('b','https://www.youtube.com/watch?v=abcdefgh_12','Audio','RUNNING','Copy',1,1)")
        db.execute(sql['beginPublication'], {'id':1,'name':'swararia-job-unique.opus'})
        for method, params in [('fail',{'id':1,'message':'failed'}), ('cancel',{'id':1}), ('recoverInterrupted',{}), ('retryFailed',{'batchId':'b'})]:
            db.execute(sql[method], params)
            self.assertEqual(('RUNNING','swararia-job-unique.opus'), db.execute('SELECT status,publicationName FROM download_jobs').fetchone())
        db.execute(sql['associatePublication'], {'id':1,'uri':'content://media/external_primary/audio/media/77'})
        db.execute(sql['readyPublication'], {'id':1,'bytes':123})
        self.assertEqual((123,), db.execute('SELECT publicationBytes FROM download_jobs').fetchone())
        # Simulated completion-write failure: no independently committed track may survive.
        db.execute('CREATE TABLE tracks(id INTEGER PRIMARY KEY, uri TEXT UNIQUE)')
        db.commit()
        try:
            with db:
                db.execute("INSERT INTO tracks VALUES (77,'content://media/external_primary/audio/media/77')")
                db.execute(sql['complete'], {'id':1,'trackId':77})
                raise RuntimeError('crash before commit')
        except RuntimeError: pass
        self.assertEqual(0, db.execute('SELECT count(*) FROM tracks').fetchone()[0])
        self.assertEqual(('RUNNING',123), db.execute('SELECT status,publicationBytes FROM download_jobs').fetchone())
        with db:
            db.execute("INSERT INTO tracks VALUES (77,'content://media/external_primary/audio/media/77')")
            db.execute(sql['complete'], {'id':1,'trackId':77})
        db.execute(sql['recoverInterrupted'])
        db.execute(sql['retryFailed'], {'batchId':'b'})
        self.assertEqual(('COMPLETED',77), db.execute('SELECT status,trackId FROM download_jobs').fetchone())

    def test_actual_additive_migration_preserves_v1_data(self):
        migration = SOURCE / 'AudioMigrations.kt'
        self.assertTrue(migration.exists(), 'Additive migration is not implemented')
        sql = re.findall(r'const val \w+ = "([^"]+)"', migration.read_text())
        self.assertEqual(2, len(sql))
        schema = json.loads((ROOT / 'app/schemas/com.swararia.audiolab.AudioDatabase/1.json').read_text())['database']
        db = sqlite3.connect(':memory:')
        for entity in schema['entities']:
            db.execute(entity['createSql'].replace('${TABLE_NAME}', entity['tableName']))
            for index in entity.get('indices', []):
                db.execute(index['createSql'].replace('${TABLE_NAME}', entity['tableName']))
        rows = [(1, 'content://media/external_primary/audio/media/8', 'renamed.opus', 123, None, 9), (9, 'content://media/external_primary/audio/media/9', 'song.mp3', 444, 321, 10)]
        history = [(3, 1, 'old.opus', 'renamed.opus', 7)]
        db.executemany('INSERT INTO tracks VALUES (?,?,?,?,?,?)', rows)
        db.executemany('INSERT INTO rename_history VALUES (?,?,?,?,?)', history)
        for statement in sql: db.execute(statement)
        self.assertEqual([r + (None,) for r in rows], db.execute('SELECT * FROM tracks ORDER BY id').fetchall())
        self.assertEqual(history, db.execute('SELECT * FROM rename_history').fetchall())
        columns = db.execute('PRAGMA table_info(download_jobs)').fetchall()
        self.assertEqual(['id','batchId','sourceUrl','title','thumbnailUrl','status','progress','stage','error','trackId','authorized','createdAt','publicationName','pendingUri','publicationBytes'], [r[1] for r in columns])
        self.assertTrue(all(r[4] is None for r in columns), 'Kotlin defaults are not SQL defaults')
        self.assertEqual({'thumbnailUrl','progress','error','trackId','publicationName','pendingUri','publicationBytes'}, {r[1] for r in columns if not r[3]})
        db.execute("INSERT INTO download_jobs(batchId,sourceUrl,title,thumbnailUrl,status,progress,stage,error,trackId,authorized,createdAt) VALUES ('batch','https://example.com/a','A',NULL,'QUEUED',NULL,'Menunggu',NULL,NULL,1,123)")
        job = db.execute('SELECT * FROM download_jobs').fetchone()
        self.assertEqual((1,'batch','https://example.com/a','A',None,'QUEUED',None,'Menunggu',None,None,1,123,None,None,None), job)
        self.assertIn('AUTOINCREMENT', db.execute("SELECT sql FROM sqlite_master WHERE name='download_jobs'").fetchone()[0])
        self.assertEqual(1, db.execute("SELECT count(*) FROM sqlite_master WHERE name='index_tracks_uri'").fetchone()[0])

    def test_actual_additive_migration_preserves_v2_to_v3_data(self):
        migration = (SOURCE / 'AudioMigrations.kt').read_text()
        block_match = re.search(r'MIGRATION_2_3_STATEMENTS\s*=\s*listOf\((.*?)\n\s*\)', migration, re.DOTALL)
        self.assertIsNotNone(block_match, 'MIGRATION_2_3_STATEMENTS missing')
        stmts = re.findall(r'"((?:[^"\\]|\\.)+)"', block_match.group(1))
        self.assertEqual(9, len(stmts))

        schema_v2 = json.loads((ROOT / 'app/schemas/com.swararia.audiolab.AudioDatabase/2.json').read_text())['database']
        schema_v3_path = ROOT / 'app/schemas/com.swararia.audiolab.AudioDatabase/3.json'
        self.assertTrue(schema_v3_path.exists(), 'Room schema 3.json must be exported')
        schema_v3 = json.loads(schema_v3_path.read_text())['database']

        migrated = sqlite3.connect(':memory:')
        for entity in schema_v2['entities']:
            migrated.execute(entity['createSql'].replace('${TABLE_NAME}', entity['tableName']))
            for index in entity.get('indices', []):
                migrated.execute(index['createSql'].replace('${TABLE_NAME}', entity['tableName']))
        v2_tracks = [
            (1, 'content://media/external_primary/audio/media/8', 'song.opus', 123, 5000, 9, 'art-1'),
            (9, 'content://media/external_primary/audio/media/9', 'clip.mp3', 444, None, 10, None),
        ]
        migrated.executemany('INSERT INTO tracks VALUES (?,?,?,?,?,?,?)', v2_tracks)
        for statement in stmts:
            migrated.execute(statement)

        self.assertEqual(
            [r + (None, None, None, None) for r in v2_tracks],
            migrated.execute('SELECT * FROM tracks ORDER BY id').fetchall(),
        )

        reference = sqlite3.connect(':memory:')
        for entity in schema_v3['entities']:
            reference.execute(entity['createSql'].replace('${TABLE_NAME}', entity['tableName']))
            for index in entity.get('indices', []):
                reference.execute(index['createSql'].replace('${TABLE_NAME}', entity['tableName']))

        for entity in schema_v3['entities']:
            table = entity['tableName']
            self.assertEqual(
                [(r[1], r[2], r[3], r[5]) for r in reference.execute(f'PRAGMA table_info({table})').fetchall()],
                [(r[1], r[2], r[3], r[5]) for r in migrated.execute(f'PRAGMA table_info({table})').fetchall()],
                f'Column schema mismatch for {table}',
            )
            self.assertEqual(
                reference.execute(f'PRAGMA foreign_key_list({table})').fetchall(),
                migrated.execute(f'PRAGMA foreign_key_list({table})').fetchall(),
                f'Foreign key mismatch for {table}',
            )
            ref_idx = sorted(
                r[0] for r in reference.execute("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name=? AND name NOT LIKE 'sqlite_autoindex%'", (table,)).fetchall()
            )
            mig_idx = sorted(
                r[0] for r in migrated.execute("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name=? AND name NOT LIKE 'sqlite_autoindex%'", (table,)).fetchall()
            )
            self.assertEqual(ref_idx, mig_idx, f'Index mismatch for {table}')

        migrated.commit()
        migrated.execute('PRAGMA foreign_keys = ON')
        migrated.execute("INSERT INTO playlists(id, name, createdAt) VALUES (10, 'Fokus', 100)")
        migrated.execute("INSERT INTO playlist_tracks(playlistId, trackId, position, addedAt) VALUES (10, 1, 0, 101)")
        migrated.execute("DELETE FROM tracks WHERE id = 1")
        self.assertEqual(0, migrated.execute("SELECT count(*) FROM playlist_tracks").fetchone()[0])

    def test_actual_additive_migration_preserves_v3_to_v4_data(self):
        migration = (SOURCE / 'AudioMigrations.kt').read_text()
        block_match = re.search(r'MIGRATION_3_4_STATEMENTS\s*=\s*listOf\((.*?)\n\s*\)', migration, re.DOTALL)
        self.assertIsNotNone(block_match, 'MIGRATION_3_4_STATEMENTS missing')
        stmts = re.findall(r'"((?:[^"\\]|\\.)+)"', block_match.group(1))
        self.assertEqual(2, len(stmts))

        schema_v3 = json.loads((ROOT / 'app/schemas/com.swararia.audiolab.AudioDatabase/3.json').read_text())['database']
        schema_v4_path = ROOT / 'app/schemas/com.swararia.audiolab.AudioDatabase/4.json'
        self.assertTrue(schema_v4_path.exists(), 'Room schema 4.json must be exported')
        schema_v4 = json.loads(schema_v4_path.read_text())['database']

        migrated = sqlite3.connect(':memory:')
        for entity in schema_v3['entities']:
            migrated.execute(entity['createSql'].replace('${TABLE_NAME}', entity['tableName']))
            for index in entity.get('indices', []):
                migrated.execute(index['createSql'].replace('${TABLE_NAME}', entity['tableName']))
        v3_tracks = [
            (1, 'content://media/external_primary/audio/media/8', 'song.opus', 123, 5000, 9, 'art-1', 'Artist A', 'Album A', 'Rock', 1000),
            (9, 'content://media/external_primary/audio/media/9', 'clip.mp3', 444, None, 10, None, None, None, None, None),
        ]
        migrated.executemany('INSERT INTO tracks VALUES (?,?,?,?,?,?,?,?,?,?,?)', v3_tracks)
        migrated.execute("INSERT INTO playlists(id, name, createdAt) VALUES (10, 'Fokus', 100)")
        migrated.execute("INSERT INTO playlist_tracks(playlistId, trackId, position, addedAt) VALUES (10, 1, 0, 101)")
        for statement in stmts:
            migrated.execute(statement)

        self.assertEqual(
            [r + (None,) for r in v3_tracks],
            migrated.execute('SELECT * FROM tracks ORDER BY id').fetchall(),
        )
        self.assertEqual([(10, 'Fokus', 100)], migrated.execute('SELECT * FROM playlists').fetchall())

        reference = sqlite3.connect(':memory:')
        for entity in schema_v4['entities']:
            reference.execute(entity['createSql'].replace('${TABLE_NAME}', entity['tableName']))
            for index in entity.get('indices', []):
                reference.execute(index['createSql'].replace('${TABLE_NAME}', entity['tableName']))

        for entity in schema_v4['entities']:
            table = entity['tableName']
            self.assertEqual(
                [(r[1], r[2], r[3], r[5]) for r in reference.execute(f'PRAGMA table_info({table})').fetchall()],
                [(r[1], r[2], r[3], r[5]) for r in migrated.execute(f'PRAGMA table_info({table})').fetchall()],
                f'Column schema mismatch for {table}',
            )
            self.assertEqual(
                reference.execute(f'PRAGMA foreign_key_list({table})').fetchall(),
                migrated.execute(f'PRAGMA foreign_key_list({table})').fetchall(),
                f'Foreign key mismatch for {table}',
            )
            ref_idx = sorted(
                r[0] for r in reference.execute("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name=? AND name NOT LIKE 'sqlite_autoindex%'", (table,)).fetchall()
            )
            mig_idx = sorted(
                r[0] for r in migrated.execute("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name=? AND name NOT LIKE 'sqlite_autoindex%'", (table,)).fetchall()
            )
            self.assertEqual(ref_idx, mig_idx, f'Index mismatch for {table}')

if __name__ == '__main__': unittest.main()
