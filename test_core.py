"""Fast, real JVM tests using the already downloaded pinned Kotlin compiler.
Run the Gradle suite first to populate the local cache. No downloads or mocks.
"""
from pathlib import Path
import os, subprocess, sys, xml.etree.ElementTree as ET
ROOT = Path(__file__).resolve().parent
CACHE = ROOT/'.cache/gradle/caches/modules-2/files-2.1'
def artifact(group, name, version):
    matches = list((CACHE/group/name/version).glob('*/'+name+'-'+version+'.jar'))
    if len(matches) != 1:
        raise SystemExit(f'Expected one cached artifact: {group}:{name}:{version}, got {len(matches)}')
    return str(matches[0])
compiler = artifact('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.2.21')
pom = next((CACHE/'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.21').glob('*/*.pom'))
ns = {'m':'http://maven.apache.org/POM/4.0.0'}
deps = [artifact(d.find('m:groupId',ns).text, d.find('m:artifactId',ns).text, d.find('m:version',ns).text)
        for d in ET.parse(pom).findall('m:dependencies/m:dependency',ns)]
annotations = artifact('org.jetbrains','annotations','23.0.0')
stdlib = artifact('org.jetbrains.kotlin','kotlin-stdlib','2.2.21')
junit = artifact('junit','junit','4.13.2')
hamcrest = artifact('org.hamcrest','hamcrest-core','1.3')
out = ROOT/'.cache/core-tests';out.mkdir(parents=True,exist_ok=True)
java = str(ROOT/'.toolchain/jdk/bin/java')
classpath = os.pathsep.join([stdlib,junit,hamcrest,annotations])
cmd = [java,'-Xmx256m','-cp',os.pathsep.join([compiler,annotations]+deps),
       'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17',
       '-classpath',classpath,'-d',str(out),
       str(ROOT/'app/src/main/java/com/swararia/audiolab/LibraryRules.kt'),
       str(ROOT/'app/src/test/java/com/swararia/audiolab/LibraryRulesTest.kt')]
subprocess.run(cmd,check=True)
raise SystemExit(subprocess.call([java,'-Xmx128m','-cp',os.pathsep.join([str(out),classpath]),
                                 'org.junit.runner.JUnitCore','com.swararia.audiolab.LibraryRulesTest']))
