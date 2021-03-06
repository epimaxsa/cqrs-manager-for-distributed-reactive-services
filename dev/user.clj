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

(ns user
  (:require [clojure.repl :refer :all]
            [clojure.pprint :refer [pprint]]
            [clojure.tools.namespace.repl :refer [refresh set-refresh-dirs]]
            [clojure.java.io :as io]
            [clojure.spec.test.alpha :as stest]
            [com.stuartsierra.component :as component]
            [eftest.runner :as eftest]
            [meta-merge.core :refer [meta-merge]]
            [reloaded.repl :refer [system init start stop go reset]]
            [io.pedestal.log :as log]
            [com.capitalone.clojure.runtime :as runtime]
            [com.capitalone.commander.rest.config :as rest-config]
            [com.capitalone.commander.rest.system :as rest-system]
            [com.capitalone.commander.indexer.config :as indexer-config]
            [com.capitalone.commander.indexer.system :as indexer-system]
            [com.capitalone.commander.database :as database]
            [com.capitalone.commander.api :as api]))

(stest/instrument)

(runtime/set-default-uncaught-exception-handler!
 (fn [thread ex] (log/error ::default-uncaught-exception-handler thread
                            :exception ex)))

(def dev-config
  {:http {:env :dev}})

(def rest-config
  (meta-merge rest-config/defaults
              rest-config/environ
              dev-config))

(def indexer-config
  (meta-merge indexer-config/defaults
              indexer-config/environ
              dev-config))

(defn new-system []
  (component/system-using
   (merge (rest-system/new-system rest-config)
          (indexer-system/new-system indexer-config))
   {:indexer [:database]
    :api     [:database]}))

(ns-unmap *ns* 'test)

(defn test []
  (eftest/run-tests (eftest/find-tests "test") {:multithread? false}))

(when (io/resource "local.clj")
  (load "local"))

(defn migrate-database
  []
  (database/migrate-database! (:database rest-config)))

(defn rollback-database
  []
  (database/rollback-database! (:database rest-config)))

(defn ensure-database
  []
  (database/-main "jdbc:postgresql://localhost/postgres?user=postgres&password=postgres"
                  "commander"
                  "commander"
                  "commander"))

(reloaded.repl/set-init! new-system)
