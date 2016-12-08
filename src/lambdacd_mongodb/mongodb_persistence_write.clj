(ns lambdacd-mongodb.mongodb-persistence-write
  (:import (com.mongodb MongoException))
  (:require [clojure.string :as str]
            [clojure.data.json :as json]
            [monger.collection :as mc]
            [clj-time.core :as t]
            [monger.joda-time]
            [clojure.tools.logging :as log]
            [clojure.walk :refer [walk]]
            [lambdacd-mongodb.mongodb-conversion :as conversion])
  (:use [com.rpl.specter]
        [monger.operators]))

(def persistence-api-version 2)

(defn- formatted-step-id [step-id]
  (str/join "-" step-id))

(defn only-trigger? [build]
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

(defn deep-transform-map [input key-fn value-fn]
  (cond
    (map? input) (clojure.walk/walk (fn [[key value]] [(key-fn key) (deep-transform-map value key-fn value-fn)]) identity input)
    (sequential? input) (clojure.walk/walk (fn [value] (deep-transform-map value key-fn value-fn)) identity input)
    :else (value-fn input)
    )

  )

(defn enrich-pipeline-state [pipeline-state build-number pipeline-def]
  (->> pipeline-state
       ((partial get-current-build build-number))
       ((partial reduce step-id-lists->string {}))
       (wrap-in-map)
       ((partial add-hash-to-map pipeline-def))
       ((partial add-build-number-to-map build-number))
       (add-api-version-to-map)
       ((fn [m] (json/write-str m :key-fn str :value-fn pre-process-values)))
       (json/read-str)
       (add-created-at-to-map)))

;(defn assoc-metadata [build-data build-number]
;  ; [build-map]
;  ; { (1) {...}
;  ;   (1 2) {...} }
;  ; 1. step-id->string
;  ; 2. enclose steps into dbmap == {:steps {...} }
;  (as-> build-data $
;        (assoc $ :build-number build-number)
;        (assoc $ :created-at (t/now))
;        (assoc $ :api-version persistence-api-version)
;        ))

(defn write-to-mongo-db [mongodb-uri mongodb-db mongodb-col build-number new-state ttl pipeline-def]
  (let [enriched-state (enrich-pipeline-state new-state build-number pipeline-def)]
    (try
      (mc/update mongodb-db mongodb-col {":build-number" build-number} {$set enriched-state} {:upsert true})
      (mc/ensure-index mongodb-db mongodb-col (array-map ":created-at" 1) {:expireAfterSeconds (long (t/in-seconds (t/days ttl)))})
      (mc/ensure-index mongodb-db mongodb-col (array-map ":build-number" 1))
      (catch MongoException e
        (log/error (str "LambdaCD-MongoDB: Write to DB: Can't connect to MongoDB server \"" mongodb-uri "\""))
        (log/error e))
      (catch Exception e
        (log/error "LambdaCD-MongoDB: Write to DB: An unexpected error occurred")
        (log/error "LambdaCD-MongoDB: caught" (.getMessage e))))))

(defn write-build-history [mongodb-uri mongodb-db mongodb-col persist-output-of-running-steps? build-number old-state new-state ttl pipeline-def]
  (when (not (only-trigger? (get new-state build-number)))
    (if persist-output-of-running-steps?
      (write-to-mongo-db mongodb-uri mongodb-db mongodb-col build-number new-state ttl pipeline-def)
      (let [old-state-only-with-status (state-only-with-status (get old-state build-number))
            new-state-only-with-status (state-only-with-status (get new-state build-number))]
        (when (not= old-state-only-with-status new-state-only-with-status)
          (write-to-mongo-db mongodb-uri mongodb-db mongodb-col build-number new-state ttl pipeline-def))))))

(defn create-or-update-build [{db :db coll :collection} build-number build-data-map]
  (let []
    (mc/update db coll {":build-number" build-number} {$set (conversion/strinigify-map-keywords build-data-map)} {:upsert true})
    (try
      (catch MongoException e
        (log/error e (str "LambdaCD-MongoDB: Write to DB: Cannot update structure for build number " build-number))))))