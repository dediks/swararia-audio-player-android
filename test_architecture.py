"""Behavior tests for Android-free state orchestration, cached dependencies only."""
from pathlib import Path
import os,subprocess,tempfile,xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parent;CACHE=ROOT/'.cache/gradle/caches/modules-2/files-2.1'
def jar(group,name,version):
    found=list((CACHE/group/name/version).glob(f'*/{name}-{version}.jar'));assert len(found)==1,(group,name,version)
    return str(found[0])
ns={'m':'http://maven.apache.org/POM/4.0.0'}
pom=next((CACHE/'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.21').glob('*/*.pom'))
deps=[jar(d.find('m:groupId',ns).text,d.find('m:artifactId',ns).text,d.find('m:version',ns).text) for d in ET.parse(pom).findall('m:dependencies/m:dependency',ns)]
ann=jar('org.jetbrains','annotations','23.0.0')
cp=os.pathsep.join([jar('org.jetbrains.kotlin','kotlin-stdlib','2.2.21'),jar('junit','junit','4.13.2'),jar('org.hamcrest','hamcrest-core','1.3'),ann,jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.10.2')]+[jar('com.fasterxml.jackson.core',n,v) for n,v in [('jackson-databind','2.20.1'),('jackson-core','2.20.1'),('jackson-annotations','2.20')]])
base=ROOT/'app/src/main/java/com/swararia/audiolab'
names=['YoutubeSearchData.kt','ThumbnailPolicy.kt','OnlinePreviewData.kt','OnlineWorkRules.kt','SearchContracts.kt','SearchSession.kt']
sources=[base/n for n in names if (base/n).is_file()]+[ROOT/'app/src/test/java/com/swararia/audiolab/SearchSessionTest.kt']
java=str(ROOT/'.toolchain/jdk/bin/java')
with tempfile.TemporaryDirectory(prefix='architecture-jvm-',dir=ROOT/'.cache/tmp') as out:
    subprocess.run([java,'-Xmx256m','-cp',os.pathsep.join([jar('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.2.21'),ann]+deps),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp,'-d',out]+[str(p) for p in sources],check=True)
    raise SystemExit(subprocess.call([java,'-Xmx128m','-cp',out+os.pathsep+cp,'org.junit.runner.JUnitCore','com.swararia.audiolab.SearchSessionTest']))
