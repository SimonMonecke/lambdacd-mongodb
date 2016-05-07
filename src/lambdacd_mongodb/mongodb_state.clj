(ns lambdacd-mongodb.mongodb-state
  (:require [lambdacd-mongodb.mongodb-persistence-read :as persistence-read]
            [lambdacd-mongodb.mongodb-persistence-write :as persistence-write]
            [lambdacd.internal.default-pipeline-state :as default-pipeline-state]
            [clj-time.core :as t]
            [lambdacd.internal.pipeline-state :as pipeline-state-protocol]
            [monger.core :as mg]
            [clojure.tools.logging :as log]))

(defn initial-pipeline-state [mongodb-db mongodb-col max-builds mark-running-steps-as pipeline-def]
  (when (< max-builds 1)
    (log/error "LambdaCD-MongoDB: max-builds must be greater than zero"))
  (persistence-read/read-build-history-from mongodb-db mongodb-col max-builds mark-running-steps-as pipeline-def))

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

(defn update-legacy
  [persist-the-output-of-running-steps build-number step-id step-result mongodb-uri mongodb-db mongodb-col state ttl pipeline-def]
  (if (not (nil? state))
    (let [old-state @state
          new-state (swap! state (partial update-pipeline-state build-number step-id step-result))]
      (persistence-write/write-build-history mongodb-uri mongodb-db mongodb-col persist-the-output-of-running-steps build-number old-state new-state ttl pipeline-def))))

(defrecord MongoDBState [state-atom persist-the-output-of-running-steps mongodb-uri mongodb-db mongodb-col ttl pipeline-def]
  pipeline-state-protocol/PipelineStateComponent
  (update [self build-number step-id step-result]
    (update-legacy persist-the-output-of-running-steps build-number step-id step-result mongodb-uri mongodb-db mongodb-col state-atom ttl pipeline-def))
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
          state-atom (atom (initial-pipeline-state db
                                                   (:col mc)
                                                   (or (:max-builds mc) 20)
                                                   (or (:mark-running-steps-as mc) :killed)
                                                   (:pipeline-def mc)))]
      (->MongoDBState state-atom
                      (= true (:persist-the-output-of-running-steps mc))
                      (:uri mc)
                      db
                      (:col mc)
                      (or (:ttl mc) 7)
                      (:pipeline-def mc)))
    (catch Exception e
      (log/error e "LambdaCD-MongoDB: Failed to initialize MongoDB"))))

(defn get-missing-keys [mc]
  (let [keyset (set (keys mc))
        needed-keys #{:uri
                      :db
                      :col
                      :pipeline-def}
        common-keys (clojure.set/intersection keyset needed-keys)]
    (clojure.set/difference needed-keys common-keys)))

(defn uri-can-be-formed [mc]
  (let [hosts (:hosts mc)]
    (not (or (nil? hosts) (empty? hosts)))))

(defn get-hosts [mc]
  (let [hosts (:hosts mc)
        port (:port mc)]
    (if (nil? port)
      (clojure.string/join "," hosts)
      (clojure.string/join "," (map (fn [host] (str host ":" port)) hosts)))))

(defn form-uri [mc]
  (let [user (:user mc)
        password (:password mc)
        authentication (if (or (nil? user) (nil? password)) "" (str user ":" password "@"))
        db (:db mc)
        hosts (get-hosts mc)]
    (str "mongodb://" authentication hosts "/" db)))

(defn add-uri [mc]
  (if (uri-can-be-formed mc)
    (assoc mc :uri (form-uri mc))
    mc))

(defn check-mongodb-keys [mc]
  (let [mc-with-uri (add-uri mc)
        missing-keys (get-missing-keys mc-with-uri)]
    (if (empty? missing-keys)
      mc-with-uri
      (log/error "LambdaCD-MongoDB: Can't find key(s):" missing-keys))))

(defn get-mongodb-cfg [c]
  (let [mongodb-cfg (:mongodb-cfg c)]
    (if (nil? mongodb-cfg)
      (log/error "LambdaCD-MongoDB: Can't find key \":mongodb-cfg\" in your config")
      (check-mongodb-keys mongodb-cfg))))

(defn use-default-persistence [config]
  (log/error "LambdaCD-MongoDB: Can't initialize persistence")
  (log/error "Use fallback: LambdaCD-Default-Persistence")
  (default-pipeline-state/new-default-pipeline-state config))

(defn new-mongodb-state [config]
  (log/debug config)
  (if-let [mongodb-config (get-mongodb-cfg config)]
    (if-let [mongodb-persistence (init-mongodb mongodb-config)]
      (do (log/info "LambdaCD-MongoDB: Initialized MongoDB")
          mongodb-persistence)
      (use-default-persistence config))
    (use-default-persistence config)))