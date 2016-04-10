(ns lambda-mongodb.test.mongodb-persistence-write
  (:require [clojure.test :refer :all]
            [lambdacd-mongodb.mongodb-persistence-write :as p]
            [clj-time.core :as t]
            [lambdacd.util :as util]))

(deftest test-build-has-only-a-trigger
  (testing "build with only a trigger"
    (is (p/build-has-only-a-trigger {'(1) {:status :success}})))
  (testing "build with only a trigger with nested steps"
    (is (p/build-has-only-a-trigger {'(1) {:status :success} '(2 1) {:status :success} '(3 1) {:status :success :foo :bar}})))
  (testing "build with two steps"
    (is (not (p/build-has-only-a-trigger {'(1) {:status :success} '(2 1) {:status :success} '(2) {:status :success :foo :bar}})))))

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
    (with-redefs-fn {#'clojure.core/hash (fn [x] 12345)}
      #(is (= {:hash 12345}
              (p/add-hash-to-map '("step1" "step2") {}))))))

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
      (with-redefs-fn {#'clj-time.core/now (fn [] now)
                       #'clojure.core/hash (fn [x] 12345)}
        #(is (= {":build-number" 42
                 ":hash"         12345
                 ":created-at"   now
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