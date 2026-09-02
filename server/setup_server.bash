#!/bin/bash
echo NB: ONLY RUN THIS ON A FRESH SERVER MACHINE
echo 'do something like: $ scp setup_server.bash app:/tmp/setup_server.bash && ssh app -- bash /tmp/setup_server.bash'
echo This script does some of the job of setting up a vm for running an app.
echo It may be destructive so only run it on fresh machines
echo Type run to continue

read ANSWER && [ "$ANSWER" = run ] || exit

echo -------------- Apt updating and upgrading
apt update -y && apt upgrade -y

echo -------------- Installing misc utilities
apt install -y tree

echo -------------- Installing mise
sudo apt install -y gpg sudo wget curl
sudo install -dm 755 /etc/apt/keyrings
wget -qO - https://mise.jdx.dev/gpg-key.pub | gpg --dearmor | sudo tee /etc/apt/keyrings/mise-archive-keyring.gpg 1> /dev/null
echo "deb [signed-by=/etc/apt/keyrings/mise-archive-keyring.gpg arch=amd64] https://mise.jdx.dev/deb stable main" | sudo tee /etc/apt/sources.list.d/mise.list
sudo apt update -y
sudo apt install -y mise

echo -------------- Create app user
adduser app --gecos '' --disabled-password

echo -------------- Create app systemd service
cat > /etc/systemd/system/app.service << SERVICE
[Unit]
After=network.target

[Service]
Type=simple
User=app
ExecStart=/usr/bin/mise run uberjar
WorkingDirectory=/home/app

[Install]
WantedBy=multi-user.target
SERVICE

echo -------------- Initializing /home/app
su app << AS_APP
cd ~
cat > mise.toml << MISETOML
[env]
# Define environment variables here

[tools]
java = { version = "openjdk-24" }
node = "22"
python = "3.12.8"
clojure = "1.12"

[tasks.uberjar]
run = "java -cp app.jar clojure.main --main prod"
MISETOML
mise trust
mise install
mise exec -- clojure -M -e ':inialized-clojure'
mkdir transient_app -p
mkdir app_jars -p
cd transient_app
mise trust
AS_APP
