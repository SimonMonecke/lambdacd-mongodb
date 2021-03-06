(ns lambda-mongodb.test.mongodb-state
  (:require [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            [lambdacd-mongodb.mongodb-state :as s]
            [lambdacd.state.protocols :as protocols]
            [lambdacd.internal.pipeline-state :as old-protocol]

            ))

(def config-without-uri
  {:db           "db"
   :col          "col"
   :pipeline-def "pipeline-def"})

(def config-with-uri
  (assoc config-without-uri :uri "mongodb://user:password@localhost:27017,locohorst:27017/db"))

(def config-with-uri-parts
  (assoc config-without-uri :user "user"
                            :hosts ["localhost" "locohorst"]
                            :port 27017
                            :password "password"))

(deftest test-get-mongodb-cfg
  (with-redefs [log/log* (fn [_ _ _ message] message)]
    (testing "that it gets a config"
      (is (= config-with-uri (s/get-mongodb-cfg {:mongodb-cfg config-with-uri}))))
    (testing "that it forms a uri if not given"
      (is (= (merge config-with-uri config-with-uri-parts)
             (s/get-mongodb-cfg {:mongodb-cfg config-with-uri-parts}))))
    (testing "that it does not need a port"
      (is (= "mongodb://user:password@localhost,locohorst/db" (:uri (s/get-mongodb-cfg {:mongodb-cfg (dissoc config-with-uri-parts :port)})))))
    (testing "that it does not need a user"
      (is (= "mongodb://localhost:27017,locohorst:27017/db" (:uri (s/get-mongodb-cfg {:mongodb-cfg (dissoc config-with-uri-parts :user)})))))
    (testing "that it does not need a password"
      (is (= "mongodb://localhost:27017,locohorst:27017/db" (:uri (s/get-mongodb-cfg {:mongodb-cfg (dissoc config-with-uri-parts :password)})))))
    (testing "that it does need a host"
      (is (= "LambdaCD-MongoDB: Can't find key(s): #{:uri}" (s/get-mongodb-cfg {:mongodb-cfg (dissoc config-with-uri-parts :hosts)})))
      (is (= "LambdaCD-MongoDB: Can't find key(s): #{:uri}" (s/get-mongodb-cfg {:mongodb-cfg (assoc config-with-uri-parts :hosts [])}))))
    (testing "that it only needs one host"
      (is (= "mongodb://user:password@localhost:27017/db"
             (:uri (s/get-mongodb-cfg {:mongodb-cfg (assoc config-with-uri-parts :hosts ["localhost"])})))))))


(defn verify [verify-atom key expectedValue]
  (= (get @verify-atom key) expectedValue))

(deftest test-MongoDBState-consume-update
  (testing "should call update-legacy twice"
    (let [v (atom {:update-legacy 0})]
      (with-redefs [s/update-legacy (fn [& params] (swap! v #(update % :update-legacy inc)))]
        (let [state (s/map->MongoDBState {})]
          (protocols/consume-step-result-update state nil nil nil)
          (old-protocol/update state nil nil nil)
          (is (verify v :update-legacy 2))
          ))))

  (testing "should call update-legacy with correct parameters"
    (let [v (atom {:update-legacy []})]
      (with-redefs [s/update-legacy (fn [& params] (swap! v #(assoc % :update-legacy params)))]
        (let [state (s/->MongoDBState :state-atom :structure-atom :persist-the-output-of-running-steps :uri :db :col :ttl :pip-def :readable)]
          (protocols/consume-step-result-update state :build-number :step-id :step-result)
          (is (verify v :update-legacy
                      [:persist-the-output-of-running-steps :build-number :step-id :step-result :uri :db :col :state-atom :ttl :pip-def]
                      ))))))
  )


(deftest test-mongoDBState-nextBuildNumber
  (testing "should call next-build-number!"
    (let [v (atom {:next-build-number false})]
      (with-redefs [s/next-build-number! (fn [& params] (swap! v #(assoc % :next-build-number true)))]
        (let [state (s/map->MongoDBState {:use-readable-build-numbers? true})]
          (protocols/next-build-number state)
          (is (verify v :next-build-number true))))))

  (testing "should call get-timestamp"
    (let [v (atom {:get-timestamp false})]
      (with-redefs [s/get-timestamp (fn [] (swap! v #(assoc % :get-timestamp true)))]
        (let [state (s/map->MongoDBState {:use-readable-build-numbers? false})]
          (protocols/next-build-number state)
          (is (verify v :get-timestamp true))))))
  )

(deftest test-mongoDBState-consume-pipeline-structure
  (testing "should call persist-pipeline-structure"
    (let [v (atom {:persist-pipeline-structure-to-mongo []})]
      (with-redefs [s/persist-pipeline-structure-to-mongo (fn [& params] (swap! v #(assoc % :persist-pipeline-structure-to-mongo params)))]
        (let [state (s/map->MongoDBState {:state-atom (atom {}) :structure-atom (atom {})})]
          (protocols/consume-pipeline-structure state :build-number :pipeline-structure)
          (is (verify v :persist-pipeline-structure-to-mongo
                      [state :build-number :pipeline-structure]))))))

  (testing "should write pipeline-structure to state for non-existing build-number"
    (let [state (s/map->MongoDBState {:state-atom (atom {}) :structure-atom (atom {1 {:otherBuild :structure}})})
          structure {:my :cool :pipeline "structure"}
          updated-state {1 {:otherBuild :structure} 42 {:my :cool :pipeline "structure"}}]
      (with-redefs [s/persist-pipeline-structure-to-mongo (fn [& _] nil)]
        (protocols/consume-pipeline-structure state 42 structure)
        (is (= updated-state
               @(:structure-atom state))))))

  (testing "should write pipeline-structure to state for existing build-number"
    (let [state (s/map->MongoDBState {:state-atom     (atom {})
                                      :structure-atom (atom {42 {:some-inner-field "someInnerValue"}})})
          structure {:my :cool :pipeline "structure"}
          updated-state {42 {:my :cool :pipeline "structure"}}]
      (with-redefs [s/persist-pipeline-structure-to-mongo (fn [& params] nil)]
        (protocols/consume-pipeline-structure state 42 structure)
        (is (= updated-state
               @(:structure-atom state))))))
  )

(deftest test-mongoDBState-all-build-numbers
  (testing "should return all sorted build-numbers"
    (let [state (s/map->MongoDBState {:state-atom (atom {42 :some-build
                                                         10 :some-other-build
                                                         20 :in-the-middle})})
          build-numbers (protocols/all-build-numbers state)]
      (is (= build-numbers
             [10 20 42]))))

  (testing "should return empty list if no builds exist"
    (let [state (s/map->MongoDBState {:state-atom (atom {})})
          build-numbers (protocols/all-build-numbers state)]
      (is (= build-numbers
             []))))
  )

(deftest test-mongoDBState-get-step-results
  (testing "should return step results"
    (let [state (s/map->MongoDBState {:state-atom (atom {42 :some-step-result
                                                         10 :some-other-build
                                                         20 :in-the-middle})})]
      (is (= (protocols/get-step-results state 42)
             :some-step-result))))
  )

(deftest test-mongoDBState-get-pipeline-structure
  (testing "should return pipeline structure"
    (let [state (s/map->MongoDBState {:state-atom (atom {42 :some-step-result
                                                         20 :in-the-middle})
                                      :structure-atom (atom {42 :cool-structure})})]
      (is (= (protocols/get-pipeline-structure state 42)
             :cool-structure))))
  )

