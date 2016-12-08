(ns lambdacd-mongodb.mongodb-conversion
  (:require [clojure.string :as str]
            [monger.conversion :as mcon]))

(defn deep-transform-map [input key-fn value-fn]
  (cond
    (map? input) (clojure.walk/walk (fn [[key value]] [(key-fn key) (deep-transform-map value key-fn value-fn)]) identity input)
    (sequential? input) (clojure.walk/walk (fn [value] (deep-transform-map value key-fn value-fn)) identity input)
    :else (value-fn input)))

(defn string->keyword [s]
  (if (and (string? s) (.startsWith s ":"))
    (keyword (.substring s 1))
    s))

(defn keyword->string [k]
  (if (keyword? k)
    (str k)
    k))

(defn strinigify-map-keywords [m]
  (deep-transform-map m keyword->string keyword->string))

(defn dbojb->map [dbobj]
  (as-> dbobj $
        (mcon/from-db-object $ false)
        (deep-transform-map $ string->keyword string->keyword)))

(defn step-id-seq->string [step-id]
  (if (sequential? step-id)
    (str/join "-" step-id)
    step-id))

