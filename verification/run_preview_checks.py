"""Direct real Media3 1.8.0/Android API compile, no Gradle/device execution."""
from pathlib import Path
import os, subprocess, zipfile, xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]
CACHE=ROOT/'.cache/gradle/caches/modules-2/files-2.1'
OUT=Path(os.environ['TMPDIR'])/'swararia-preview-api'; OUT.mkdir(parents=True,exist_ok=True)
SRC=ROOT/'app/src/main/java/com/swararia/audiolab'
def artifact(group,name,version):
    return next((CACHE/group/name/version).rglob('*.jar'))
ann=artifact('org.jetbrains','annotations','23.0.0')
compiler=artifact('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.2.21')
ns={'m':'http://maven.apache.org/POM/4.0.0'}
pom=next((CACHE/'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.21').rglob('*.pom'))
deps=[artifact(d.find('m:groupId',ns).text,d.find('m:artifactId',ns).text,d.find('m:version',ns).text) for d in ET.parse(pom).findall('m:dependencies/m:dependency',ns)]
cp=[ROOT/'.toolchain/android-sdk/platforms/android-36/android.jar']
for path in CACHE.rglob('*'):
    if path.suffix=='.jar' and 'kotlin-compiler' not in str(path): cp.append(path)
    elif path.suffix=='.aar':
        with zipfile.ZipFile(path) as z:
            if 'classes.jar' in z.namelist():
                out=OUT/(path.parent.parent.name+'-'+path.name+'.jar'); out.write_bytes(z.read('classes.jar')); cp.append(out)
glue=OUT/'AppWiringCompileOnly.kt'
commands=(SRC/'PlaybackControls.kt').read_text().split('object PlaybackCommands {',1)[1].split('\n}',1)[0]
glue.write_text('''package com.swararia.audiolab
import android.os.Bundle
import androidx.media3.session.SessionCommand
class AppContainer { val searchRepository: AudioSearchRepository get() = error("compile only") }
class SwarariaApp : android.app.Application() { val container: AppContainer get() = error("compile only") }
class MainActivity : android.app.Activity()
data class IngestState(val busy: Boolean = false)
object IngestStatus { val state = kotlinx.coroutines.flow.MutableStateFlow(IngestState()) }
object R { object drawable { const val ic_audio: Int = 1 } }
object PlaybackCommands {'''+commands+'\n}\n')
names=['PlaybackService','PreviewRemote','PreviewSession','PreviewResolver','PreviewUrlCache','OnlinePreviewData','YoutubeSearchData','ThumbnailPolicy','ThumbnailTransfer','SearchContracts','PlaybackRules','YoutubeSearchRepository']
java=str(ROOT/'.toolchain/jdk/bin/java')
subprocess.run([java,'-Xmx512m','-cp',':'.join(map(str,[compiler,ann]+deps)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',':'.join(map(str,cp)),'-d',str(OUT/'api.jar'),str(glue)]+[str(SRC/(n+'.kt')) for n in names],check=True)
print('PASS: actual service, remote controller and repository compile against cached Android/Media3 APIs; app wiring declarations compile-only')
