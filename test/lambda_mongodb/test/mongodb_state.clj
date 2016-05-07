(ns lambda-mongodb.test.mongodb-state
  (:require [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            [lambdacd-mongodb.mongodb-state :as s]))

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
