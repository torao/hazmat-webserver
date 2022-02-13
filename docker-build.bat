rem Windows Home のような Docker Toolbox を使っている場合は Docker Terminal から実行
@echo off
setlocal
set VERSION=1.2.7

call sbt universal:packageBin
move /y target\universal\hazmat-webserver-%VERSION%.zip docker\
docker build -t torao/hazmat-webserver:%VERSION% docker --build-arg VERSION=%VERSION%
docker tag torao/hazmat-webserver:%VERSION% torao/hazmat-webserver:latest
