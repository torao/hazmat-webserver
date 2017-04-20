#!/bin/bash
contents=../hazmat-contents
abspath=$(cd $(dirname $contents) && pwd)/$(basename $contents)
docker run -d -p 8088:8088 -v "$abspath:/opt/hazmat-contents" --name hazmat-webserver at.hazm/webserver:latest /opt/hazmat-contents
