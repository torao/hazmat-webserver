@echo off
setlocal
set VERSION=1.1.1

call sbt universal:packageBin
move /y target\universal\hazmat-webserver-%VERSION%.zip docker\
docker build -t torao/hazmat-webserver:%VERSION% docker
docker tag torao/hazmat-webserver:%VERSION% torao/hazmat-webserver:latest
