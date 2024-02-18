#!/bin/bash

set -e

JDK_VER="11.0.14.1"
JDK_BUILD="1"
PACKR_VERSION="4.0.0"

SIGNING_IDENTITY="Developer ID Application"

if ! [ -f OpenJDK11U-jre_x64_mac_hotspot_${JDK_VER}_${JDK_BUILD}.tar.gz ] ; then
    curl -Lo OpenJDK11U-jre_x64_mac_hotspot_${JDK_VER}_${JDK_BUILD}.tar.gz \
        https://github.com/adoptium/temurin11-binaries/releases/download/jdk-${JDK_VER}%2B${JDK_BUILD}/OpenJDK11U-jre_x64_mac_hotspot_${JDK_VER}_${JDK_BUILD}.tar.gz
fi

echo "1b2f792ad05af9dba876db962c189527e645b48f50ceb842b4e39169de553303  OpenJDK11U-jre_x64_mac_hotspot_${JDK_VER}_${JDK_BUILD}.tar.gz" | shasum -c

# packr requires a "jdk" and pulls the jre from it - so we have to place it inside
# the jdk folder at jre/
if ! [ -d osx-jdk ] ; then
    tar zxf OpenJDK11U-jre_x64_mac_hotspot_${JDK_VER}_${JDK_BUILD}.tar.gz
    mkdir osx-jdk
    mv jdk-${JDK_VER}+${JDK_BUILD}-jre osx-jdk/jre

    pushd osx-jdk/jre
    # Move JRE out of Contents/Home/
    mv Contents/Home/* .
    # Remove unused leftover folders
    rm -rf Contents
    popd
fi

if ! [ -f packr-all-${PACKR_VERSION}.jar ] ; then
    curl -Lo packr-all-${PACKR_VERSION}.jar \
        https://github.com/libgdx/packr/releases/download/${PACKR_VERSION}/packr-all-${PACKR_VERSION}.jar
fi

echo "e2047f5b098bd5ca05150a530f3ada3a7f07bd846be730b92378180bdd3d8be2  packr-all-${PACKR_VERSION}.jar" | sha256sum -c

java -jar packr-all-${PACKR_VERSION}.jar \
    packr/macos-x64-config.json

cp build/filtered-resources/Info.plist native-osx/PaeScape.app/Contents

echo Setting world execute permissions on PaeScape
pushd native-osx/PaeScape.app
chmod g+x,o+x Contents/MacOS/PaeScape
popd

codesign -f -s "${SIGNING_IDENTITY}" --entitlements osx/signing.entitlements --options runtime native-osx/PaeScape.app || true

# create-dmg exits with an error code due to no code signing, but is still okay
# note we use Adam-/create-dmg as upstream does not support UDBZ
create-dmg --format UDBZ native-osx/PaeScape.app native-osx/ || true

mv native-osx/PaeScape\ *.dmg native-osx/PaeScape-x64.dmg

if ! hdiutil imageinfo native-osx/PaeScape-x64.dmg | grep -q "Format: UDBZ" ; then
    echo "Format of resulting dmg was not UDBZ, make sure your create-dmg has support for --format"
    exit 1
fi

# Notarize app
if xcrun notarytool submit native-osx/PaeScape-x64.dmg --wait --keychain-profile "AC_PASSWORD" ; then
    xcrun stapler staple native-osx/PaeScape-x64.dmg
fi
