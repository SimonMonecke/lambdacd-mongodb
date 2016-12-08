(ns lambda-mongodb.test.mongodb-persistence-write
  (:require [clojure.test :refer :all]
            [lambdacd-mongodb.mongodb-persistence-write :as p]
            [monger.collection :as mc]
            [monger.conversion :as mconv]
            [lambdacd-mongodb.mongodb-conversion :as conversion])
  (:use [monger.operators])
  (:import (com.github.fakemongo Fongo))
  )

(deftest test-build-has-only-a-trigger
  (testing "build with only a trigger"
    (is (p/only-trigger? {'(1) {:status :success}})))
  (testing "build with only a trigger with nested steps"
    (is (p/only-trigger? {'(1) {:status :success} '(2 1) {:status :success} '(3 1) {:status :success :foo :bar}})))
  (testing "build with two steps"
    (is (not (p/only-trigger? {'(1) {:status :success} '(2 1) {:status :success} '(2) {:status :success :foo :bar}})))))

(deftest test-step-id-lists->string
  (testing "should convert list keywords"
    (is (=
          {"1" "step1" "2-1" "step1sub" "2" {:status :ok}}
          (reduce p/step-id-lists->string {} {[1] "step1" [2 1] "step1sub" [2] {:status :ok}})))))

(deftest test-get-current-build
  (testing "should extract current build"
    (is (=
          {[1] :step1 [1 1] :substep}
          (p/get-current-build 42 {41 {[1] :step1} 42 {[1] :step1 [1 1] :substep} 43 {[2] :ok}})))))

(deftest test-wrap-in-test
  (testing "should wrap the build"
    (is (=
          {:steps {"1" :step1 "1-1" :substep}}
          (p/wrap-in-map {"1" :step1 "1-1" :substep})))))

(deftest test-add-hash-to-map
  (testing "should add a hash of the pipeline-def to the map"
    (with-redefs-fn {#'clojure.core/hash (fn [_] 12345)}
      #(is (= {:hash 12345}
              (p/add-hash-to-map '("step1" "step2") {}))))))

(deftest test-add-api-version-to-map
  (testing "should add api version to the map"
    (is (= {:api-version p/persistence-api-version}
           (p/add-api-version-to-map {})))))

(deftest test-add-build-number-to-map
  (testing "should add the builder-number to the map"
    (is (= {:build-number 123 :steps {}}
           (p/add-build-number-to-map 123 {:steps {}})))))

(deftest test-add-created-at-to-map
  (testing "should adda timestamp to the map"
    (let [now (clj-time.core/now)]
      (with-redefs-fn {#'clj-time.core/now (fn [] now)}
        #(is (= {":created-at" now "steps" {}}
                (p/add-created-at-to-map {"steps" {}})))))))

(deftest test-enrich-pipeline-state->json-format
  (testing "should enrich a pipeline-state"
    (let [now (clj-time.core/now)]
      (with-redefs-fn {#'clj-time.core/now (fn [] now)}
        #(is (= {":build-number" 42
                 ":created-at"   now
                 ":api-version"  p/persistence-api-version
                 ":steps"        {"1"   {":status" ":success" ":out" "hallo"}
                                  "1-1" {":status" ":waiting" ":out" "hey"}
                                  "2"   {":status" ":failure" ":out" "hey"}}}
                (p/enrich-pipeline-state
                  {41 {[1] {:status :running}}
                   42 {[1]   {:status :success :out "hallo"}
                       [1 1] {:status :waiting :out "hey"}
                       [2]   {:status :failure :out "hey"}}
                   43 {[1] {:status :success}}}
                  42
                  '("first-step" "second-step"))))))))

(deftest test-state-only-with-status
  (testing "should only select field :status"
    (is (= {'(1) {:status :running}
            '(2) {:status :success}}
           (p/state-only-with-status {'(1) {:out "output" :has-been-waiting true :status :running}
                                      '(2) {:out "output2" :trigger-id 42 :status :success}})))))

(deftest test-step-id-lists->string
  (testing "should transform id list to strings"
    (is (= {"1"   "someState"
            "1-1" "someOtherState"
            "2"   "someOtherOtherState"}
           (reduce p/step-id-lists->string {} {'(1)   "someState"
                                               '(1 1) "someOtherState"
                                               '(2)   "someOtherOtherState"})))))

(deftest test-pre-process-values
  (testing "should convert keywords to string with prefix :"
    (is (= ":value"
           (p/pre-process-values :key :value))))
  (testing "should just return any other type"
    (is (= 42
           (p/pre-process-values :key 42)))))



(def fongo (atom nil))

(defn db-clean-up-fixture [test]
  (reset! fongo (Fongo. "test-fongo"))
  (test)
  (reset! fongo nil))

(use-fixtures :each db-clean-up-fixture)

(deftest test-create-or-update-non-existing-document
  (let [db (.getDB @fongo "lambdacd")
        collection "test-pipe"
        mongo {:db db :collection collection}]
    (testing "should create non-existing document"
      (p/create-or-update-build mongo 42 {:some-field "someValue" :some-other-field "someOtherValue" "someStringKey" :someKeywordValue})
      (let [result (conversion/dbojb->map (mc/find-one db collection {":build-number" 42}))]
        (is (= (select-keys result [:build-number :some-field :some-other-field "someStringKey"]))
            {:build-number 42 :some-field "someValue" :some-other-field "someOtherValue" "someStringKey" :someKeywordValue})))))

(deftest test-create-or-update-upsert-document
  (let [db (.getDB @fongo "lambdacd")
        collection "test-pipe"
        mongo {:db db :collection collection}]
    (testing "should update existing document"
      (mc/insert db collection (conversion/strinigify-map-keywords {:build-number 42 :some-field "someValue"}))
      (p/create-or-update-build mongo 42 {:some-field "someUpdatedValue" :some-other-field "someOtherValue" "someStringKey" :someKeywordValue})
      (let [result (conversion/dbojb->map (mc/find-one db collection {":build-number" 42}))]
        (is (= (select-keys result [:build-number :some-field :some-other-field "someStringKey"])
               {:build-number 42 :some-field "someUpdatedValue" :some-other-field "someOtherValue" "someStringKey" :someKeywordValue}))))))

(defn time->iso-formatted-string [t]
  (clj-time.format/unparse (clj-time.format/formatters :basic-date-time) t))

(deftest test-write-to-mongo-db
  (let [db (.getDB @fongo "lambdacd")
        collection "test-pipe"
        mongo {:db db :collection collection}
        some-state {41 {[1] {:status :running}}
                    42 {[1]   {:status :success :out "hallo"}
                        [1 1] {:status :waiting :out "hey"}
                        [2]   {:status :failure :out "hey"}}
                    43 {[1] {:status :success}}}]

    (testing "should update non-existing document"
      (let [now (clj-time.core/now)]
        (with-redefs [clj-time.core/now (fn [] now)]
          (p/write-to-mongo-db "someMongoUri" db collection 42 some-state 7 "somePipelineDefinition")
          (let [result (mconv/from-db-object (mc/find-one db collection {":build-number" 42}) false)]
            (is (= {":api-version"  3
                    ":build-number" 42
                    ":steps"        {"1"   {":out"    "hallo"
                                            ":status" ":success"}
                                     "1-1" {":out"    "hey"
                                            ":status" ":waiting"}
                                     "2"   {":out"    "hey"
                                            ":status" ":failure"}}}
                   (select-keys result [":build-number" ":api-version" ":steps"])))
            (is (= (time->iso-formatted-string now)
                   (time->iso-formatted-string (get result ":created-at"))))))))

    (testing "should update existing document"
      (let [now (clj-time.core/now)]
        (with-redefs [clj-time.core/now (fn [] now)]
          (mc/insert db collection {":build-number" 41 ":someKey" ":someValue"})
          (p/write-to-mongo-db "someMongoUri" db collection 41 some-state 7 "somePipelineDefinition")
          (let [result (mconv/from-db-object (mc/find-one db collection {":build-number" 41}) false)]
            (is (= {":someKey"      ":someValue"
                    ":api-version"  3
                    ":build-number" 41
                    ":steps"        {"1" {":status" ":running"}}}
                   (select-keys result [":build-number" ":api-version" ":steps" ":someKey"])))
            (is (= (time->iso-formatted-string now)
                   (time->iso-formatted-string (get result ":created-at"))))))))))
