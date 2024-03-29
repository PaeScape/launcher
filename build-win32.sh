#!/bin/bash

set -e

JDK_VER="11.0.8"
JDK_BUILD="10"
JDK_BUILD_SHORT="10"
PACKR_VERSION="paescape-0.1"

if ! [ -f OpenJDK11U-jre_x86-32_windows_hotspot_${JDK_VER}_${JDK_BUILD}.zip ] ; then
    curl -Lo OpenJDK11U-jre_x86-32_windows_hotspot_${JDK_VER}_${JDK_BUILD}.zip \
        https://github.com/AdoptOpenJDK/openjdk11-binaries/releases/download/jdk-${JDK_VER}%2B${JDK_BUILD}/OpenJDK11U-jre_x86-32_windows_hotspot_${JDK_VER}_${JDK_BUILD_SHORT}.zip
fi

echo "00e0eb7112a4cdbaae663110e4c7af6377d2fa01f69c20222790293b4f427f26 OpenJDK11U-jre_x86-32_windows_hotspot_${JDK_VER}_${JDK_BUILD}.zip" | sha256sum -c

# packr requires a "jdk" and pulls the jre from it - so we have to place it inside
# the jdk folder at jre/
if ! [ -d win32-jdk ] ; then
    unzip OpenJDK11U-jre_x86-32_windows_hotspot_${JDK_VER}_${JDK_BUILD}.zip
    mkdir win32-jdk
    mv jdk-$JDK_VER+$JDK_BUILD_SHORT-jre win32-jdk/jre
fi

if ! [ -f packr_${PACKR_VERSION}.jar ] ; then
    curl -Lo packr_${PACKR_VERSION}.jar \
        https://github.com/paescape/packr/releases/download/${PACKR_VERSION}/packr.jar
fi

echo "65c03c56173c5fc0965e70fab7576f6597f3af6e9477865536ecf872c383df8b  packr_${PACKR_VERSION}.jar" | sha256sum -c

java -jar packr_${PACKR_VERSION}.jar \
    packr/win-x86-config.json

# modify packr exe manifest to enable Windows dpi scaling
"C:/Program Files (x86)/Resource Hacker/ResourceHacker.exe" \
    -open native-win32/PaeScape.exe \
    -save native-win32/PaeScape.exe \
    -action addoverwrite \
    -res packr/paescape.manifest \
    -mask MANIFEST,1,

# packr on Windows doesn't support icons, so we use resourcehacker to include it
"C:/Program Files (x86)/Resource Hacker/ResourceHacker.exe" \
    -open native-win32/PaeScape.exe \
    -save native-win32/PaeScape.exe \
    -action add \
    -res paescape.ico \
    -mask ICONGROUP,MAINICON,

# We use the filtered iss file
"C:/Program Files (x86)/Inno Setup 6/ISCC.exe" build/filtered-resources/paescape32.iss