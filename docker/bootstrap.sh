#!/bin/bash

if [ -f /opt/site/requirements.txt ]
  pip install -r /opt/site/requirements.txt &
fi

if [ -f /opt/site/package.json ]
  cd /opt/site && npm init &
fi

cd /opt/site
/opt/webserver/bin/hazmat-webserver $*
