# DLedger-jepsen-test

A [jepsen](https://github.com/jepsen-io/jepsen) test for [dledger](https://github.com/openmessaging/openmessaging-storage-dledger).  
 
## What is being tested?

DLedger is a raft-based java library for building high-available, high-durable, strong-consistent commitlog. The test run concurrent operations to dledger  from different nodes in a dledger cluster and checks that the operations preserve the consistency properties defined in the test. During the test, various nemesis can be added to interfere with the operations.

Currently, checker is  **Set**. Given a set of concurrent unique appends to dledger commitlog followed by a final read, verifies that every successfully appended element is present in the read, and that the read contains only elements for which an append was attempted.

## Usage  

1. Prepare **one** control node and **five** db nodes and ensure that the control node can use SSH to  log into a bunch of db nodes. 
2. Install clojure, jepsen and [clojure-control](https://github.com/killme2008/clojure-control) on the control node.
3. Edit *nodes* , *control.clj* and *src/dledger_jepsen_test/core.clj* files to set hostname, user name and store path. Those values are hardcoded in the program by now.
4. Deploy the dledger server with clojure-control on the control node:
```
control run dledger build
control run dledger deploy
``` 
5. Run the test
```
lein run test --nodes-file ./nodes
```
&#160;&#160;&#160;or execute `./run_test.sh` 

## Quick Start (Docker)

In one shell, we start the five nodes and the controller using docker compose.
```shell
cd docker
./up.sh --dev
```
In another shell, use `docker exec -it chaos-control bash` to enter the controller, then

```shell
control run dledger build
control run dledger deploy
./run_test.sh
```

### Options

See `lein run test --help` for  options. 

**nemesis**

`--nemsis NAME`, what nemesis should we run? The default value is partition-random-halves. You can also run the following nemesis:

- partition-random-node: isolates a single node from the rest of the network.

![enter image description here](http://assets.processon.com/chart_image/5d05fd1ce4b00d2a1ac788c7.png)

- partition-random-halves: cuts the network into randomly chosen halves.
 
![enter image description here](http://assets.processon.com/chart_image/5d05fb65e4b0cbb88a5f1815.png)

- kill-random-processes: kill random processes and restart them.

![enter image description here](http://assets.processon.com/chart_image/5d0c4523e4b0d4ba353ee2dd.png)

- crash-random-nodes: crash random nodes and restart them (kill processes and drop caches).

![enter image description here](http://assets.processon.com/chart_image/5d05feafe4b08ceab31d121a.png)

- hammer-time: pause random nodes with SIGSTOP/SIGCONT.

![enter image description here](http://assets.processon.com/chart_image/5d06012de4b091a8f244ba50.png)

- bridge: a grudge which cuts the network in half, but preserves a node in the middle which has uninterrupted bidirectional connectivity to both components.

![enter image description here](http://assets.processon.com/chart_image/5d06033de4b0d4295989d335.png)

- partition-majorities-ring: every node can see a majority, but no node sees the _same_ majority as any other. Randomly orders nodes into a ring.

![enter image description here](http://assets.processon.com/chart_image/5d0604d3e4b0591fc0e34259.png)

**Other options:**

`--rate HZ`, approximate number of requests per second, per thread, the default value is 10.

`--concurrency NUMBER`, the number of workers (clients), the default value is 5.

`--time-limit TIME`, test time limit, the default value is 60.

`--interval TIME`, nemesis interval, the default value is 15. 

`--test-count TIMES`, times to run test, the default value is 1. 




