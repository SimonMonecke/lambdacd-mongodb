(ns lambda-mongodb.test.mongodb-conversion
  (:require [lambdacd-mongodb.mongodb-conversion :as testee]
            [clojure.test :refer :all]))

(deftest test-string->key
  (testing "should convert a string with : prefix to keyword"
    (is (= :key (testee/string->keyword ":key"))))
  (testing "should not convert a string without : prefix to keyword"
    (is (= "key" (testee/string->keyword "key"))))
  (testing "should convert numbers to keyowrd"
    (is (= 42 (testee/string->keyword 42)))))

(deftest test-key->string
  (testing "should convert keyword to string with : prefix"
    (is (= ":key" (testee/keyword->string :key))))
  (testing "should not convert non-keyword"
    (is (= 12 (testee/keyword->string 12)))
    (is (= true (testee/keyword->string true)))
    (is (= {:map :value} (testee/keyword->string {:map :value})))
    (is (= ["vector" :vector] (testee/keyword->string ["vector" :vector])))))

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

(deftest test-map->dbobj
  (testing "should return dbobject"
    (is (= "class com.mongodb.BasicDBObject" (str (type (testee/map->dbobj {:key :value}))))))
  (testing "should call deep-transform-map"
    (let [v (atom {:deep-transform-map 0})]
      (with-redefs [testee/deep-transform-map (fn [m kf vf] (swap! v #(update % :deep-transform-map inc)) m)]
        (testee/map->dbobj {:key :value})
        (is (= (get @v :deep-transform-map) 1))))))

(deftest test-dbobj->map
  (testing "should transform dbobject to map"
    (is (= {"key" "value"}
           (testee/dbojb->map (monger.conversion/to-db-object {"key" "value"})))))
  (testing "should call deep-transform-map"
    (let [v (atom {:deep-transform-map 0})]
      (with-redefs [testee/deep-transform-map (fn [m kf vf] (swap! v #(update % :deep-transform-map inc)) m)]
        (testee/dbojb->map {:key :value})
        (is (= (get @v :deep-transform-map) 1))))))

(deftest test-map->dbobj--and--dbobj->map
  (testing "should transform map to dbobject and back again"
    (is (= {"key" "value"}
           (-> {"key" "value"}
               testee/map->dbobj
               testee/dbojb->map))))
  (testing "should transform map to dbobject and back again with keyword conversion"
    (is (= {:key :value :key2 "value2" :key3 {:key4 :value4} :key4 [1 2 3 :value5 "value6"]}
           (-> {:key :value :key2 "value2" :key3 {:key4 :value4} :key4 [1 2 3 :value5 "value6"]}
               testee/map->dbobj
               testee/dbojb->map)))))
