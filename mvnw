#!/bin/sh
set -eu

MAVEN_VERSION="3.9.11"
MAVEN_CACHE="${HOME}/.m2/wrapper/dists/apache-maven-${MAVEN_VERSION}"
MAVEN_BIN="${MAVEN_CACHE}/apache-maven-${MAVEN_VERSION}/bin/mvn"

if [ ! -x "${MAVEN_BIN}" ]; then
    mkdir -p "${MAVEN_CACHE}"
    ARCHIVE="${MAVEN_CACHE}/apache-maven-${MAVEN_VERSION}-bin.tar.gz"
    curl --fail --location --silent --show-error \
        "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/${MAVEN_VERSION}/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
        --output "${ARCHIVE}"
    tar -xzf "${ARCHIVE}" -C "${MAVEN_CACHE}"
    rm "${ARCHIVE}"
fi

exec "${MAVEN_BIN}" "$@"