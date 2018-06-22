FROM ubuntu:18.04
MAINTAINER TAKAMI Torao <koiroha@gmail.com>

RUN curl -sL https://deb.nodesource.com/setup_8.x | bash -

RUN apt-get update
RUN apt-get install -y unzip
RUN apt-get install -y curl

RUN apt-get install -y build-essential
RUN apt-get install -y openjdk-8-jdk
RUN apt-get install -y nodejs
RUN apt-get install -y python3-pip python3-dev \
      && cd /usr/local/bin \
      && ln -s /usr/bin/python3 python \
      && pip3 install --upgrade pip
RUN apt-get clean

RUN mkdir -p /opt/site
RUN mkdir -p /opt/webserver
COPY hazmat-webserver-1.1.0.zip /opt/webserver
RUN cd /opt/webserver && unzip hazmat-webserver-1.1.0.zip && mv hazmat-webserver-1.1.0/* . && rmdir hazmat-webserver-1.1.0 && rm hazmat-webserver-1.1.0.zip
WORKDIR /opt/webserver
ENTRYPOINT ["/opt/webserver/bin/hazmat-webserver"]

ENV JAVA_HOME /usr/lib/jvm/java-8-openjdk-amd64
ENV PATH $JAVA_HOME/bin:$PATH
ENV LANG C.UTF-8

EXPOSE 8089