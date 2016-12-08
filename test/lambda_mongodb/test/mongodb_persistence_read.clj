(ns lambda-mongodb.test.mongodb-persistence-read
  (:require [clojure.test :refer :all]
            [lambdacd-mongodb.mongodb-persistence-read :as p]
            [clj-time.core :as t]
            [lambdacd.util :as util]
            [monger.collection :as mc]
            [lambdacd-mongodb.mongodb-persistence-write :refer [persistence-api-version]]
            )
  (:import (com.github.fakemongo Fongo)))

(def api-version persistence-api-version)

(deftest test-clean-states
  (testing "don't change success steps"
    (is (=
          '({1234 {'(1) {:status :success} '(2 1) {:status :success} '(2) {:status :success}}}
             {7812 {'(1) {:status :success} '(2) {:status :success :foo :bar} '(3) {:status :success}}})
          (p/set-status-of-step :killed
                                '({1234 {'(1) {:status :success} '(2 1) {:status :success} '(2) {:status :success}}}
                                   {7812 {'(1) {:status :success} '(2) {:status :success :foo :bar} '(3) {:status :success}}})))))
  (testing "don't change failed or success steps"
    (is (=
          '({1234 {'(1) {:status :success} '(2 1) {:status :success} '(2) {:status :success}}}
             {7812 {'(1) {:status :success} '(2) {:status :failed :foo :bar} '(3) {:status :success}}})
          (p/set-status-of-step :killed
                                '({1234 {'(1) {:status :success} '(2 1) {:status :success} '(2) {:status :success}}}
                                   {7812 {'(1) {:status :success} '(2) {:status :failed :foo :bar} '(3) {:status :success}}})))))
  (testing "only change running or waiting but not success or failed steps"
    (is (=
          '({1234 {'(1) {:status :killed} '(2 1) {:status :success} '(2) {:status :killed}}}
             {7812 {'(1) {:status :killed} '(2) {:status :success :foo :bar} '(3) {:status :success}}})
          (p/set-status-of-step :killed
                                '({1234 {'(1) {:status :waiting} '(2 1) {:status :success} '(2) {:status :running}}}
                                   {7812 {'(1) {:status :running} '(2) {:status :success :foo :bar} '(3) {:status :success}}})))))
  (testing "set running steps to :failure if mark-running-steps-as is :failure"
    (is (=
          '({1234 {'(1) {:status :killed} '(2 1) {:status :success} '(2) {:status :failure}}}
             {7812 {'(1) {:status :failure} '(2) {:status :success :foo :bar} '(3) {:status :success}}})
          (p/set-status-of-step :failure
                                '({1234 {'(1) {:status :waiting} '(2 1) {:status :success} '(2) {:status :running}}}
                                   {7812 {'(1) {:status :running} '(2) {:status :success :foo :bar} '(3) {:status :success}}})))))

  (testing "set running steps to :success if mark-running-steps-as is :success"
    (is (=
          '({1234 {'(1) {:status :killed} '(2 1) {:status :success} '(2) {:status :success}}}
             {7812 {'(1) {:status :success} '(2) {:status :success :foo :bar} '(3) {:status :success}}})
          (p/set-status-of-step :success
                                '({1234 {'(1) {:status :waiting} '(2 1) {:status :success} '(2) {:status :running}}}
                                   {7812 {'(1) {:status :running} '(2) {:status :success :foo :bar} '(3) {:status :success}}}))))))

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

(deftest test-read-state
  (testing "should transform mongo find result to map"
    (is (= {1234 {'(1)   "someResult"
                  '(1 2) "someOtherResult"}}
           (p/read-state {"_id"           42
                          ":steps"        {"1"   "someResult"
                                           "1-2" "someOtherResult"}
                          ":created-at"   t/now
                          ":build-number" 1234
                          ":hash"         4224})))))

(deftest test-set-step-message
  (testing "should set message in running steps"
    (is (= '({1234 {'(1)   {:status :success}
                    '(1 2) {:details [{:label "LambdaCD-MongoDB:", :details [{:label "Running step state was modified by a restart"}]}]
                            :status  :running}}}
              {1233 {'(1)   {:status :success}
                     '(1 2) {:details [{:label "LambdaCD-MongoDB:", :details [{:label "Running step state was modified by a restart"}]}]
                             :status  :running}
                     '(2)   {:status :failure}}})
           (p/set-step-message '({1234 {'(1)   {:status :success}
                                        '(1 2) {:status :running}}}
                                  {1233 {'(1)   {:status :success}
                                         '(1 2) {:status :running}
                                         '(2)   {:status :failure}}})))))
  (testing "should set message in waiting steps"
    (is (= '({1234 {'(1)   {:status :success}
                    '(1 2) {:details [{:label "LambdaCD-MongoDB:", :details [{:label "Waiting step state was modified by a restart"}]}]
                            :status  :waiting}}}
              {1233 {'(1)   {:status :success}
                     '(1 2) {:details [{:label "LambdaCD-MongoDB:", :details [{:label "Waiting step state was modified by a restart"}]}]
                             :status  :waiting}
                     '(2)   {:status :failure}}})
           (p/set-step-message '({1234 {'(1)   {:status :success}
                                        '(1 2) {:status :waiting}}}
                                  {1233 {'(1)   {:status :success}
                                         '(1 2) {:status :waiting}
                                         '(2)   {:status :failure}}}))))))

(deftest test-remove-artifacts
  (testing "should remove artifcats and add message"
    (is (= '({1234 {'(1)   {:details nil}
                    '(1 2) {:details [{:label "Artifacts", :details [{:label "Artifacts are deleted after a restart"}]}]}}}
              {1233 {'(1)   {:details [{:label "someOtherDetails", :details [{:label "someDetail"}]}]}
                     '(1 2) {:details [{:label "Artifacts", :details [{:label "Artifacts are deleted after a restart"}]}]}
                     '(2)   {:details nil}}})
           (p/remove-artifacts '({1234 {'(1)   {}
                                        '(1 2) {:details [{:label "Artifacts", :details [{:label "Artifact 1"} {:label "Artifact 2"}]}]}}}
                                  {1233 {'(1)   {:details [{:label "someOtherDetails", :details [{:label "someDetail"}]}]}
                                         '(1 2) {:details [{:label "Artifacts", :details [{:label "Artifact 1"} {:label "Artifact 2"}]}]}
                                         '(2)   {}}}))))))

(deftest test-read-build-history-from
  (testing "should restore build-history"
    (let [test-builds '({"_id" "5711249aeb8a131e6329e31c" ":steps" {"1" {":most-recent-update-at" "2016-04-15T17:27:54.355Z" ":first-updated-at" "2016-04-15T17:24:51.694Z" ":status" ":success" ":trigger-id" "cce61807-c4ab-4089-a3d3-11143b80f55e" ":has-been-waiting" true ":out" "Waiting for trigger..."} "2" {":most-recent-update-at" "2016-04-15T17:27:54.407Z" ":first-updated-at" "2016-04-15T17:27:54.356Z" ":status" ":success" ":global" {":key1" "keyword" "key2" "string"}} "3" {":most-recent-update-at" "2016-04-15T17:28:03.409Z" ":first-updated-at" "2016-04-15T17:27:54.411Z" ":status" ":success" ":out" "bar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\n" ":exit" 0 ":outputs" {"(1 3)" {":status" ":success" ":out" "foo\n" ":exit" 0} "(2 3)" {":status" ":success" ":out" "bar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\n"}} ":global" {}} "1-3" {":most-recent-update-at" "2016-04-15T17:27:54.420Z" ":first-updated-at" "2016-04-15T17:27:54.415Z" ":status" ":success" ":out" "foo\n" ":exit" 0} "2-3" {":most-recent-update-at" "2016-04-15T17:28:03.400Z" ":first-updated-at" "2016-04-15T17:27:54.427Z" ":status" ":success" ":out" "bar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\n"} "4" {":most-recent-update-at" "2016-04-15T17:28:03.433Z" ":first-updated-at" "2016-04-15T17:28:03.409Z" ":status" ":success" ":global" {":key1" "keyword" "key2" "string"}} "5" {":most-recent-update-at" "2016-04-15T17:28:05.409Z" ":first-updated-at" "2016-04-15T17:28:03.437Z" ":status" ":killed" ":trigger-id" "b1c7baa7-a23c-491c-b14f-eb1f1294f5e6" ":has-been-waiting" true ":out" "Waiting for trigger..." ":received-kill" true}} ":hash" -1157046155 ":build-number" 1460741091 ":api-version" 2 ":created-at" "2016-04-15T19:28:05.411+02:00"}
                         {"_id" "57111f9438bacdd024b77cdd" ":steps" {"1" {":most-recent-update-at" "2016-04-15T17:06:28.907Z" ":first-updated-at" "2016-04-15T17:03:52.138Z" ":status" ":success" ":trigger-id" "abc71d6d-b6af-4c96-89b6-d9841b089b94" ":has-been-waiting" true ":out" "Waiting for trigger..."} "2" {":most-recent-update-at" "2016-04-15T17:06:28.964Z" ":first-updated-at" "2016-04-15T17:06:28.908Z" ":status" ":success" ":global" {":key1" "keyword" "key2" "string"}} "3" {":most-recent-update-at" "2016-04-15T17:06:37.938Z" ":first-updated-at" "2016-04-15T17:06:28.967Z" ":status" ":success" ":out" "bar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\n" ":exit" 0 ":outputs" {"(1 3)" {":status" ":success" ":out" "foo\n" ":exit" 0} "(2 3)" {":status" ":success" ":out" "bar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\n"}} ":global" {}} "1-3" {":most-recent-update-at" "2016-04-15T17:06:28.980Z" ":first-updated-at" "2016-04-15T17:06:28.972Z" ":status" ":success" ":out" "foo\n" ":exit" 0} "2-3" {":most-recent-update-at" "2016-04-15T17:06:37.927Z" ":first-updated-at" "2016-04-15T17:06:28.976Z" ":status" ":success" ":out" "bar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\n"} "4" {":most-recent-update-at" "2016-04-15T17:06:37.942Z" ":first-updated-at" "2016-04-15T17:06:37.938Z" ":status" ":success" ":global" {":key1" "keyword" "key2" "string"}} "5" {":most-recent-update-at" "2016-04-15T17:06:41.935Z" ":first-updated-at" "2016-04-15T17:06:37.948Z" ":status" ":killed" ":trigger-id" "f37b2fee-b1c4-4ea8-b095-d126bd06db3c" ":has-been-waiting" true ":out" "Waiting for trigger..." ":received-kill" true}} ":hash" -1157046155 ":build-number" 1460739832 ":api-version" 2 ":created-at" "2016-04-15T19:06:41.936+02:00"}
                         {"_id" "570a7a78240c7f15fd5c0ef4" ":steps" {"1" {":most-recent-update-at" "2016-04-10T16:08:24.519Z" ":first-updated-at" "2016-04-10T16:08:08.705Z" ":status" ":success" ":trigger-id" "2f4e946f-9b4d-4d60-bcc7-bca6346fa6cd" ":has-been-waiting" true ":out" "Waiting for trigger..."} "2" {":most-recent-update-at" "2016-04-10T16:08:24.552Z" ":first-updated-at" "2016-04-10T16:08:24.519Z" ":status" ":success" ":global" {":key1" "keyword" "key2" "string"}} "3" {":most-recent-update-at" "2016-04-10T16:08:24.564Z" ":first-updated-at" "2016-04-10T16:08:24.556Z" ":status" ":running"} "1-3" {":most-recent-update-at" "2016-04-10T16:08:24.569Z" ":first-updated-at" "2016-04-10T16:08:24.560Z" ":status" ":success" ":out" "foo\n" ":exit" 0} "2-3" {":most-recent-update-at" "2016-04-10T16:08:24.565Z" ":first-updated-at" "2016-04-10T16:08:24.565Z" ":status" ":running"}} ":hash" -1157046155 ":build-number" 1460304488 ":api-version" 2 ":created-at" "2016-04-10T18:08:24.570+02:00"}
                         {"_id" "570a7882240c7f15fd5c0ef3" ":steps" {"1" {":most-recent-update-at" "2016-04-10T16:00:02.176Z" ":first-updated-at" "2016-04-10T15:59:02.739Z" ":status" ":success" ":trigger-id" "6340c42c-cfb3-4c17-af02-e5bedd8bb44d" ":has-been-waiting" true ":out" "Waiting for trigger..."} "2" {":most-recent-update-at" "2016-04-10T16:00:02.219Z" ":first-updated-at" "2016-04-10T16:00:02.177Z" ":status" ":success" ":global" {":key1" "keyword" "key2" "string"}} "3" {":most-recent-update-at" "2016-04-10T16:00:11.207Z" ":first-updated-at" "2016-04-10T16:00:02.222Z" ":status" ":success" ":out" "bar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\n" ":exit" 0 ":outputs" {"(1 3)" {":status" ":success" ":out" "foo\n" ":exit" 0} "(2 3)" {":status" ":success" ":out" "bar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\n"}} ":global" {}} "1-3" {":most-recent-update-at" "2016-04-10T16:00:02.234Z" ":first-updated-at" "2016-04-10T16:00:02.226Z" ":status" ":success" ":out" "foo\n" ":exit" 0} "2-3" {":most-recent-update-at" "2016-04-10T16:00:11.198Z" ":first-updated-at" "2016-04-10T16:00:02.230Z" ":status" ":success" ":out" "bar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\n"} "4" {":most-recent-update-at" "2016-04-10T16:00:11.211Z" ":first-updated-at" "2016-04-10T16:00:11.207Z" ":status" ":success" ":global" {":key1" "keyword" "key2" "string"}} "5" {":most-recent-update-at" "2016-04-10T16:00:11.220Z" ":first-updated-at" "2016-04-10T16:00:11.216Z" ":status" ":waiting" ":trigger-id" "21c1008a-9425-47bc-a1a7-480646f54737" ":has-been-waiting" true}} ":hash" -1157046155 ":build-number" 1460303942 ":api-version" 2 ":created-at" "2016-04-10T18:00:11.221+02:00"}
                         {"_id" "570a775f240c7f15fd5c0ef2" ":steps" {"1" {":most-recent-update-at" "2016-04-10T15:55:11.295Z" ":first-updated-at" "2016-04-10T15:55:07.413Z" ":status" ":success" ":trigger-id" "12b578ec-17c6-4081-9dd5-360f8a77a889" ":has-been-waiting" true ":out" "Waiting for trigger..."} "2" {":most-recent-update-at" "2016-04-10T15:55:11.300Z" ":first-updated-at" "2016-04-10T15:55:11.296Z" ":status" ":success" ":global" {":key1" "keyword" "key2" "string"}} "3" {":most-recent-update-at" "2016-04-10T15:55:11.313Z" ":first-updated-at" "2016-04-10T15:55:11.303Z" ":status" ":running"} "1-3" {":most-recent-update-at" "2016-04-10T15:55:11.313Z" ":first-updated-at" "2016-04-10T15:55:11.308Z" ":status" ":success" ":out" "foo\n" ":exit" 0} "2-3" {":most-recent-update-at" "2016-04-10T15:55:11.310Z" ":first-updated-at" "2016-04-10T15:55:11.310Z" ":status" ":running"}} ":hash" -1157046155 ":build-number" 1460303707 ":api-version" 2 ":created-at" "2016-04-10T17:55:11.314+02:00"}
                         {"_id" "570a7751240c7f15fd5c0ef1" ":steps" {"1" {":most-recent-update-at" "2016-04-10T15:54:57.023Z" ":first-updated-at" "2016-04-10T15:54:53.790Z" ":status" ":success" ":trigger-id" "c8660817-e326-4732-9e36-f9a3b283f735" ":has-been-waiting" true ":out" "Waiting for trigger..."} "2" {":most-recent-update-at" "2016-04-10T15:54:57.062Z" ":first-updated-at" "2016-04-10T15:54:57.023Z" ":status" ":success" ":global" {":key1" "keyword" "key2" "string"}} "3" {":most-recent-update-at" "2016-04-10T15:55:06.047Z" ":first-updated-at" "2016-04-10T15:54:57.068Z" ":status" ":success" ":out" "bar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\n" ":exit" 0 ":outputs" {"(1 3)" {":status" ":success" ":out" "foo\n" ":exit" 0} "(2 3)" {":status" ":success" ":out" "bar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\n"}} ":global" {}} "1-3" {":most-recent-update-at" "2016-04-10T15:54:57.080Z" ":first-updated-at" "2016-04-10T15:54:57.072Z" ":status" ":success" ":out" "foo\n" ":exit" 0} "2-3" {":most-recent-update-at" "2016-04-10T15:55:06.042Z" ":first-updated-at" "2016-04-10T15:54:57.076Z" ":status" ":success" ":out" "bar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\n"} "4" {":most-recent-update-at" "2016-04-10T15:55:06.058Z" ":first-updated-at" "2016-04-10T15:55:06.053Z" ":status" ":success" ":global" {":key1" "keyword" "key2" "string"}} "5" {":most-recent-update-at" "2016-04-10T15:55:07.393Z" ":first-updated-at" "2016-04-10T15:55:06.063Z" ":status" ":success" ":trigger-id" "c254aaf4-b902-459c-8a86-080cb253940c" ":has-been-waiting" true ":out" "Waiting for trigger..."} "6" {":most-recent-update-at" "2016-04-10T15:55:07.409Z" ":first-updated-at" "2016-04-10T15:55:07.401Z" ":status" ":failure" ":out" "i am going to fail now...\n" ":exit" 1}} ":hash" -1157046155 ":build-number" 1460303693 ":api-version" 2 ":created-at" "2016-04-10T17:55:07.410+02:00"})
          test-builds-result {1460741091 {'(1) {:most-recent-update-at (t/date-time 2016 4 15 17 27 54 355) :first-updated-at (t/date-time 2016 4 15 17 24 51 694) :status :success :trigger-id "cce61807-c4ab-4089-a3d3-11143b80f55e" :has-been-waiting true :out "Waiting for trigger..." :details nil} '(2) {:most-recent-update-at (t/date-time 2016 4 15 17 27 54 407) :first-updated-at (t/date-time 2016 4 15 17 27 54 356) :status :success :global {:key1 "keyword" "key2" "string"} :details nil} '(3) {:most-recent-update-at (t/date-time 2016 4 15 17 28 3 409) :first-updated-at (t/date-time 2016 4 15 17 27 54 411) :status :success :out "bar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\n" :exit 0 :outputs {"(1 3)" {:status :success :out "foo\n" :exit 0} "(2 3)" {:status :success :out "bar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\n"}} :global {} :details nil} '(1 3) {:most-recent-update-at (t/date-time 2016 4 15 17 27 54 420) :first-updated-at (t/date-time 2016 4 15 17 27 54 415) :status :success :out "foo\n" :exit 0 :details nil} '(2 3) {:most-recent-update-at (t/date-time 2016 4 15 17 28 3 400) :first-updated-at (t/date-time 2016 4 15 17 27 54 427) :status :success :out "bar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\n" :details nil} '(4) {:most-recent-update-at (t/date-time 2016 4 15 17 28 3 433) :first-updated-at (t/date-time 2016 4 15 17 28 3 409) :status :success :global {:key1 "keyword" "key2" "string"} :details nil} '(5) {:most-recent-update-at (t/date-time 2016 4 15 17 28 5 409) :first-updated-at (t/date-time 2016 4 15 17 28 3 437) :status :killed :trigger-id "b1c7baa7-a23c-491c-b14f-eb1f1294f5e6" :has-been-waiting true :out "Waiting for trigger..." :received-kill true :details nil}}
                              1460739832 {'(1) {:most-recent-update-at (t/date-time 2016 4 15 17 6 28 907) :first-updated-at (t/date-time 2016 4 15 17 3 52 138) :status :success :trigger-id "abc71d6d-b6af-4c96-89b6-d9841b089b94" :has-been-waiting true :out "Waiting for trigger..." :details nil} '(2) {:most-recent-update-at (t/date-time 2016 4 15 17 6 28 964) :first-updated-at (t/date-time 2016 4 15 17 6 28 908) :status :success :global {:key1 "keyword" "key2" "string"} :details nil} '(3) {:most-recent-update-at (t/date-time 2016 4 15 17 6 37 938) :first-updated-at (t/date-time 2016 4 15 17 6 28 967) :status :success :out "bar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\n" :exit 0 :outputs {"(1 3)" {:status :success :out "foo\n" :exit 0} "(2 3)" {:status :success :out "bar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\n"}} :global {} :details nil} '(1 3) {:most-recent-update-at (t/date-time 2016 4 15 17 6 28 980) :first-updated-at (t/date-time 2016 4 15 17 6 28 972) :status :success :out "foo\n" :exit 0 :details nil} '(2 3) {:most-recent-update-at (t/date-time 2016 4 15 17 6 37 927) :first-updated-at (t/date-time 2016 4 15 17 6 28 976) :status :success :out "bar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\n" :details nil} '(4) {:most-recent-update-at (t/date-time 2016 4 15 17 6 37 942) :first-updated-at (t/date-time 2016 4 15 17 6 37 938) :status :success :global {:key1 "keyword" "key2" "string"} :details nil} '(5) {:most-recent-update-at (t/date-time 2016 4 15 17 6 41 935) :first-updated-at (t/date-time 2016 4 15 17 6 37 948) :status :killed :trigger-id "f37b2fee-b1c4-4ea8-b095-d126bd06db3c" :has-been-waiting true :out "Waiting for trigger..." :received-kill true :details nil}}
                              1460304488 {'(1) {:most-recent-update-at (t/date-time 2016 4 10 16 8 24 519) :first-updated-at (t/date-time 2016 4 10 16 8 8 705) :status :success :trigger-id "2f4e946f-9b4d-4d60-bcc7-bca6346fa6cd" :has-been-waiting true :out "Waiting for trigger..." :details nil} '(2) {:most-recent-update-at (t/date-time 2016 4 10 16 8 24 552) :first-updated-at (t/date-time 2016 4 10 16 8 24 519) :status :success :global {:key1 "keyword" "key2" "string"} :details nil} '(3) {:most-recent-update-at (t/date-time 2016 4 10 16 8 24 564) :first-updated-at (t/date-time 2016 4 10 16 8 24 556) :status :failure :details [{:label "LambdaCD-MongoDB:" :details [{:label "Running step state was modified by a restart"}]}]} '(1 3) {:most-recent-update-at (t/date-time 2016 4 10 16 8 24 569) :first-updated-at (t/date-time 2016 4 10 16 8 24 560) :status :success :out "foo\n" :exit 0 :details nil} '(2 3) {:most-recent-update-at (t/date-time 2016 4 10 16 8 24 565) :first-updated-at (t/date-time 2016 4 10 16 8 24 565) :status :failure :details [{:label "LambdaCD-MongoDB:" :details [{:label "Running step state was modified by a restart"}]}]}}
                              1460303942 {'(1) {:most-recent-update-at (t/date-time 2016 4 10 16 0 2 176) :first-updated-at (t/date-time 2016 4 10 15 59 2 739) :status :success :trigger-id "6340c42c-cfb3-4c17-af02-e5bedd8bb44d" :has-been-waiting true :out "Waiting for trigger..." :details nil} '(2) {:most-recent-update-at (t/date-time 2016 4 10 16 0 2 219) :first-updated-at (t/date-time 2016 4 10 16 0 2 177) :status :success :global {:key1 "keyword" "key2" "string"} :details nil} '(3) {:most-recent-update-at (t/date-time 2016 4 10 16 0 11 207) :first-updated-at (t/date-time 2016 4 10 16 0 2 222) :status :success :out "bar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\n" :exit 0 :outputs {"(1 3)" {:status :success :out "foo\n" :exit 0} "(2 3)" {:status :success :out "bar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\n"}} :global {} :details nil} '(1 3) {:most-recent-update-at (t/date-time 2016 4 10 16 0 2 234) :first-updated-at (t/date-time 2016 4 10 16 0 2 226) :status :success :out "foo\n" :exit 0 :details nil} '(2 3) {:most-recent-update-at (t/date-time 2016 4 10 16 0 11 198) :first-updated-at (t/date-time 2016 4 10 16 0 2 230) :status :success :out "bar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\n" :details nil} '(4) {:most-recent-update-at (t/date-time 2016 4 10 16 0 11 211) :first-updated-at (t/date-time 2016 4 10 16 0 11 207) :status :success :global {:key1 "keyword" "key2" "string"} :details nil} '(5) {:most-recent-update-at (t/date-time 2016 4 10 16 0 11 220) :first-updated-at (t/date-time 2016 4 10 16 0 11 216) :status :killed :trigger-id "21c1008a-9425-47bc-a1a7-480646f54737" :has-been-waiting true :details [{:label "LambdaCD-MongoDB:" :details [{:label "Waiting step state was modified by a restart"}]}]}}
                              1460303707 {'(1) {:most-recent-update-at (t/date-time 2016 4 10 15 55 11 295) :first-updated-at (t/date-time 2016 4 10 15 55 7 413) :status :success :trigger-id "12b578ec-17c6-4081-9dd5-360f8a77a889" :has-been-waiting true :out "Waiting for trigger..." :details nil} '(2) {:most-recent-update-at (t/date-time 2016 4 10 15 55 11 300) :first-updated-at (t/date-time 2016 4 10 15 55 11 296) :status :success :global {:key1 "keyword" "key2" "string"} :details nil} '(3) {:most-recent-update-at (t/date-time 2016 4 10 15 55 11 313) :first-updated-at (t/date-time 2016 4 10 15 55 11 303) :status :failure :details [{:label "LambdaCD-MongoDB:" :details [{:label "Running step state was modified by a restart"}]}]} '(1 3) {:most-recent-update-at (t/date-time 2016 4 10 15 55 11 313) :first-updated-at (t/date-time 2016 4 10 15 55 11 308) :status :success :out "foo\n" :exit 0 :details nil} '(2 3) {:most-recent-update-at (t/date-time 2016 4 10 15 55 11 310) :first-updated-at (t/date-time 2016 4 10 15 55 11 310) :status :failure :details [{:label "LambdaCD-MongoDB:" :details [{:label "Running step state was modified by a restart"}]}]}}
                              1460303693 {'(1) {:most-recent-update-at (t/date-time 2016 4 10 15 54 57 23) :first-updated-at (t/date-time 2016 4 10 15 54 53 790) :status :success :trigger-id "c8660817-e326-4732-9e36-f9a3b283f735" :has-been-waiting true :out "Waiting for trigger..." :details nil} '(2) {:most-recent-update-at (t/date-time 2016 4 10 15 54 57 62) :first-updated-at (t/date-time 2016 4 10 15 54 57 23) :status :success :global {:key1 "keyword" "key2" "string"} :details nil} '(3) {:most-recent-update-at (t/date-time 2016 4 10 15 55 6 47) :first-updated-at (t/date-time 2016 4 10 15 54 57 68) :status :success :out "bar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\n" :exit 0 :outputs {"(1 3)" {:status :success :out "foo\n" :exit 0} "(2 3)" {:status :success :out "bar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\n"}} :global {} :details nil} '(1 3) {:most-recent-update-at (t/date-time 2016 4 10 15 54 57 80) :first-updated-at (t/date-time 2016 4 10 15 54 57 72) :status :success :out "foo\n" :exit 0 :details nil} '(2 3) {:most-recent-update-at (t/date-time 2016 4 10 15 55 6 42) :first-updated-at (t/date-time 2016 4 10 15 54 57 76) :status :success :out "bar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\nbar\n" :details nil} '(4) {:most-recent-update-at (t/date-time 2016 4 10 15 55 6 58) :first-updated-at (t/date-time 2016 4 10 15 55 6 53) :status :success :global {:key1 "keyword" "key2" "string"} :details nil} '(5) {:most-recent-update-at (t/date-time 2016 4 10 15 55 7 393) :first-updated-at (t/date-time 2016 4 10 15 55 6 63) :status :success :trigger-id "c254aaf4-b902-459c-8a86-080cb253940c" :has-been-waiting true :out "Waiting for trigger..." :details nil} '(6) {:most-recent-update-at (t/date-time 2016 4 10 15 55 7 409) :first-updated-at (t/date-time 2016 4 10 15 55 7 401) :status :failure :out "i am going to fail now...\n" :exit 1 :details nil}}}]
      (with-redefs-fn {#'p/find-builds (fn [& _] test-builds)}
        #(is (= test-builds-result
                (p/read-build-history-from "someDB" "someCol" 42 :failure "somePipelineDef")))))))

(def fongo (atom nil))

(defn db-clean-up-fixture [test]
  (reset! fongo (Fongo. "test-fongo"))
  (test)
  (reset! fongo nil))

(use-fixtures :each db-clean-up-fixture)

(deftest test-find-builds
  (let [db (.getDB @fongo "lambdacd")
        collection "test-pipe"
        find-builds #'p/find-builds]

    (testing "should only find builds with the same api version in descending build-number order"
      (mc/insert db collection {":build-number" 1 ":hash" "wrongHash" ":api-version" api-version})
      (mc/insert db collection {":build-number" 2 ":api-version" api-version})

      (let [result (find-builds db collection 10)]
        (is (= [{":build-number" 2} {":build-number" 1}] (map #(select-keys %1 [":build-number"]) result)))))))

(def MAX_BUILDS 10)

(deftest test-read-pipeline-structures-from
  (let [db (.getDB @fongo "lambdacd")
        collection "test-pipe"]
    (testing "should read all pipeline-structures from db"
      (let [add-api #(assoc % ":api-version" api-version)
            add-noise #(assoc % ":steps" [{"1" {:a :b}}])
            mongo-entry #(-> % add-api add-noise)
            b1 (mongo-entry {":build-number"       1
                             ":pipeline-structure" {":hans" ":wurst"}})
            b2 (mongo-entry {":build-number" 2})
            b3 (mongo-entry {":pipeline-structure" "super sache"})
            b4 (mongo-entry {":build-number" 4 ":pipeline-structure" "cooles ding!"})
            ]
        (doseq [x [b1 b2 b3 b4]]
          (mc/insert db collection x))
        (let [result (p/read-pipeline-structures-from db collection MAX_BUILDS)]
          (is (= {4 "cooles ding!"
                  1 {:hans :wurst}} result)))))

    (testing "should respect max builds parameter"
      (let [add-api #(assoc % ":api-version" api-version)
            add-noise #(assoc % ":steps" [{"1" {:a :b}}])
            mongo-entry #(-> % add-api add-noise)
            b1 (mongo-entry {":build-number"       1
                             ":pipeline-structure" {":hans" ":wurst"}})
            b2 (mongo-entry {":build-number" 4 ":pipeline-structure" "cooles ding!"})
            ]
        (doseq [x [b1 b2]]
          (mc/insert db collection x))
        (let [result (p/read-pipeline-structures-from db collection 1)]
          (is (= {4 "cooles ding!"} result)))))
    ))