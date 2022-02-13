FROM ubuntu:20.04
MAINTAINER TAKAMI Torao <koiroha@gmail.com>
ARG VERSION

# tzdata: to avoid "the time zones in which they are located."
RUN apt update && apt upgrade -y \
 && apt install -y tzdata \
 && apt install -y unzip curl build-essential openjdk-11-jdk graphviz \
 && apt clean

# Python 3
RUN apt install -y  python3-pip python3-dev \
 && cd /usr/local/bin \
 && ln -s /usr/bin/python3 /usr/local/bin/python \
 && pip3 install --upgrade pip

# Node.js
RUN curl -sL https://deb.nodesource.com/setup_lts.x | bash - \
 && apt install -y nodejs \
 && npm install -g yarn \
 && yarn add mermaid.cli

WORKDIR /opt/webserver
COPY hazmat-webserver-$VERSION.zip ./
RUN unzip hazmat-webserver-$VERSION.zip \
 && mv hazmat-webserver-$VERSION/* . \
 && rmdir hazmat-webserver-$VERSION \
 && rm hazmat-webserver-$VERSION.zip
COPY bootstrap.sh ./bin/
RUN chmod 755 ./bin/bootstrap.sh

ENTRYPOINT ["/opt/webserver/bin/bootstrap.sh"]

RUN mkdir -p /opt/site
CMD ["/opt/site"]

ENV JAVA_HOME /usr/lib/jvm/java-11-openjdk-amd64
ENV PATH $JAVA_HOME/bin:$PATH
ENV LANG C.UTF-8

EXPOSE 8089
