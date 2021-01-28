#!/bin/sh

export CATALINA_HOME="$(dirname "$0")/server/apache-tomcat-9.0.36"

/bin/sh "$(dirname "$0")/server/apache-tomcat-9.0.36/bin/shutdown.sh"
