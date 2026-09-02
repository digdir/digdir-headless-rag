#!/bin/sh
# The deployment model is quite simple and old school.
#
# There is an SSH host $SERVER_HOST. We upload our current commit to
# this host and build the uberjar /home/app/app.jar on the server. If
# all went well we restart the systemd service app which will use the
# new uberjar.

set -e
echo -------------------------------------------------------- Packaging HEAD as tmp/transient_app.tar.gz
VERSION=$(git rev-parse HEAD)
UBERJAR_DEST=/home/app/app_jars/$VERSION.jar
SERVER_HOST=$1
echo -------------------------------------------------------- SERVER_HOST=$SERVER_HOST
# [ "$SERVER_HOST" != "app-prod-blue" ] && [ "$SERVER_HOST" != "app-test-blue" ] && echo SERVER_HOST must be app-prod-blue or app-test-blue && exit 1
mkdir -p tmp
git archive --format=tar.gz HEAD -o tmp/transient_app.tar.gz --add-virtual-file=version.txt:$VERSION

echo -------------------------------------------------------- Uploading tmp/transient_app.tar.gz to $SERVER_HOST...
scp tmp/transient_app.tar.gz $SERVER_HOST:/home/app/transient_app.tar.gz
ssh $SERVER_HOST -- chown app:app /home/app/transient_app.tar.gz

echo -------------------------------------------------------- Extracting transient_app.tar.gz and building uberjar...
ssh $SERVER_HOST -- su app -c -- "'rm -rf /home/app/transient_app \
&& mkdir -p /home/app/transient_app \
&& tar -xzf /home/app/transient_app.tar.gz -C /home/app/transient_app \
&& cp /home/app/mise.toml /home/app/transient_app/mise.toml \
&& cd /home/app/transient_app \
&& bash build.bash \
&& cp /home/app/transient_app/target/$VERSION.jar $UBERJAR_DEST'"

echo --------------------------------------------------------
echo Done -- the uberjar successfully built on the server and is stored at $SERVER_HOST:$UBERJAR_DEST
echo You may now point the production uberjar /home/app/app.jar to $UBERJAR_DEST like so
echo 
echo      ssh $SERVER_HOST -- \"ln -sf $UBERJAR_DEST /home/app/app.jar \&\& chown app:app /home/app/app.jar\"
echo
echo and restart the production service
echo 
echo      ssh $SERVER_HOST -- systemctl restart app
echo
echo or all in one
echo 
echo      ssh $SERVER_HOST -- \"ln -sf $UBERJAR_DEST /home/app/app.jar \&\& chown app:app /home/app/app.jar \&\& systemctl restart app\" '&&' ssh $SERVER_HOST -- journalctl -fu app  
echo
echo the final command finishes off by displaying the logs.
