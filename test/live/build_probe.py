#!/usr/bin/env python3
"""Build the separate test-only platform API consumer from a Maven classpath."""
import os
import subprocess
from pathlib import Path
from platforms import ROOT, SMOKE, JAVA21

def main():
    output=SMOKE/'probe';classes=output/'classes';classes.mkdir(parents=True,exist_ok=True)
    classpath=(output/'classpath.txt').read_text().strip()+os.pathsep+str(ROOT/'target/georestrict-2.1.0.jar')
    java_bin=JAVA21.parent
    sources=[str(p) for p in (ROOT/'test/live/probe').glob('*.java')]
    subprocess.run([str(java_bin/'javac'),'--release','17','-cp',classpath,'-d',str(classes),*sources],check=True)
    command=[str(java_bin/'jar'),'--create','--file',str(output/'GeoRestrictLiveProbe.jar'),'-C',str(classes),'.']
    subprocess.run(command,check=True)
    update=[str(java_bin/'jar'),'--update','--file',str(output/'GeoRestrictLiveProbe.jar')]
    for name in ['plugin.yml','bungee.yml']:update+=['-C',str(ROOT/'test/live/probe'),name]
    subprocess.run(update,check=True)

if __name__=='__main__':main()
