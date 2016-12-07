(ns lambda-mongodb.test.mongodb-conversion
  (:require [lambdacd-mongodb.mongodb-conversion :as testee]
            [clojure.test :refer :all]))

(deftest test-string->key
  (testing "should convert a string with : prefix to keyword"
    (is (= :key (testee/string->key ":key"))))
  (testing "should not convert a string without : prefix to keyword"
    (is (= "key" (testee/string->key "key"))))
  (testing "should convert numbers to keyowrd"
    (is (= 42 (testee/string->key 42)))))
