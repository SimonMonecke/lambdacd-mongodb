(ns lambdacd-mongodb.mongodb-state
  (:require [lambdacd-mongodb.mongodb-persistence :as persistence]
            [lambdacd.internal.default-pipeline-state :as default-pipeline-state]
            [clj-time.core :as t]
            [lambdacd.internal.pipeline-state :as pipeline-state-protocol]
            [clojure.core.async :as async]
            [monger.core :as mg]))

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

; own functions

(defn- is-inactive? [[_ build-state]]
  (let [status (status-for-steps build-state)]
    (not (or (= status :running) (= status :waiting) (= status :unknown)))))

(defn delete-active-builds [his]
  (into {} (filter is-inactive? his)))

(defn initial-pipeline-state [mongodb-db mongodb-col]
  (let [build-history (persistence/read-build-history-from mongodb-db mongodb-col)]
    (delete-active-builds build-history)))

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
  [build-number step-id step-result mongodb-db mongodb-col state]
  (if (not (nil? state))
    (let [new-state (swap! state (partial update-pipeline-state build-number step-id step-result))]
      (persistence/write-build-history mongodb-db mongodb-col build-number new-state))))

; copied from lambdacd.internal.default-pipeline-state, change parameters and record name

(defrecord MongoDBState [state-atom mongodb-db mongodb-col]
  pipeline-state-protocol/PipelineStateComponent
  (update [self build-number step-id step-result]
    (update-legacy build-number step-id step-result mongodb-db mongodb-col state-atom))
  (get-all [self]
    @state-atom)
  (get-internal-state [self]
    state-atom)
  (next-build-number [self]
    (default-pipeline-state/next-build-number-legacy state-atom)))

(defn new-mongodb-state [state-atom mongodb-db mongodb-col step-results-channel]
  (let [instance (->MongoDBState state-atom mongodb-db mongodb-col)]
    (default-pipeline-state/start-pipeline-state-updater instance step-results-channel)
    instance))

; copied from lambdacd.core, use initial-pipeline-state and new-mongodb-state

(defn assemble-pipeline [pipeline-def config]
  (let [mongodb-cfg (:mongodb-cfg config)
        mongodb-con (mg/connect (select-keys mongodb-cfg [:host :port]))
        mongodb-db (mg/get-db mongodb-con (:db mongodb-cfg))
        mongodb-col (:col mongodb-cfg)
        state (atom (initial-pipeline-state mongodb-db mongodb-col))
        step-results-channel (async/chan)
        pipeline-state-component (new-mongodb-state state mongodb-db mongodb-col step-results-channel)
        context {:config                   config
                 :step-results-channel     step-results-channel
                 :pipeline-state-component pipeline-state-component}]
    {:state        state
     :context      context
     :pipeline-def pipeline-def}))