(ns lambdacd-mongodb.mongodb-persistence
  (:import (java.util.regex Pattern)
           (java.io File)
           (org.joda.time DateTime))
  (:require [clojure.string :as str]
            [lambdacd.util :as util]
            [clj-time.format :as f]
            [clojure.data.json :as json]
            [monger.collection :as mc]
            [cheshire.core :as cheshire]))

; copyied from lambdacd.internal.default-pipeline-state-persistence

(defn- formatted-step-id [step-id]
  (str/join "-" step-id))

(defn- unformat-step-id [formatted-step-id]
  (map util/parse-int (str/split formatted-step-id (Pattern/compile "-"))))

(defn- step-result->json-format [old [k v]]
  (assoc old (formatted-step-id k) v))

(defn- pipeline-state->json-format [pipeline-state]
  (reduce step-result->json-format {} pipeline-state))

(defn- step-json->step [{step-result :step-result step-id :step-id}]
  {(unformat-step-id step-id) step-result})

(defn- json-format->pipeline-state [json-map]
  (into {} (map step-json->step json-map)))

(defn- to-date-if-date [v]
  (try
    (f/parse util/iso-formatter v)
    (catch Throwable t v)))

(defn- post-process-values [k v]
  (if (= :status k)
    (keyword v)
    (to-date-if-date v)))

; own functions

(defn write-build-history [mongodb-db mongodb-col build-number new-state]
  (let [state-as-json (pipeline-state->json-format (get new-state build-number))
        state-as-json-string (util/to-json state-as-json)
        state-as-map (cheshire/parse-string state-as-json-string)
        state-with-build-number {"steps" state-as-map "build-number" build-number}]
    (mc/update mongodb-db mongodb-col {"build-number" build-number} state-with-build-number {:upsert true})))

(defn format-state [old [step-id step-result]]
  (conj old {:step-id step-id :step-result step-result}))

(defn- read-state [state-map]
  (let [build-number (get state-map "build-number")
        steps (get state-map "steps")
        state-as-vec (reduce format-state [] steps)
        state-as-string (cheshire/generate-string state-as-vec)
        state (json-format->pipeline-state (json/read-str state-as-string :key-fn keyword :value-fn post-process-values))]
    {build-number state}))

(defn read-build-history-from [mongodb-db mongodb-col]
  (let [build-state-seq (mc/find mongodb-db mongodb-col {})
        build-state-maps (map (fn [build] (monger.conversion/from-db-object build false)) build-state-seq)
        states (map read-state build-state-maps)]
    (into {} states)))
