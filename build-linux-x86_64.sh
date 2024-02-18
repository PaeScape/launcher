#!/bin/bash

set -e

JDK_VER="11.0.8"
JDK_BUILD="10"
PACKR_VERSION="4.0.0"
APPIMAGE_VERSION="12"

umask 022

if ! [ -f OpenJDK11U-jre_x64_linux_hotspot_${JDK_VER}_${JDK_BUILD}.tar.gz ] ; then
    curl -Lo OpenJDK11U-jre_x64_linux_hotspot_${JDK_VER}_${JDK_BUILD}.tar.gz \
        https://github.com/AdoptOpenJDK/openjdk11-binaries/releases/download/jdk-${JDK_VER}%2B${JDK_BUILD}/OpenJDK11U-jre_x64_linux_hotspot_${JDK_VER}_${JDK_BUILD}.tar.gz
fi

echo "98615b1b369509965a612232622d39b5cefe117d6189179cbad4dcef2ee2f4e1 OpenJDK11U-jre_x64_linux_hotspot_${JDK_VER}_${JDK_BUILD}.tar.gz" | sha256sum -c

# packr requires a "jdk" and pulls the jre from it - so we have to place it inside
# the jdk folder at jre/
if ! [ -d linux-jdk ] ; then
    tar zxf OpenJDK11U-jre_x64_linux_hotspot_${JDK_VER}_${JDK_BUILD}.tar.gz
    mkdir linux-jdk
    mv jdk-$JDK_VER+$JDK_BUILD-jre linux-jdk/jre
fi

if ! [ -f packr-all-${PACKR_VERSION}.jar ] ; then
    curl -Lo packr-all-${PACKR_VERSION}.jar \
        https://github.com/libgdx/packr/releases/download/${PACKR_VERSION}/packr-all-${PACKR_VERSION}.jar
fi

echo "e2047f5b098bd5ca05150a530f3ada3a7f07bd846be730b92378180bdd3d8be2  packr-all-${PACKR_VERSION}.jar" | sha256sum -c

# Note: Host umask may have checked out this directory with g/o permissions blank
chmod -R u=rwX,go=rX appimage
# ...ditto for the build process
chmod 644 build/libs/PaeScapeLauncher.jar

java -jar packr-all-${PACKR_VERSION}.jar \
    packr/linux-x64-config.json

pushd native-linux-x86_64/PaeScape.AppDir
mkdir -p jre/lib/amd64/server/
ln -s ../../server/libjvm.so jre/lib/amd64/server/ # packr looks for libjvm at this hardcoded path

# Symlink AppRun -> PaeScape
ln -s PaeScape AppRun

# Ensure PaeScape is executable to all users
chmod 755 PaeScape
popd

if ! [ -f appimagetool-x86_64.AppImage ] ; then
    curl -Lo appimagetool-x86_64.AppImage \
        https://github.com/AppImage/AppImageKit/releases/download/$APPIMAGE_VERSION/appimagetool-x86_64.AppImage
    chmod +x appimagetool-x86_64.AppImage
fi

echo "d918b4df547b388ef253f3c9e7f6529ca81a885395c31f619d9aaf7030499a13  appimagetool-x86_64.AppImage" | sha256sum -c

./appimagetool-x86_64.AppImage \
	native-linux-x86_64/PaeScape.AppDir/ \
	native-linux-x86_64/PaeScape.AppImage

cp native-linux-x86_64/PaeScape.AppImage .
