@echo off

call sbt universal:packageBin
move /y target\universal\hazmat-webserver-1.1.0.zip docker\
docker build -t torao/hazmat-webserver:1.1.0 docker
