FROM ubuntu:18.04
MAINTAINER TAKAMI Torao <koiroha@gmail.com>
ARG VERSION

RUN apt-get update \
&&  apt-get upgrade -y \
&&  apt-get install -y unzip curl build-essential openjdk-8-jdk nodejs python3-pip python3-dev \
&&  apt-get clean

RUN cd /usr/local/bin \
&&  ln -s /usr/bin/python3 python \
&&  pip3 install --upgrade pip \
&&  curl -sL https://deb.nodesource.com/setup_8.x | bash -

WORKDIR /opt/webserver
COPY hazmat-webserver-$VERSION.zip /opt/webserver
COPY bootstrap.sh /opt/webserver/bin
RUN chmod 755 /opt/webserver/bin/bootstrap.sh

RUN mkdir -p /opt/site
RUN mkdir -p /opt/webserver
RUN cd /opt/webserver \
&&  unzip hazmat-webserver-$VERSION.zip \
&&  mv hazmat-webserver-$VERSION/* . \
&&  rmdir hazmat-webserver-$VERSION \
&&  rm hazmat-webserver-$VERSION.zip

ENTRYPOINT ["/opt/webserver/bin/bootstrap.sh"]

CMD ["/opt/site"]

ENV JAVA_HOME /usr/lib/jvm/java-8-openjdk-amd64
ENV PATH $JAVA_HOME/bin:$PATH
ENV LANG C.UTF-8

EXPOSE 8089
