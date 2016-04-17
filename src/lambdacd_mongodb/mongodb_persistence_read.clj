(ns lambdacd-mongodb.mongodb-persistence-read
  (:import (java.util.regex Pattern))
  (:require [clojure.string :as str]
            [clj-time.format :as f]
            [clojure.data.json :as json]
            [monger.query :as mq]
            monger.joda-time
            [lambdacd-mongodb.mongodb-persistence-write :as p-write])
  (:use [com.rpl.specter]))

(defn parse-int [int-str]
  (Integer/parseInt int-str))

(defn- unformat-step-id [formatted-step-id]
  (map parse-int (str/split formatted-step-id (Pattern/compile "-"))))

(defn- step-json->step [{step-result :step-result step-id :step-id}]
  {(unformat-step-id step-id) step-result})

(defn json-format->pipeline-state [steps-list]
  (into {} (map step-json->step steps-list)))

(defn- to-date-if-date [v]
  (try
    (f/parse (f/formatters :date-time) v)
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
        state-as-string (json/write-str state-as-vec)
        state (json-format->pipeline-state (json/read-str state-as-string :key-fn post-process-keys :value-fn post-process-values))]
    {build-number state}))

(defn- find-builds [mongodb-db mongodb-col max-builds pipeline-def]
  (let [pipeline-def-hash (hash (clojure.string/replace pipeline-def #"\s" ""))]
    (doall (map #(monger.conversion/from-db-object % false)
                (mq/with-collection mongodb-db mongodb-col
                                    (mq/find {":hash" pipeline-def-hash ":api-version" p-write/persistence-api-version})
                                    (mq/sort (array-map ":build-number" -1))
                                    (mq/limit max-builds)
                                    (mq/keywordize-fields false))))))

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

(defn set-status-of-step [mark-running-steps-as build-list]
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

(defn set-step-message [build-list]
  (->> build-list
       (set-step-message-specter :waiting "Waiting step state was modified by a restart")
       (set-step-message-specter :running "Running step state was modified by a restart")))

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

(defn read-build-history-from [mongodb-db mongodb-col max-builds mark-running-steps-as pipeline-def]
  (->> (find-builds mongodb-db mongodb-col max-builds pipeline-def)
       (map read-state)
       (remove-artifacts)
       (set-step-message)
       (set-status-of-step mark-running-steps-as)
       (into {})))
