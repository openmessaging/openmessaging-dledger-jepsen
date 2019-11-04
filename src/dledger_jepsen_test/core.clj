; Licensed to the Apache Software Foundation (ASF) under one or more
; contributor license agreements.  See the NOTICE file distributed with
; this work for additional information regarding copyright ownership.
; The ASF licenses this file to You under the Apache License, Version 2.0
; (the "License"); you may not use this file except in compliance with
; the License.  You may obtain a copy of the License at
;
;     http://www.apache.org/licenses/LICENSE-2.0
;
; Unless required by applicable law or agreed to in writing, software
; distributed under the License is distributed on an "AS IS" BASIS,
; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
; See the License for the specific language governing permissions and
; limitations under the License.

(ns dledger-jepsen-test.core
  (:require [clojure.tools.logging :refer :all]
            [clojure.string :as cstr]
            [jepsen [cli :as cli]
             [control :as c]
             [db :as db]
             [tests :as tests]
             [checker :as checker]
             [client :as client]
             [generator :as gen]
             [nemesis :as nemesis]]
            [jepsen.checker.timeline :as timeline]
            [jepsen.control.util :as cu]
            [jepsen.os :as os])
  (:import [io.openmessaging.dledger.jepsenclient JepsenSetClient]))

(defonce dledger-path "/root/dledger-jepsen")
(defonce dledger-port 20911)
(defonce dledger-bin "java")
(defonce dledger-start "startup.sh")
(defonce dledger-stop "stop.sh")
(defonce dledger-stop-dropcaches "stop_dropcaches.sh")
(defonce dledger-data-path "/tmp/dledgerstore")
(defonce dledger-log-path "logs/dledger")

(defn peer-id [node]
  (str node))

(defn peer-str [node]
  (str (peer-id node) "-" node ":" dledger-port))

(defn peers
  "Constructs an initial cluster string for a test, like
  \"n0-host1:20911;n1-host2:20911,...\""
  [test]
  (->> (:nodes test)
       (map (fn [node]
              (peer-str node)))
       (cstr/join ";")))

(defn start! [test node]
  (info "Start DLedgerServer" node)
  (c/cd dledger-path
        (c/exec :sh
                dledger-start
                "--group jepsen"
                "--id"
                (peer-id node)
                "--peers"
                (peers test))))

(defn stop! [node]
  (info "Stop DLedgerServer" node)
  (c/cd dledger-path
        (c/exec :sh
                dledger-stop)))

(defn stop_dropcaches! [node]
  (info "Stop DLedgerServer and drop caches" node)
  (c/cd dledger-path
        (c/exec :sh
                dledger-stop)))

(defn- create-client [test]
  (doto (JepsenSetClient. "jepsen" (peers test))
    (.startup)))

(defn- start-client [client]
  (-> client
      :conn
      (.startup)))

(defn- shutdown-client [client]
  (-> client
      :conn
      (.shutdown)))


(defn- add
  "add element to dledger"
  [client value]
  (-> client
      :conn
      (.add (pr-str value))))

(defn- read-all
  "read set from dledger"
  [client]
  (-> client
      :conn
      (.read)))


(defn db
  "DLedger db."
  []
  (reify db/DB
    (setup! [_ test node]
      (start! test node)
      (Thread/sleep 20000)
      )

    (teardown! [_ test node]
      (stop! node)
      (Thread/sleep 20000)
      (c/exec :rm
              :-rf
              dledger-data-path))))

(defrecord Client [conn]
  client/Client
  (open! [this test node]
    (-> this
        (assoc :node node)
        (assoc :conn (create-client test))))

  (setup! [this test])

  (invoke! [this test op]
    (try
      (case (:f op)
            :add (let [res,(add this (:value op))]
                   (cond
                    (= res 200) (assoc op :type :ok)
                    (= res 502) (assoc op :type :info :error "wait qurom ack timeout")
                    (= res 1001) (assoc op :type :info :error "client append timeout")
                    (= res 1002) (assoc op :type :fail :error "client connect refuse")
                    (= res 1003) (assoc op :type :fail :error "client send req fail")
                    :else (assoc op :type :fail :error (str "error code: " res))))

            :read (assoc op
                         :type :ok
                         :value (read-string (read-all this)))
            )

      (catch Exception e
        (assoc op :type :info :error e)
        )))

  (teardown! [this test])

  (close! [this test]
    (shutdown-client this)))

