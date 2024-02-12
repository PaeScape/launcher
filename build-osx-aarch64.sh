#!/bin/bash

set -e

JDK_VER="17"
JDK_BUILD="35"
PACKR_VERSION="paescape-0.1"

SIGNING_IDENTITY="Developer ID Application"

FILE="OpenJDK17-jdk_aarch64_mac_hotspot_${JDK_VER}_${JDK_BUILD}.tar.gz"
URL="https://github.com/adoptium/temurin17-binaries/releases/download/jdk-${JDK_VER}%2B${JDK_BUILD}/${FILE}"

if ! [ -f ${FILE} ] ; then
    curl -Lo ${FILE} ${URL}
fi

echo "910bb88543211c63298e5b49f7144ac4463f1d903926e94a89bfbf10163bbba1  ${FILE}" | shasum -c

# packr requires a "jdk" and pulls the jre from it - so we have to place it inside
# the jdk folder at jre/
if ! [ -d osx-aarch64-jdk ] ; then
    # Extract jdk archive
    tar zxf $FILE

    # Create jre
    jdk-${JDK_VER}+${JDK_BUILD}/Contents/Home/bin/jlink \
        --add-modules java.base,java.datatransfer,java.desktop,java.logging,java.management \
        --add-modules java.naming,java.net.http,java.sql,java.xml\
        --add-modules jdk.crypto.ec,jdk.httpserver,jdk.unsupported\
        --add-modules jdk.random,jdk.net,jdk.crypto.cryptoki,jdk.accessibility\
        --add-modules jdk.charsets,java.prefs,jdk.unsupported.desktop\
        --no-header-files --no-man-pages\
        --output osx-aarch64-jdk/jre

    # Cleanup
    rm -rf jdk-${JDK_VER}+${JDK_BUILD}
fi

if ! [ -f packr_${PACKR_VERSION}.jar ] ; then
    curl -Lo packr_${PACKR_VERSION}.jar \
        https://github.com/paescape/packr/releases/download/${PACKR_VERSION}/packr.jar
fi

echo "65c03c56173c5fc0965e70fab7576f6597f3af6e9477865536ecf872c383df8b  packr_${PACKR_VERSION}.jar" | shasum -c

java -jar packr_${PACKR_VERSION}.jar \
	packr/macos-aarch64-config.json

cp build/filtered-resources/Info.plist native-osx-aarch64/PaeScape.app/Contents

echo Setting world execute permissions on PaeScape
pushd native-osx-aarch64/PaeScape.app
chmod g+x,o+x Contents/MacOS/PaeScape
popd

codesign -f -s "${SIGNING_IDENTITY}" --entitlements osx/signing.entitlements --options runtime native-osx-aarch64/PaeScape.app || true

# create-dmg exits with an error code due to no code signing, but is still okay
create-dmg native-osx-aarch64/PaeScape.app native-osx-aarch64/ || true

mv native-osx-aarch64/PaeScape\ *.dmg native-osx-aarch64/PaeScape-aarch64.dmg

# Notarize app
if xcrun notarytool submit native-osx-aarch64/PaeScape-aarch64.dmg --wait --keychain-profile "AC_PASSWORD" ; then
    xcrun stapler staple native-osx-aarch64/PaeScape-aarch64.dmg
fi