#!/bin/bash
VERSION=1.2.7
sbt universal:packageBin
mv target/universal/hazmat-webserver-$VERSION.zip docker/
docker build -t torao/hazmat-webserver:$VERSION --build-arg VERSION=$VERSION docker
docker tag torao/hazmat-webserver:$VERSION torao/hazmat-webserver:latest

