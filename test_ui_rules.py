"""JVM-only checks for pure UI formatting; no Android device/render claims."""
from pathlib import Path
import os,subprocess,tempfile,xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parent
CACHE=ROOT/'.cache/gradle/caches/modules-2/files-2.1'
def jar(group,name,version):
    paths=list((CACHE/group/name/version).glob(f'*/{name}-{version}.jar'))
    assert len(paths)==1,(group,name,version)
    return str(paths[0])
ns={'m':'http://maven.apache.org/POM/4.0.0'}
pom=next((CACHE/'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.21').glob('*/*.pom'))
deps=[jar(d.find('m:groupId',ns).text,d.find('m:artifactId',ns).text,d.find('m:version',ns).text) for d in ET.parse(pom).findall('m:dependencies/m:dependency',ns)]
annotations=jar('org.jetbrains','annotations','23.0.0')
cp=os.pathsep.join([jar('org.jetbrains.kotlin','kotlin-stdlib','2.2.21'),jar('junit','junit','4.13.2'),jar('org.hamcrest','hamcrest-core','1.3'),annotations])
base=ROOT/'app/src/main/java/com/swararia/audiolab'
sources=[base/'LibraryRules.kt']+([base/'UiPresentation.kt'] if (base/'UiPresentation.kt').is_file() else [])+([base/'OnlineWorkRules.kt'] if (base/'OnlineWorkRules.kt').is_file() else [])+([base/'ThumbnailTransfer.kt'] if (base/'ThumbnailTransfer.kt').is_file() else [])+[ROOT/'app/src/test/java/com/swararia/audiolab/UiPresentationTest.kt',ROOT/'app/src/test/java/com/swararia/audiolab/OnlineWorkRulesTest.kt']
java=str(ROOT/'.toolchain/jdk/bin/java')
with tempfile.TemporaryDirectory(prefix='ui-jvm-',dir=ROOT/'.cache/tmp') as out:
    subprocess.run([java,'-Xmx256m','-cp',os.pathsep.join([jar('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.2.21'),annotations]+deps),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp,'-d',out]+[str(p) for p in sources],check=True)
    raise SystemExit(subprocess.call([java,'-Xmx128m','-cp',out+os.pathsep+cp,'org.junit.runner.JUnitCore','com.swararia.audiolab.UiPresentationTest','com.swararia.audiolab.OnlineWorkRulesTest']))
