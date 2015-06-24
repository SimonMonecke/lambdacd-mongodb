(ns example-pipeline.steps
  (:require [lambdacd.steps.shell :as shell]))

(defn some-step-that-does-nothing [& _]
  {:status :success})

(defn some-step-that-echos-foo [_ ctx]
  (shell/bash ctx "/" "echo foo"))
(defn some-step-that-echos-bar [_ ctx]
  (shell/bash ctx "/" "echo bar"))

(defn some-failing-step [_ ctx]
  (shell/bash ctx "/" "echo \"i am going to fail now...\"" "exit 1"))
