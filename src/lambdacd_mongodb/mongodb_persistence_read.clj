(ns lambdacd-mongodb.mongodb-persistence-read
  (:import (java.util.regex Pattern)
           (com.mongodb MongoException))
  (:require [clojure.string :as str]
            [lambdacd.util :as util]
            [clj-time.format :as f]
            [clojure.data.json :as json]
            [monger.query :as mq]
            [cheshire.core :as cheshire]
            monger.joda-time
            [clojure.tools.logging :as log])
  (:use [com.rpl.specter]))

(defn- unformat-step-id [formatted-step-id]
  (map util/parse-int (str/split formatted-step-id (Pattern/compile "-"))))

(defn- step-json->step [{step-result :step-result step-id :step-id}]
  {(unformat-step-id step-id) step-result})

(defn json-format->pipeline-state [steps-list]
  (into {} (map step-json->step steps-list)))

(defn- to-date-if-date [v]
  (try
    (f/parse util/iso-formatter v)
    (catch Throwable t v)))

(defn format-state [old [step-id step-result]]
  (conj old {":step-id" step-id ":step-result" step-result}))

(defn post-process-values [k v]
  (if (and (string? v) (.startsWith v ":"))
    (keyword (.substring v 1))
    (to-date-if-date v)))

(defn post-process-keys [k]
  (if (and (string? k) (.startsWith k ":"))
    (keyword (.substring k 1))
    k))

(defn read-state [state-map]
  (let [build-number (get state-map ":build-number")
        steps (get state-map ":steps")
        state-as-vec (reduce format-state [] steps)
        state-as-string (cheshire/generate-string state-as-vec)
        state (json-format->pipeline-state (json/read-str state-as-string :key-fn post-process-keys :value-fn post-process-values))]
    {build-number state}))

(defn- find-builds [mongodb-db mongodb-col max-builds pipeline-def]
  (let [pipeline-def-hash (hash (clojure.string/replace pipeline-def #"\s" ""))]
    (mq/with-collection mongodb-db mongodb-col
                        (mq/find {":hash" pipeline-def-hash})
                        (mq/sort (array-map ":build-number" -1))
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

(defn set-status-of-step [build-list mark-running-steps-as]
  (->> build-list
       (set-status-of-step-specter :waiting :killed)
       (set-status-of-step-specter :running mark-running-steps-as)))

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

; TODO: test
(defn set-step-message [build-list]
  (->> build-list
       (set-step-message-specter :waiting "Waiting step state was modified by a restart")
       (set-step-message-specter :running "Running step state was modified by a restart")))

; TODO: test
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

; TODO: test
(defn read-build-history-from [mongodb-uri mongodb-db mongodb-col max-builds mark-running-steps-as pipeline-def]
  (let [build-state-seq (find-builds mongodb-db mongodb-col max-builds pipeline-def)
        build-state-maps (map (fn [build] (monger.conversion/from-db-object build false)) build-state-seq)
        states (map read-state build-state-maps)
        wo-artifacts (remove-artifacts states)
        with-killed-message (set-step-message wo-artifacts)
        wo-running-or-waiting-states (set-status-of-step with-killed-message mark-running-steps-as)]
    (try
      (into {} wo-running-or-waiting-states)
      (catch MongoException e
        (log/error (str "LambdaCD-MongoDB: Read from DB: Can't connect MongoDB server \"" mongodb-uri "\""))
        (log/error e))
      (catch Exception e
        (log/error "LambdaCD-MongoDB: Read from DB: An unexpected error occurred")
        (log/error "LambdaCD-MongoDB: caught" (.getMessage e))))))
