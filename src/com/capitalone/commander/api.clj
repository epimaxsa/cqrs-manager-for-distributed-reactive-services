;
; Copyright 2016 Capital One Services, LLC
;
; Licensed under the Apache License, Version 2.0 (the "License")
; you may not use this file except in compliance with the License.
; You may obtain a copy of the License at
;
;     http://www.apache.org/licenses/LICENSE-2.0
;
; Unless required by applicable law or agreed to in writing, software
; distributed under the License is distributed on an "AS IS" BASIS,
; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
; See the License for the specific language governing permissions and limitations under the License.
;
; SPDX-Copyright: Copyright (c) Capital One Services, LLC
; SPDX-License-Identifier: Apache-2.0
;

(ns com.capitalone.commander.api
  (:require [clojure.spec.alpha :as s]
            [clojure.core.async :as a]
            [com.stuartsierra.component :as c]
            [io.pedestal.log :as log]
            [clj-uuid :as uuid]
            [com.capitalone.commander :as commander]
            [com.capitalone.commander.database :as d]
            [com.capitalone.commander.kafka :as k])
  (:import [org.apache.kafka.clients.consumer Consumer]))

(set! *warn-on-reflection* true)

(defprotocol CommandService
  (-create-command [this command-params]
    "Creates a command from the command-params and records to the
    Log.  Returns the created command")
  (-create-command-sync [this command-params sync-timeout-ms]
    "Creates a command from the command-params and records to the Log.
    Returns the newly created command, with a :children key whose
    value is a vector containing the completion event id if
    successful.  If ")
  (-list-commands [this offset limit]
    "Returns a map of :commands, :limit, :offset, and :total,
    where :commands is `limit` indexed commands, starting at `offset`.
    If limit is 0, returns all indexed commands starting with
    offset. :total is the total count of all commands.")
  (-get-command-by-id [this id]
    "Returns the indexed command with the given id, or nil if none
    found.")
  (-commands-ch [this ch]
    "Returns ch, the given core.async channel that will convey all
    commands (from time of call onward)."))

(defprotocol CommandValidator
  (-validate-command-params [this command-params]
    "Returns true if valid, map of errors otherwise"))

(defprotocol EventService
  (-list-events [this offset limit]
    "Returns a map of :events, :limit, :offset, and :total,
    where :events is `limit` indexed events, starting at `offset`.
    If limit is 0, returns all indexed events starting with
    offset. :total is the total count of all events.")
  (-get-event-by-id [this id]
    "Returns the indexed event with the given id, or nil if none
    found.")
  (-events-ch [this ch]
    "Returns ch, the given core.async channel that will convey all
    events (from time of call onward)."))

(defn create-command
  "Creates a command by recording to the Log. If sync? is false (the
  default if not given), writes to the Log and returns immediately.
  If sync? is true, writes to the Log and waits for the command's
  corresponding completion event to arrive on the Log before
  returning. Returns the newly created command in either case."
  ([api command-params]
   (create-command api command-params false))
  ([api command-params sync?]
   (log/info ::create-command [api command-params sync?])
   (if sync?
     (-create-command-sync api command-params (:sync-timeout-ms api))
     (-create-command api command-params))))

(s/def ::CommandService (partial satisfies? CommandService))

(s/fdef create-command
        :args (s/cat :api ::CommandService
                     :command-params ::commander/command-params
                     :sync? (s/? (s/nilable boolean?)))
        :ret ::commander/command
        :fn (s/and #(= (-> % :ret :action) (-> % :args :command-params :action))
                   #(= (-> % :ret :data)   (-> % :args :command-params :data))))

(defn list-commands
  "Returns a map of :commands, :limit, :offset, and :total,
   where :commands is `limit` indexed commands, starting at `offset`.
   If limit is 0, returns all indexed commands starting with
   offset. :total is the total count of all commands."
  ([api] (list-commands api 0))
  ([api offset] (list-commands api offset 0))
  ([api offset limit]
   (log/info ::list-commands [api offset limit])
   (-list-commands api (or offset 0) (or limit 0))))

(s/def ::commands (s/every ::commander/command))
(s/def ::total (s/int-in 0 Long/MAX_VALUE))

(s/fdef list-commands
        :args (s/cat :api ::CommandService
                     :offset (s/? (s/nilable (s/int-in 0 Long/MAX_VALUE)))
                     :limit (s/? (s/nilable (s/int-in 0 Long/MAX_VALUE))))
        :ret (s/keys :req-un [::commands ::commander/limit ::commander/offset ::total])
        :fn #(let [limit (-> % :args :limit)]
               (if (pos? limit)
                 (= (-> % :ret count) limit)
                 true)))

(defn get-command-by-id
  "Returns the indexed command with the given id, or nil if none
  found."
  [api id]
  (log/info ::get-command-by-id [api id])
  (-get-command-by-id api id))

(s/fdef get-command-by-id
        :args (s/cat :api ::CommandService
                     :id  ::commander/id)
        :ret ::commander/command)

(defn command-map
  [{:keys [key value topic partition offset timestamp] :as command}]
  (log/debug ::command-map [command])
  (let [{:keys [action data]} value]
    {:id        key
     :action    action
     :data      data
     :timestamp timestamp
     :topic     topic
     :partition partition
     :offset    offset}))

(defn commands-ch
  "Returns a core.async channel (ch if given, a sliding-buffer channel
  of size 10 otherwise) that will convey all commands arriving from
  the time of the call onward."
  ([api]
   (commands-ch api (a/chan (a/sliding-buffer 10))))
  ([api ch]
   (log/info ::commands-ch [api ch])
   (-commands-ch api ch)
   ch))

(defn validate-command-params
  "Returns true if valid, a map of errors otherwise."
  [api command-params]
  (-validate-command-params api command-params))

(s/fdef validate-command-params
        :args (s/cat :api ::CommandService
                     :command-params ::commander/command-params)
        :ret (s/or :valid true?
                   :invalid (s/keys)))

(s/def ::EventService (partial satisfies? EventService))

(defn list-events
  "Returns a map of :events, :limit, :offset, and :total,
   where :events is `limit` indexed events, starting at `offset`.
   If limit is 0, returns all indexed events starting with
   offset. :total is the total count of all events."
  ([api] (list-events api 0))
  ([api offset] (list-events api offset 0))
  ([api offset limit]
   (log/info ::list-events [api offset limit])
   (-list-events api (or offset 0) (or limit 0))))

(s/def ::events (s/every ::commander/event))
(s/fdef list-events
        :args (s/cat :api ::EventService
                     :offset (s/? (s/nilable (s/int-in 0 Long/MAX_VALUE)))
                     :limit (s/? (s/nilable (s/int-in 0 Long/MAX_VALUE))))
        :ret (s/keys :req-un [::events ::commander/limit ::commander/offset ::total])
        :fn #(let [limit (-> % :args :limit)]
               (if (pos? limit)
                 (= (-> % :ret count) limit)
                 true)))

(defn get-event-by-id
  "Returns the indexed event with the given id, or nil if none
  found."
  [api id]
  (log/info ::get-event-by-id [api id])
  (-get-event-by-id api id))

(s/fdef get-event-by-id
        :args (s/cat :api ::EventService
                     :id  ::commander/id)
        :ret ::commander/event)

(defn event-map
  [{:keys [key value topic partition offset timestamp] :as event}]
  (log/debug ::event-map [event])
  (let [{:keys [action data parent]} value]
    {:id        key
     :parent    parent
     :action    action
     :data      data
     :timestamp timestamp
     :topic     topic
     :partition partition
     :offset    offset}))

(defn events-ch
  "Returns a core.async channel (ch if given, a sliding-buffer channel
  of size 10 otherwise) that will convey all events arriving from
  the time of the call onward."
  ([api]
   (events-ch api (a/chan (a/sliding-buffer 10))))
  ([api ch]
   (log/info ::events-ch [api ch])
   (-events-ch api ch)
   ch))

(defn- command-record
  [topic id command]
  {:topic topic
   :key   id
   :value command})

(defn- send-command-and-await-result!
  [kafka-producer command-topic id command]
  (let [record (command-record command-topic id command)
        ch (k/send! kafka-producer record)]
    (if-some [ret (a/<!! ch)]
      (if (instance? Exception ret)
        (throw (ex-info "Error writing to Kafka" {:record record} ret))
        ret)
      (throw (ex-info "Error writing to Kafka: send response channel closed" {:record record})))))

(defrecord Commander [database
                      kafka-producer
                      commands-topic
                      events-topic
                      kafka-consumer
                      ch
                      pub
                      commands-ch
                      commands-mult
                      events-ch
                      events-pub
                      events-mult
                      sync-timeout-ms]
  CommandService
  (-create-command [this command-params]
    (let [id     (uuid/v1)
          result (send-command-and-await-result! kafka-producer commands-topic id command-params)]
      (assoc command-params
             :id        id
             :timestamp (:timestamp result)
             :topic     (:topic result)
             :partition (:partition result)
             :offset    (:offset result))))
  (-create-command-sync [this command-params sync-timeout-ms]
    (let [id     (uuid/v1)
          rch    (a/promise-chan)
          _      (a/sub events-pub id rch)
          result (send-command-and-await-result! kafka-producer commands-topic id command-params)
          base   (assoc command-params
                        :id        id
                        :timestamp (:timestamp result)
                        :topic     (:topic result)
                        :partition (:partition result)
                        :offset    (:offset result))]
      (try
        (a/alt!!
          rch
          ([v] (assoc base :children [(:key v)]))

          (a/timeout sync-timeout-ms)
          ([v] (assoc base :error "Timed out waiting for completion event.")))
        (finally
          (a/close! rch)
          (a/unsub events-pub id rch)))))
  (-list-commands [_ offset limit]
    (d/fetch-commands database offset limit))
  (-get-command-by-id [this id]
    (d/fetch-command-by-id database id))
  (-commands-ch [this ch]
    (let [int (a/chan 1 (map command-map))]
      (a/pipe int ch)
      (a/tap commands-mult int)
      ch))

  CommandValidator
;;; TODO
  (-validate-command-params [this command-params] true)

  EventService
  (-list-events [this offset limit]
    (d/fetch-events database offset limit))
  (-get-event-by-id [this id]
    (d/fetch-event-by-id database id))
  (-events-ch [this ch]
    (let [int (a/chan 1 (map event-map))]
      (a/pipe int ch)
      (a/tap events-mult int)
      ch))

  c/Lifecycle
  (start [this]
    (let [^Consumer consumer (:consumer kafka-consumer)
          ch             (a/chan 1)
          pub            (a/pub ch :topic)

          events-ch      (a/chan 1)
          events-mult    (a/mult events-ch)

          events-ch-copy (a/chan 1)
          events-pub     (a/pub events-ch-copy (comp :parent :value))

          commands-ch    (a/chan 1)
          commands-mult  (a/mult commands-ch)]
      (.subscribe consumer [commands-topic events-topic])

      (a/sub pub commands-topic commands-ch)
      (a/sub pub events-topic events-ch)
      (a/tap events-mult events-ch-copy)

      (k/kafka-consumer-onto-ch! kafka-consumer ch)

      (assoc this
             :ch            ch
             :pub           pub
             :events-ch     events-ch
             :events-mult   events-mult
             :events-pub    events-pub
             :commands-ch   commands-ch
             :commands-mult commands-mult)))
  (stop [this]
    (when ch (a/close! ch))
    (when pub (a/unsub-all pub))
    (when events-ch  (a/close! events-ch))
    (when events-pub (a/unsub-all events-pub))
    (when commands-ch (a/close! commands-ch))
    (dissoc this :events-ch :events-pub :commands-ch)))

(defn construct-commander-api
  [{:keys [commands-topic events-topic sync-timeout-ms]
    :as config}]
  (map->Commander {:commands-topic  commands-topic
                   :events-topic    events-topic
                   :sync-timeout-ms sync-timeout-ms}))
