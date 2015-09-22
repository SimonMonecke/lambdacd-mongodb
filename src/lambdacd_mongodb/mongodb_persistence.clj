(ns lambdacd-mongodb.mongodb-persistence
  (:import (java.util.regex Pattern)
           (java.io File)
           (org.joda.time DateTime)
           (com.mongodb MongoException))
  (:require [clojure.string :as str]
            [lambdacd.util :as util]
            [clj-time.format :as f]
            [clojure.data.json :as json]
            [monger.collection :as mc]
            [monger.query :as mq]
            [cheshire.core :as cheshire]
            [clj-time.core :as t]
            monger.joda-time
            [clojure.tools.logging :as log]))

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

; copyied from lambdacd.presentation.pipeline-state

(defn- desc [a b]
  (compare b a))

(defn- root-step? [[step-id _]]
  (= 1 (count step-id)))

(defn- root-step-id [[step-id _]]
  (first step-id))

(defn- step-result [[_ step-result]]
  step-result)

(defn- status-for-steps [step-ids-and-results]
  (let [accumulated-status (->> step-ids-and-results
                                (filter root-step?)
                                (sort-by root-step-id desc)
                                (first)
                                (step-result)
                                (:status))]
    (or accumulated-status :unknown)))

(defn- is-inactive? [build-state]
  (let [status (status-for-steps build-state)]
    (not (or (= status :running) (= status :waiting) (= status :unknown)))))

; own functions

(defn build-has-only-a-trigger [build]
  (every? (partial = 1)
          (map
            (fn [[k v]] (last k))
            (get build (first (keys build))))))

(defn write-build-history [mongodb-host mongodb-db mongodb-col build-number new-state pipeline-def]
  (when (not (build-has-only-a-trigger new-state))
    (let [state-as-json (pipeline-state->json-format (get new-state build-number))
          state-as-json-string (util/to-json state-as-json)
          state-as-map (cheshire/parse-string state-as-json-string)
          is-active (not (is-inactive? (get new-state build-number)))
          pipeline-def-hash (hash (clojure.string/replace pipeline-def #"\s" ""))
          state-with-more-information {"steps"        state-as-map
                                       "build-number" build-number
                                       "is-active"    is-active
                                       "hash"         pipeline-def-hash
                                       "created-at"   (t/now)}]
      (try
        (when (not (mc/exists? mongodb-db mongodb-col))
          (mc/create mongodb-db mongodb-col {}))
        (mc/ensure-index mongodb-db mongodb-col (array-map :created-at 1) {:expireAfterSeconds (long (t/in-seconds (t/weeks 2)))})
        (mc/update mongodb-db mongodb-col {"build-number" build-number} state-with-more-information {:upsert true})
        (catch MongoException e
          (log/error (str "Write to DB: Can't connect to MongoDB server \"" mongodb-host "\""))
          (log/error e))
        (catch Exception e
          (log/error "Write to DB: An unexpected error occurred")
          (log/error e))))))

(defn format-state [old [step-id step-result]]
  (conj old {:step-id step-id :step-result step-result}))

(defn- read-state [state-map]
  (let [build-number (get state-map "build-number")
        steps (get state-map "steps")
        state-as-vec (reduce format-state [] steps)
        state-as-string (cheshire/generate-string state-as-vec)
        state (json-format->pipeline-state (json/read-str state-as-string :key-fn keyword :value-fn post-process-values))]
    {build-number state}))

(defn- find-builds [mongodb-db mongodb-col max-builds pipeline-def]
  (let [pipeline-def-hash (hash (clojure.string/replace pipeline-def #"\s" ""))]
    (mq/with-collection mongodb-db mongodb-col
                        (mq/find {"hash" pipeline-def-hash})
                        (mq/sort (array-map "build-number" -1))
                        (mq/limit max-builds)
                        (mq/keywordize-fields false))))

(defn to-kill [m]
  (if (or (= (:status m) :running) (= (:status m) :waiting)) (assoc m :status :killed) m))

(defn clean-steps [steps]
  (reduce
    (fn [m k]
      (assoc m k
               (to-kill (get steps k))))
    {} (keys steps)))

(defn clean-build [build]
  (reduce
    (fn [m k]
      (assoc m k
               (clean-steps (get build k))))
    {} (keys build)))

(defn clean-states [build-list]
  (map
    clean-build
    build-list))

(defn read-build-history-from [mongodb-host mongodb-db mongodb-col max-builds pipeline-def]
  (let [build-state-seq (find-builds mongodb-db mongodb-col max-builds pipeline-def)
        build-state-maps (map (fn [build] (monger.conversion/from-db-object build false)) build-state-seq)
        states (map read-state build-state-maps)
        cleaned-states (clean-states states)]
    (try
      (into {} cleaned-states)
      (catch MongoException e
        (log/error (str "Read from DB: Can't connect MongoDB server \"" mongodb-host "\""))
        (log/error e))
      (catch Exception e
        (log/error "Read from DB: An unexpected error occurred")
        (log/error e)))))
