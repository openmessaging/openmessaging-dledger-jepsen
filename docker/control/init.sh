#!/bin/sh

# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

: "${SSH_PRIVATE_KEY?SSH_PRIVATE_KEY is empty, please use up.sh}"
: "${SSH_PUBLIC_KEY?SSH_PUBLIC_KEY is empty, please use up.sh}"

if [ ! -f ~/.ssh/known_hosts ]; then
    mkdir -m 700 ~/.ssh
    echo $SSH_PRIVATE_KEY | perl -p -e 's/↩/\n/g' > ~/.ssh/id_rsa
    chmod 600 ~/.ssh/id_rsa
    echo $SSH_PUBLIC_KEY > ~/.ssh/id_rsa.pub
    echo > ~/.ssh/known_hosts
    for f in $(seq 1 5);do
	ssh-keyscan -t rsa n$f >> ~/.ssh/known_hosts
    done
fi

# TODO: assert that SSH_PRIVATE_KEY==~/.ssh/id_rsa

cat <<EOF 
Welcome to Jepsen on Docker
===========================

Please run \`docker exec -it jepsen-control bash\` in another terminal to proceed.
EOF

# hack for keep this container running
tail -f /dev/null
