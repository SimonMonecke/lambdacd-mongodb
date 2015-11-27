(ns lambda-mongodb.test.mongodb-persistence
  (:require [clojure.test :refer :all]
            [lambdacd-mongodb.mongodb-persistence :as p]))

(deftest test-clean-states
  (testing "don't change success steps"
    (is (=
          '({1234 {'(1) {:status :success} '(2 1) {:status :success} '(2) {:status :success}}}
             {7812 {'(1) {:status :success} '(2) {:status :success :foo :bar} '(3) {:status :success}}})
          (p/set-status-of-step '({1234 {'(1) {:status :success} '(2 1) {:status :success} '(2) {:status :success}}}
                                   {7812 {'(1) {:status :success} '(2) {:status :success :foo :bar} '(3) {:status :success}}}) false))))
  (testing "don't change failed or success steps"
    (is (=
          '({1234 {'(1) {:status :success} '(2 1) {:status :success} '(2) {:status :success}}}
             {7812 {'(1) {:status :success} '(2) {:status :failed :foo :bar} '(3) {:status :success}}})
          (p/set-status-of-step '({1234 {'(1) {:status :success} '(2 1) {:status :success} '(2) {:status :success}}}
                                   {7812 {'(1) {:status :success} '(2) {:status :failed :foo :bar} '(3) {:status :success}}}) false))))
  (testing "only change running or waiting but not success or failed steps"
    (is (=
          '({1234 {'(1) {:status :killed} '(2 1) {:status :success} '(2) {:status :killed}}}
             {7812 {'(1) {:status :killed} '(2) {:status :success :foo :bar} '(3) {:status :success}}})
          (p/set-status-of-step '({1234 {'(1) {:status :waiting} '(2 1) {:status :success} '(2) {:status :running}}}
                                   {7812 {'(1) {:status :running} '(2) {:status :success :foo :bar} '(3) {:status :success}}}) false))))
  (testing "set running steps to :failure if mark-running-steps-as-failure is true"
    (is (=
          '({1234 {'(1) {:status :killed} '(2 1) {:status :success} '(2) {:status :failure}}}
             {7812 {'(1) {:status :failure} '(2) {:status :success :foo :bar} '(3) {:status :success}}})
          (p/set-status-of-step '({1234 {'(1) {:status :waiting} '(2 1) {:status :success} '(2) {:status :running}}}
                                   {7812 {'(1) {:status :running} '(2) {:status :success :foo :bar} '(3) {:status :success}}}) true)))))

(deftest test-build-has-only-a-trigger
  (testing "build with only a trigger"
    (is (p/build-has-only-a-trigger {'(1) {:status :success}})))
  (testing "build with only a trigger with nested steps"
    (is (p/build-has-only-a-trigger {'(1) {:status :success} '(2 1) {:status :success} '(3 1) {:status :success :foo :bar}})))
  (testing "build with two steps"
    (is (not (p/build-has-only-a-trigger {'(1) {:status :success} '(2 1) {:status :success} '(2) {:status :success :foo :bar}})))))