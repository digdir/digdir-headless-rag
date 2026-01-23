#!/bin/bash
set -e
eval "$(mise activate bash)"
clojure -A:build:prod -M -e ::ok
clojure -X:prod:build uberjar
