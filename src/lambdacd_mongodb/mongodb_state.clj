(ns lambdacd-mongodb.mongodb-state
  (:require [lambdacd-mongodb.mongodb-persistence :as persistence]
            [lambdacd.internal.default-pipeline-state :as default-pipeline-state]
            [clj-time.core :as t]
            [lambdacd.internal.pipeline-state :as pipeline-state-protocol]
            [clojure.core.async :as async]
            [monger.core :as mg]))

(defn initial-pipeline-state [mongodb-host mongodb-db mongodb-col max-builds pipeline-def]
  (when (< max-builds 1)
    (throw (IllegalArgumentException. "max-builds must be greater than zero")))
  (persistence/read-build-history-from mongodb-host mongodb-db mongodb-col max-builds pipeline-def))

; copyied from lambdacd.internal.default-pipeline-state

(defn- put-if-not-present [m k v]
  (if (contains? m k)
    m
    (assoc m k v)))

(defn- update-current-run [step-id step-result current-state]
  (let [current-step-result (get current-state step-id)
        now (t/now)
        new-step-result (-> current-step-result
                            (assoc :most-recent-update-at now)
                            (put-if-not-present :first-updated-at now)
                            (merge step-result))]
    (assoc current-state step-id new-step-result)))

(defn- update-pipeline-state [build-number step-id step-result current-state]
  (assoc current-state build-number (update-current-run step-id step-result (get current-state build-number))))

; copied from lambdacd.internal.default-pipeline-state, change function name and parameters

(defn update-legacy
  [build-number step-id step-result mongodb-host mongodb-db mongodb-col state pipeline-def]
  (if (not (nil? state))
    (let [new-state (swap! state (partial update-pipeline-state build-number step-id step-result))]
      (persistence/write-build-history mongodb-host mongodb-db mongodb-col build-number new-state pipeline-def))))

; copied from lambdacd.internal.default-pipeline-state, change parameters and record name

(defrecord MongoDBState [state-atom mongodb-host mongodb-db mongodb-col pipeline-def]
  pipeline-state-protocol/PipelineStateComponent
  (update [self build-number step-id step-result]
    (update-legacy build-number step-id step-result mongodb-host mongodb-db mongodb-col state-atom pipeline-def))
  (get-all [self]
    @state-atom)
  (get-internal-state [self]
    state-atom)
  (next-build-number [self]
    (quot (System/currentTimeMillis) 1000)))

(defn new-mongodb-state [config]
  (let [mongodb-cfg (:mongodb-cfg config)
        mongodb-host (:host mongodb-cfg)
        mongodb-con (mg/connect (select-keys mongodb-cfg [:host :port]))
        mongodb-db (mg/get-db mongodb-con (:db mongodb-cfg))
        mongodb-col (:col mongodb-cfg)
        max-builds (or (:max-builds mongodb-cfg) 20)
        pipeline-def (:pipeline-def mongodb-cfg)
        state-atom (atom (initial-pipeline-state mongodb-host mongodb-db mongodb-col max-builds pipeline-def))
        instance   (->MongoDBState state-atom mongodb-host mongodb-db mongodb-col pipeline-def)]
    instance))
