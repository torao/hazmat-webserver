#!/bin/bash
VERSION=1.2.7
sbt universal:packageBin
mv target/universal/hazmat-webserver-$VERSION.zip docker/
if [ ! -e docker/hazmat-webserver-$VERSION.zip ]
then
  echo "ERROR: $VERSION is not built. Isn't it `ls target/universal/hazmat-webserver-*.zip`?"
  exit 1
fi
docker build -t torao/hazmat-webserver:$VERSION --build-arg VERSION=$VERSION docker
docker tag torao/hazmat-webserver:$VERSION torao/hazmat-webserver:latest

