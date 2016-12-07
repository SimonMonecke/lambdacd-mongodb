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

(deftest test-key->string
  (testing "should convert keyword to string with : prefix"
    (is (= ":key" (testee/key->string :key))))
  (testing "should not convert non-keyword"
    (is (= 12 (testee/key->string 12)))
    (is (= true (testee/key->string true)))
    (is (= {:map :value} (testee/key->string {:map :value})))
    (is (= ["vector" :vector] (testee/key->string ["vector" :vector])))))

(deftest test-step-id->string
  (testing "should return empty string for empty list"
    (is (= "" (testee/step-id-seq->string '()))))
  (testing "should return identity for non-sequential input"
    (is (= true (testee/step-id-seq->string true))))
  (testing "should join entries in list"
    (is (= "1-3-hallo-4" (testee/step-id-seq->string '(1 3 "hallo" 4))))))

(deftest test-deep-transform-map
  (testing "should return empty for empty map"
    (is (= {} (testee/deep-transform-map {} identity identity))))
  (testing "should return identity"
    (is (= {:key :value :key2 {:key3 :value2}}
           (testee/deep-transform-map {:key :value :key2 {:key3 :value2}} identity identity))))
  (testing "should transform values to false"
    (is (= {:key false :key2 {:key3 false}}
           (testee/deep-transform-map {:key :value :key2 {:key3 :value2}} identity (constantly false)))))
  (testing "should transform keys to string"
    (is (= {":key" :value ":key2" {":key3" :value2}}
           (testee/deep-transform-map {:key :value :key2 {:key3 :value2}} str identity))))
  (testing "should transform values to false"
    (is (= {:key false :key2 [false false]}
           (testee/deep-transform-map {:key false :key2 [1 2]} identity (constantly false))))))
