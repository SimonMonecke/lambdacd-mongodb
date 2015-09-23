(ns lambda-mongodb.test.mongodb-persistence
  (:require [clojure.test :refer :all]
            [lambdacd-mongodb.mongodb-persistence :as p]))

(deftest test-to-kill
  (testing "don't change success steps"
    (is (=
          {:status :success}
          (p/to-kill {:status :success}))))
  (testing "don't change failed steps"
    (is (=
          {:status :failed}
          (p/to-kill {:status :failed}))))
  (testing "don't change any other entries"
    (is (=
          {:status :success :foo :bar}
          (p/to-kill {:status :success :foo :bar}))))
  (testing "change waiting status to killed"
    (is (=
          {:status :killed :foo :bar}
          (p/to-kill {:status :waiting :foo :bar}))))
  (testing "change running status to killed"
    (is (=
          {:status :killed :foo :bar}
          (p/to-kill {:status :running :foo :bar})))))

(deftest test-clean-steps
  (testing "don't change success steps"
    (is (=
          {'(1) {:status :success} '(2 1) {:status :success} '(2) {:status :success}}
          (p/clean-steps {'(1) {:status :success} '(2 1) {:status :success} '(2) {:status :success}}))))
  (testing "don't change failed or success steps"
    (is (=
          {'(1) {:status :success} '(2 1) {:status :failed} '(2) {:status :success}}
          (p/clean-steps {'(1) {:status :success} '(2 1) {:status :failed} '(2) {:status :success}}))))
  (testing "just change running but not success steps"
    (is (=
          {'(1) {:status :success} '(2 1) {:status :killed} '(2) {:status :success}}
          (p/clean-steps {'(1) {:status :success} '(2 1) {:status :running} '(2) {:status :success}}))))
  (testing "only change running or waiting but not success steps"
    (is (=
          {'(1) {:status :killed} '(2 1) {:status :killed} '(2) {:status :success}}
          (p/clean-steps {'(1) {:status :waiting} '(2 1) {:status :running} '(2) {:status :success}})))))

(deftest test-clean-build
  (testing "don't change success steps"
    (is (=
          {1234 {'(1) {:status :success} '(2 1) {:status :success} '(2) {:status :success}}}
          (p/clean-build {1234 {'(1) {:status :success} '(2 1) {:status :success} '(2) {:status :success}}}))))
  (testing "don't change failed or success steps"
    (is (=
          {1234 {'(1) {:status :failed} '(2 1) {:status :success} '(2) {:status :success}}}
          (p/clean-build {1234 {'(1) {:status :failed} '(2 1) {:status :success} '(2) {:status :success}}}))))
  (testing "only change running but not success or failed steps"
    (is (=
          {1234 {'(1) {:status :failed} '(2 1) {:status :killed} '(2) {:status :success}}}
          (p/clean-build {1234 {'(1) {:status :failed} '(2 1) {:status :running} '(2) {:status :success}}})))))

(deftest test-clean-states
  (testing "don't change success steps"
    (is (=
          '({1234 {'(1) {:status :success} '(2 1) {:status :success} '(2) {:status :success}}}
             {7812 {'(1) {:status :success} '(2) {:status :success :foo :bar} '(3) {:status :success}}})
          (p/clean-states '({1234 {'(1) {:status :success} '(2 1) {:status :success} '(2) {:status :success}}}
                             {7812 {'(1) {:status :success} '(2) {:status :success :foo :bar} '(3) {:status :success}}})))))
  (testing "don't change failed or success steps"
    (is (=
          '({1234 {'(1) {:status :success} '(2 1) {:status :success} '(2) {:failed :success}}}
             {7812 {'(1) {:status :success} '(2) {:status :failed :foo :bar} '(3) {:status :success}}})
          (p/clean-states '({1234 {'(1) {:status :success} '(2 1) {:status :success} '(2) {:failed :success}}}
                             {7812 {'(1) {:status :success} '(2) {:status :failed :foo :bar} '(3) {:status :success}}})))))
  (testing "only change running or waiting but not success or failed steps"
    (is (=
          '({1234 {'(1) {:status :killed} '(2 1) {:status :success} '(2) {:status :killed}}}
             {7812 {'(1) {:status :killed} '(2) {:status :success :foo :bar} '(3) {:status :success}}})
          (p/clean-states '({1234 {'(1) {:status :waiting} '(2 1) {:status :success} '(2) {:status :running}}}
                             {7812 {'(1) {:status :running} '(2) {:status :success :foo :bar} '(3) {:status :success}}}))))))

(deftest test-build-has-only-a-trigger
  (testing "build with only a trigger"
    (is (p/build-has-only-a-trigger {1234 {'(1) {:status :success}}})))
  (testing "build with only a trigger with nested steps"
    (is (p/build-has-only-a-trigger {1234 {'(1) {:status :success} '(2 1) {:status :success} '(3 1) {:status :success :foo :bar}}})))
  (testing "build with two steps"
    (is (not (p/build-has-only-a-trigger {1234 {'(1) {:status :success} '(2 1) {:status :success} '(2) {:status :success :foo :bar}}})))))