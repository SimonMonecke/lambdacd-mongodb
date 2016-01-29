(ns example-pipeline.steps
  (:require [lambdacd.steps.shell :as shell]
            [lambdacd.steps.support :refer [new-printer print-to-output printed-output]]))

(defn some-step-that-does-nothing [& _]
  {:status :success})

(defn write-n-times [ctx printer n text]
  (if (= n 1)
    (do
      (print-to-output ctx printer text)
      {:status :success})
    (do
      (Thread/sleep 1000)
      (print-to-output ctx printer text)
      (recur ctx printer (- n 1) text))))


(defn some-step-that-echos-foo [_ ctx]
  (shell/bash ctx "/" "echo foo"))
(defn some-step-that-echos-bar [_ ctx]
  (write-n-times ctx (new-printer) 10 "bar"))

(defn some-failing-step [_ ctx]
  (shell/bash ctx "/" "echo \"i am going to fail now...\"" "exit 1"))
