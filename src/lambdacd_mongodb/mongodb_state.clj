(ns lambdacd-mongodb.mongodb-state
  (:require [lambdacd-mongodb.mongodb-persistence :as persistence]
            [lambdacd.internal.default-pipeline-state :as default-pipeline-state]
            [clj-time.core :as t]
            [lambdacd.internal.pipeline-state :as pipeline-state-protocol]
            [clojure.core.async :as async]
            [monger.core :as mg]
            [clojure.tools.logging :as log]))

(defn initial-pipeline-state [mongodb-uri mongodb-db mongodb-col max-builds mark-running-steps-as-failure pipeline-def]
  (when (< max-builds 1)
    (log/error "LambdaCD-MongoDB: max-builds must be greater than zero"))
  (persistence/read-build-history-from mongodb-uri mongodb-db mongodb-col max-builds mark-running-steps-as-failure pipeline-def))

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
  [build-number step-id step-result mongodb-uri mongodb-db mongodb-col state ttl pipeline-def]
  (if (not (nil? state))
    (let [new-state (swap! state (partial update-pipeline-state build-number step-id step-result))]
      (persistence/write-build-history mongodb-uri mongodb-db mongodb-col build-number new-state ttl pipeline-def))))

; copied from lambdacd.internal.default-pipeline-state, change parameters and record name

(defrecord MongoDBState [state-atom mongodb-uri mongodb-db mongodb-col ttl pipeline-def]
  pipeline-state-protocol/PipelineStateComponent
  (update [self build-number step-id step-result]
    (update-legacy build-number step-id step-result mongodb-uri mongodb-db mongodb-col state-atom ttl pipeline-def))
  (get-all [self]
    @state-atom)
  (get-internal-state [self]
    state-atom)
  (next-build-number [self]
    (quot (System/currentTimeMillis) 1000)))

(defn init-mongodb [mc]
  (try
    (let [conn (:conn (mg/connect-via-uri (:uri mc)))
          db (mg/get-db conn (:db mc))
          state-atom (atom (initial-pipeline-state (:uri mc)
                                                   db
                                                   (:col mc)
                                                   (or (:max-builds mc) 20)
                                                   (or (:mark-running-steps-as-failure mc) false)
                                                   (:pipeline-def mc)))]
      (->MongoDBState state-atom
                      (:uri mc)
                      db
                      (:col mc)
                      (or (:ttl mc) 7)
                      (:pipeline-def mc)))
    (catch Exception e
      (log/error "LambdaCD-MongoDB: Can't initialize MongoDB")
      (log/error "LambdaCD-MongoDB: caught" (.getMessage e)))))

(defn get-missing-keys [mc]
  (let [keyset (set (keys mc))
        needed-keys #{:uri
                      :db
                      :col
                      :pipeline-def}
        common-keys (clojure.set/intersection keyset needed-keys)]
    (clojure.set/difference needed-keys common-keys)))

(defn check-mongodb-keys [mc]
  (let [missing-keys (get-missing-keys mc)]
    (if (not (empty? missing-keys))
      (log/error "LambdaCD-MongoDB: Can't find key(s):" missing-keys)
      (init-mongodb mc))))

(defn get-mongodb-cfg [c]
  (let [mongodb-cfg (:mongodb-cfg c)]
    (if (nil? mongodb-cfg)
      (log/error "LambdaCD-MongoDB: Can't find key \":mongodb-cfg\" in your config")
      (check-mongodb-keys mongodb-cfg))))

(defn new-mongodb-state [config]
  (log/debug config)
  (let [mongodb-persistence (get-mongodb-cfg config)]
    (if (nil? (get-mongodb-cfg config))
      (do (log/error "LambdaCD-MongoDB: Can't init persistence.")
          (log/error "Use fallback: LambdaCD-Default-Persistence")
          (default-pipeline-state/new-default-pipeline-state config))
      (do
        (log/info "LambdaCD-MongoDB: Initizied MongoDB")
        mongodb-persistence))))