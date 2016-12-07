(ns lambdacd-mongodb.mongodb-conversion
  (:require [clojure.string :as str]))

(defn deep-transform-map [input key-fn value-fn]
  (cond
    (map? input) (clojure.walk/walk (fn [[key value]] [(key-fn key) (deep-transform-map value key-fn value-fn)]) identity input)
    (sequential? input) (clojure.walk/walk (fn [value] (deep-transform-map value key-fn value-fn)) identity input)
    :else (value-fn input)))

(defn string->key [key]
  (if (and (string? key) (.startsWith key ":"))
    (keyword (.substring key 1))
    key))

(defn key->string [key]
  (if (keyword? key)
    (str key)
    key))

(defn map->dbobj [m])
(defn dbojb->map [dbobj])

(defn step-id-seq->string [step-id]
  (if (sequential? step-id)
    (str/join "-" step-id)
    step-id))

