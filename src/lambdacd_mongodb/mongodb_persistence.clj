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
            [clojure.tools.logging :as log])
  (:use [com.rpl.specter]))

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
  (every? #(= % 1)
          (select [ALL FIRST LAST] build)))

(defn write-build-history [mongodb-uri mongodb-db mongodb-col build-number new-state ttl pipeline-def]
  (when (not (build-has-only-a-trigger (get new-state build-number)))
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
        (mc/update mongodb-db mongodb-col {"build-number" build-number} state-with-more-information {:upsert true})
        (mc/ensure-index mongodb-db mongodb-col (array-map :created-at 1) {:expireAfterSeconds (long (t/in-seconds (t/days ttl)))})
        (catch MongoException e
          (log/error (str "LambdaCD-MongoDB: Write to DB: Can't connect to MongoDB server \"" mongodb-uri "\""))
          (log/error e))
        (catch Exception e
          (log/error "LambdaCD-MongoDB: Write to DB: An unexpected error occurred")
          (log/error "LambdaCD-MongoDB: caught" (.getMessage e)))))))

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

(defn set-status-of-step-specter [old-status new-status build-list]
  (setval [ALL                                              ;Container
           ALL                                              ;All builds
           LAST                                             ;List of steps
           ALL                                              ;All steps
           LAST                                             ;Data of every step
           :status
           #(= % old-status)]
          new-status
          build-list))

(defn set-status-of-step [build-list mark-running-steps-as-failure]
  (->> build-list
       (set-status-of-step-specter :waiting :killed)
       (set-status-of-step-specter :running (if mark-running-steps-as-failure
                                              :failure
                                              :killed))))

(defn set-step-message-specter [status msg build-list]
  (setval [ALL                                              ;Container
           ALL                                              ;All builds
           LAST                                             ;List of steps
           ALL                                              ;All steps
           LAST                                             ;Data of every step
           #(= (:status %) status)
           :details]
          [{:label   "LambdaCD-MongoDB:"
            :details [{:label msg}]}]
          build-list))

(defn set-step-message [build-list]
  (->> build-list
       (set-step-message-specter :waiting "Waiting step was killed by a restart")
       (set-step-message-specter :running "Running step was killed by a restart")))

(defn remove-artifacts [build-list]
  (setval [ALL
           ALL
           LAST
           ALL
           LAST
           :details
           #(sequential? %)
           ALL
           #(= (:label %) "Artifacts")
           :details]
          [{:label "Artifacts are deleted after a restart"}]
          build-list))

(defn read-build-history-from [mongodb-uri mongodb-db mongodb-col max-builds mark-running-steps-as-failure pipeline-def]
  (let [build-state-seq (find-builds mongodb-db mongodb-col max-builds pipeline-def)
        build-state-maps (map (fn [build] (monger.conversion/from-db-object build false)) build-state-seq)
        states (map read-state build-state-maps)
        wo-artifacts (remove-artifacts states)
        with-killed-message (set-step-message wo-artifacts)
        wo-running-or-waiting-states (set-status-of-step with-killed-message mark-running-steps-as-failure)]
    (try
      (into {} wo-running-or-waiting-states)
      (catch MongoException e
        (log/error (str "LambdaCD-MongoDB: Read from DB: Can't connect MongoDB server \"" mongodb-uri "\""))
        (log/error e))
      (catch Exception e
        (log/error "LambdaCD-MongoDB: Read from DB: An unexpected error occurred")
        (log/error "LambdaCD-MongoDB: caught" (.getMessage e))))))
