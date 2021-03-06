#!/usr/bin/env bash

#
#  Copyright 2018, Infor Inc.
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.
#

set -e

iterations=1

cloudstore_jars=$CLOUDSTORE_HOME/lib/java

# for local testing
test_jars=$PWD/build/jars

cp="$cloudstore_jars/junit-4.8.2.jar:$test_jars/cloudstore-test.jar:$cloudstore_jars/cloudstore-test.jar"

for i in $(seq 1 $iterations);
do
  echo "------------- Iteration $i ------------------"
  java -cp $cp com.logicblox.cloudstore.TestRunner $*
done
