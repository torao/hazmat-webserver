#!/bin/bash

SITE=/opt/site

bootstrap(){
  echo -e "\n"
  echo "=== BOOTSTRAP: `date` ==="
  if [ -f $SITE/requirements.txt ]
  then
    pip install -r $SITE/requirements.txt
  fi
  if [ -f $SITE/package.json ]
  then
    cd $SITE
    npm init
  fi
  if [ -f $SITE/scripts/bootstrap.sh ]
  then
    source $SITE/scripts/bootstrap.sh
  fi
}

# bootstrap logging
LOGDIR=$SITE/logs
LOGFILE=$LOGDIR/bootstrap-`date "+%Y%m%d"`.log
if [ -d $LOGDIR ]
then
  mkdir -p $LOGDIR
fi

bootstrap > $LOGFILE 2>&1 &
cd $SITE
/opt/webserver/bin/hazmat-webserver $*
