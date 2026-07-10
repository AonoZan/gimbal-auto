#!/bin/bash

set -e

./gradlew clean :app:compileDebugKotlin
./gradlew installDebug