(defproject lambdacd-mongodb "0.6.0"
            :description "LambdaCD extension which lets you use a MongoDB to persist the state of pipelines"
            :url "https://github.com/SimonMonecke/lambdacd-mongodb"
            :license {:name "The MIT License (MIT)"
                      :url "http://opensource.org/licenses/MIT"}
            :scm {:name "git"
                  :url "https://github.com/SimonMonecke/lambdacd-mongodb.git"}
            :dependencies [[lambdacd "0.5.4"]
                           [ring-server "0.3.1"]
                           [org.clojure/clojure "1.7.0"]
                           [org.clojure/tools.logging "0.3.0"]
                           [org.slf4j/slf4j-api "1.7.5"]
                           [ch.qos.logback/logback-core "1.0.13"]
                           [ch.qos.logback/logback-classic "1.0.13"]
                           [com.novemberain/monger "2.0.0"]
                           [cheshire "5.5.0"]
                           [com.rpl/specter "0.7.1"]]
            :test-paths ["test", "example"]
            :profiles {:uberjar {:aot :all}}
            :main example-pipeline.pipeline)
