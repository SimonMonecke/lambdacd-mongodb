(ns lambdacd-mongodb.mongodb-persistence-write
  (:import (com.mongodb MongoException))
  (:require [clojure.string :as str]
            [clojure.data.json :as json]
            [monger.collection :as mc]
            [cheshire.core :as cheshire]
            [clj-time.core :as t]
            monger.joda-time
            [clojure.tools.logging :as log])
  (:use [com.rpl.specter]))

(def persistence-api-version 2)

(defn- formatted-step-id [step-id]
  (str/join "-" step-id))

(defn build-has-only-a-trigger [build]
  (every? #(= % 1)
          (select [ALL FIRST LAST] build)))

(defn state-only-with-status [state]
  (reduce
    (fn [old [k v]]
      (assoc old k
                 (select-keys v [:status])))
    {}
    state))

(defn step-id-lists->string [old [k v]]
  (assoc old (formatted-step-id k) v))

(defn get-current-build [build-number m]
  (get m build-number))

(defn wrap-in-map [m]
  {:steps m})

(defn add-hash-to-map [pipeline-def m]
  (let [pipeline-def-hash (hash (clojure.string/replace pipeline-def #"\s" ""))]
    (assoc m :hash pipeline-def-hash)))

(defn add-build-number-to-map [build-number m]
  (assoc m :build-number build-number))

(defn add-created-at-to-map [m]
  (assoc m ":created-at" (t/now)))

(defn add-api-version-to-map [m]
  (assoc m :api-version persistence-api-version))

(defn pre-process-values [_ v]
  (if (keyword? v)
    (str v)
    v))

(defn enrich-pipeline-state [pipeline-state build-number pipeline-def]
  (->> pipeline-state
       ((partial get-current-build build-number))
       ((partial reduce step-id-lists->string {}))
       (wrap-in-map)
       ((partial add-hash-to-map pipeline-def))
       ((partial add-build-number-to-map build-number))
       (add-api-version-to-map)
       ((fn [m] (json/write-str m :key-fn str :value-fn pre-process-values)))
       (cheshire/parse-string)
       (add-created-at-to-map)))

(defn write-to-mongo-db [mongodb-uri mongodb-db mongodb-col build-number new-state ttl pipeline-def]
  (let [enriched-state (enrich-pipeline-state new-state build-number pipeline-def)]
    (try
      (mc/update mongodb-db mongodb-col {":build-number" build-number} enriched-state {:upsert true})
      (mc/ensure-index mongodb-db mongodb-col (array-map ":created-at" 1) {:expireAfterSeconds (long (t/in-seconds (t/days ttl)))})
      (mc/ensure-index mongodb-db mongodb-col (array-map ":build-number" 1))
      (catch MongoException e
        (log/error (str "LambdaCD-MongoDB: Write to DB: Can't connect to MongoDB server \"" mongodb-uri "\""))
        (log/error e))
      (catch Exception e
        (log/error "LambdaCD-MongoDB: Write to DB: An unexpected error occurred")
        (log/error "LambdaCD-MongoDB: caught" (.getMessage e))))))

(defn write-build-history [mongodb-uri mongodb-db mongodb-col persist-the-output-of-running-steps build-number old-state new-state ttl pipeline-def]
  (when (not (build-has-only-a-trigger (get new-state build-number)))
    (if persist-the-output-of-running-steps
      (write-to-mongo-db mongodb-uri mongodb-db mongodb-col build-number new-state ttl pipeline-def)
      (let [old-state-only-with-status (state-only-with-status (get old-state build-number))
            new-state-only-with-status (state-only-with-status (get new-state build-number))]
        (when (not= old-state-only-with-status new-state-only-with-status)
          (write-to-mongo-db mongodb-uri mongodb-db mongodb-col build-number new-state ttl pipeline-def))))))