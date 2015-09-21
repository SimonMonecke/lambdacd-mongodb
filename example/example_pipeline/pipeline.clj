(ns example-pipeline.pipeline
  (:use [lambdacd.steps.control-flow]
        [lambdacd.steps.manualtrigger]
        [example-pipeline.steps])
  (:require
    [ring.server.standalone :as ring-server]
    [lambdacd.ui.ui-server :as ui]
    [lambdacd.runners :as runners]
    [lambdacd.util :as util]
    [clojure.tools.logging :as log]
    [lambdacd-mongodb.mongodb-state :as mongodb-state])
  (:gen-class))

(def pipeline-def
  `(
     wait-for-manual-trigger
     some-step-that-does-nothing
     (in-parallel
       some-step-that-echos-foo
       some-step-that-echos-bar)
     some-step-that-does-nothing
     wait-for-manual-trigger
     some-failing-step
     ))

(defn -main [& args]
  (let [home-dir (util/create-temp-dir)
        mongodb-cfg {:host "localhost"
                     :port 27017
                     :db   "lambdacd"
                     :col  "test-project"
                     :max-builds 10
                     :pipeline-def pipeline-def}
        config {:mongodb-cfg              mongodb-cfg
                :home-dir                 home-dir
                :dont-wait-for-completion false}
        pipeline (lambdacd.core/assemble-pipeline pipeline-def config (mongodb-state/new-mongodb-state config))
        app (ui/ui-for pipeline)]
    (log/info "LambdaCD Home Directory is " home-dir)
    (runners/start-one-run-after-another pipeline)
    (ring-server/serve app {:open-browser? false
                            :port          8080})))