(defn mostly-small-nonempty-subset
  "Returns a subset of the given collection, with a logarithmically decreasing
  probability of selecting more elements. Always selects at least one element.

      (->> #(mostly-small-nonempty-subset [1 2 3 4 5])
           repeatedly
           (map count)
           (take 10000)
           frequencies
           sort)
      ; => ([1 3824] [2 2340] [3 1595] [4 1266] [5 975])"
  [xs]
  (-> xs
      count
      inc
      Math/log
      rand
      Math/exp
      long
      (take (shuffle xs))))

(def crash-random-nodes
  "A nemesis that crashes a random subset of nodes."
  (nemesis/node-start-stopper
    mostly-small-nonempty-subset
    (fn start [test node]
      (info "Crash start" node)
      (stop_dropcaches! node)
      [:killed node])
    (fn stop [test node]
      (info "Crash stop" node)
      (start! test node)
      [:restarted node])))

(def kill-random-processes
  "A nemesis that kills a random subset of processes."
  (nemesis/node-start-stopper
    mostly-small-nonempty-subset
    (fn start [test node]
      (info "Kill start" node)
      (stop! node)
      [:killed node])
    (fn stop [test node]
      (info "Kill stop" node)
      (start! test node)
      [:restarted node])))

(def nemesis-map
  "A map of nemesis names to functions that construct nemesis, given opts."
  {"partition-random-halves"           (nemesis/partition-random-halves)
   "partition-random-node"             (nemesis/partition-random-node)
   "kill-random-processes"             kill-random-processes
   "crash-random-nodes"                crash-random-nodes
   "hammer-time"                       (nemesis/hammer-time dledger-bin)
   "bridge"                            (nemesis/partitioner (comp nemesis/bridge shuffle))
   "partition-majorities-ring"         (nemesis/partition-majorities-ring)})

(defn- parse-int [s]
  (Integer/parseInt s))

(def cli-opts
  "Additional command line options."
  [["-r" "--rate HZ" "Approximate number of requests per second, per thread."
    :default  10
    :parse-fn read-string
    :validate [#(and (number? %) (pos? %)) "Must be a positive number"]]
   [nil "--nemesis NAME" "What nemesis should we run?"
    :default  "partition-random-halves"
    :validate [nemesis-map (cli/one-of nemesis-map)]]
   ["-i" "--interval TIME" "How long is the nemesis interval?"
    :default  15
    :parse-fn parse-int
    :validate [#(and (number? %) (pos? %)) "Must be a positive number"]]]
  )


(defn dledger-test
  "Given an options map from the command line runner (e.g. :nodes, :ssh,
  :concurrency ...), constructs a test map."
  [opts]
  (let [nemesis (get nemesis-map (:nemesis opts))]
    (merge tests/noop-test
           opts
           {:name          "dledger"
            :os            os/noop
            :db            (db)
            :client        (Client. nil)
            :nemesis       nemesis
            :checker       (checker/compose
                             {:perf (checker/perf)
                              :set (checker/set)
                              :timeline (timeline/html)})
            :generator     (gen/phases
                             (->> (range)
                                (map (fn [x] {:type :invoke, :f :add, :value x}))
                                (gen/seq)
                                (gen/stagger (/ (:rate opts)))
                                (gen/nemesis
                                  (gen/seq(cycle [(gen/sleep (:interval opts))
                                                  {:type :info, :f :start}
                                                  (gen/sleep (:interval opts))
                                                  {:type :info, :f :stop}])))
                                (gen/time-limit (:time-limit opts)))
                             (gen/log "Healing cluster")
                             (gen/nemesis (gen/once {:type :info, :f :stop}))
                             (gen/log "Waiting for recovery")
                             (gen/sleep 10)
                             (gen/clients (gen/once {:type :invoke, :f :read, :value nil})))})))

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
  browsing results."
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn dledger-test
                                         :opt-spec cli-opts})
                   (cli/serve-cmd))
            args))


