(ns lambda-mongodb.test.mongodb-persistence-read
  (:require [clojure.test :refer :all]
            [lambdacd-mongodb.mongodb-persistence-read :as p]
            [clj-time.core :as t]
            [lambdacd.util :as util]))

(deftest test-clean-states
  (testing "don't change success steps"
    (is (=
          '({1234 {'(1) {:status :success} '(2 1) {:status :success} '(2) {:status :success}}}
             {7812 {'(1) {:status :success} '(2) {:status :success :foo :bar} '(3) {:status :success}}})
          (p/set-status-of-step '({1234 {'(1) {:status :success} '(2 1) {:status :success} '(2) {:status :success}}}
                                   {7812 {'(1) {:status :success} '(2) {:status :success :foo :bar} '(3) {:status :success}}}) :killed))))
  (testing "don't change failed or success steps"
    (is (=
          '({1234 {'(1) {:status :success} '(2 1) {:status :success} '(2) {:status :success}}}
             {7812 {'(1) {:status :success} '(2) {:status :failed :foo :bar} '(3) {:status :success}}})
          (p/set-status-of-step '({1234 {'(1) {:status :success} '(2 1) {:status :success} '(2) {:status :success}}}
                                   {7812 {'(1) {:status :success} '(2) {:status :failed :foo :bar} '(3) {:status :success}}}) :killed))))
  (testing "only change running or waiting but not success or failed steps"
    (is (=
          '({1234 {'(1) {:status :killed} '(2 1) {:status :success} '(2) {:status :killed}}}
             {7812 {'(1) {:status :killed} '(2) {:status :success :foo :bar} '(3) {:status :success}}})
          (p/set-status-of-step '({1234 {'(1) {:status :waiting} '(2 1) {:status :success} '(2) {:status :running}}}
                                   {7812 {'(1) {:status :running} '(2) {:status :success :foo :bar} '(3) {:status :success}}}) :killed))))
  (testing "set running steps to :failure if mark-running-steps-as is :failure"
    (is (=
          '({1234 {'(1) {:status :killed} '(2 1) {:status :success} '(2) {:status :failure}}}
             {7812 {'(1) {:status :failure} '(2) {:status :success :foo :bar} '(3) {:status :success}}})
          (p/set-status-of-step '({1234 {'(1) {:status :waiting} '(2 1) {:status :success} '(2) {:status :running}}}
                                   {7812 {'(1) {:status :running} '(2) {:status :success :foo :bar} '(3) {:status :success}}}) :failure))))

  (testing "set running steps to :success if mark-running-steps-as is :success"
    (is (=
          '({1234 {'(1) {:status :killed} '(2 1) {:status :success} '(2) {:status :success}}}
             {7812 {'(1) {:status :success} '(2) {:status :success :foo :bar} '(3) {:status :success}}})
          (p/set-status-of-step '({1234 {'(1) {:status :waiting} '(2 1) {:status :success} '(2) {:status :running}}}
                                   {7812 {'(1) {:status :running} '(2) {:status :success :foo :bar} '(3) {:status :success}}}) :success)))))

(deftest test-post-process-values
  (testing "should convert a string with prefix : to keyword"
    (is (= :success
           (p/post-process-values :status ":success"))))
  (testing "should not convert a string without prefix : to keyword"
    (is (= "success"
           (p/post-process-values :status "success"))))
  (testing "should not convert any other type, e.g. numbers, to keyword"
    (is (= 42
           (p/post-process-values :status 42))))
  (testing "should convert a date iso string to date"
    (let [now (t/now)]
      (is (= now
             (p/post-process-values :created-at (clj-time.format/unparse util/iso-formatter now)))))))

(deftest test-post-process-keys
  (testing "should convert a string with prefix : to keyword"
    (is (= :status
           (p/post-process-keys ":status"))))
  (testing "should not convert a string without prefix : to keyword"
    (is (= "string-key"
           (p/post-process-keys "string-key"))))
  (testing "should not convert any other type, e.g. numbers, to keyword"
    (is (= 42
           (p/post-process-keys 42)))))

(deftest test-json-format->pipeline-state
  (testing "should transform ids and collect all steps in a map"
    (is (= {'(1)   "someResult"
            '(1 1) "someOtherResult"
            '(1 2) "someOtherOtherResult"}
           (p/json-format->pipeline-state [{:step-id "1" :step-result "someResult"}
                                           {:step-id "1-1" :step-result "someOtherResult"}
                                           {:step-id "1-2" :step-result "someOtherOtherResult"}])))))