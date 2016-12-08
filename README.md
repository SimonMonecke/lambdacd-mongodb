# lambdacd-mongodb [![Build Status](https://travis-ci.org/SimonMonecke/lambdacd-mongodb.svg?branch=master)](https://travis-ci.org/SimonMonecke/lambdacd-mongodb)

[![Clojars Project](http://clojars.org/lambdacd-mongodb/latest-version.svg)](http://clojars.org/lambdacd-mongodb)

If you use [LambdaCD](https://github.com/flosell/lambdacd) in an environment without persistence you certainly noticed that the build history is lost after restarting LambdaCD. lambdacd-mongodb stores the state of your pipeline in a MongoDB and it restores it at the next startup.

## Nice to know

* Only builds with the same `api-version` can be restored
* Only builds with at least two active steps are stored because you don't need builds waiting for a trigger 
* Tested with LambdaCD version 0.11.0
* String keys in the global map starting with the prefix ":" will be restored as keyword and not as string

## Example

1. Start your local MongoDB daemon (default port: 27017)
2. Run `lein run`
3. Let the pipeline run for a few times
4. Stop LambdaCD by pressing strg-c in your terminal
5. Restart LambdaCD by running `lein run` again
6. The build history should still be there

## Configuration

1. To use lambdacd-mongodb you have to create a map containing your MongoDB configuration.
   * The :col key specifies the collection which is used to store all builds from one pipeline. Do not use a collection for more than one pipeline!
   * The :max-builds key is optional (default: 20) and definies how many inactive builds are restored.
   * The :ttl key is optional (default: 7) and definies how many days the builds should be stored
   * The :mark-running-steps-as is optional (default: :killed). If you set it to :failure all running steps will be marked with the status :failure. If you set to :success, running steps will me marked as :success. Please be advised that configurating something other than :success, :failure or :killed will leave your pipeline in an undefined state.
   * The :persist-the-output-of-running-steps is optional (default: false). If you set it to true the state of the pipeline will be persisted if the output of any step is changed (-> many write operations). If you set it to false the state of the pipeline will only be persisted if the status of any step is changed (-> fewer write operations).
   * The :hosts key specifies the hosts of the MongoDB. The key is mandatory to form a URI.
   * The :user, :password and :port keys are optional. If you set them, they will be used to augment the URI.
   * The :uri key specifies the URI of the MongoDB. This key is considered deprecated and will only be used as a fallback if the :hosts key is not specified
2. Add the mongodb configuration map to the main configuration by using the key name :mongodb-cfg

```clojure
(defn -main [& args]
  (let [home-dir (util/create-temp-dir)
        mongodb-cfg {:user         "user"
                     :uri          "mongodb://localhost:27017/lambdacd"
                     :password     "password"
                     :hosts        ["localhost"]
                     :port         27017
                     :db           "lambdacd"
                     :col          "test-project"
                     :max-builds   10
                     :ttl          7
                     :mark-running-steps-as :killed
                     :pipeline-def pipeline-def
                     :persist-the-output-of-running-steps false
                     :use-readable-build-numbers true}
        config {:mongodb-cfg              mongodb-cfg
                :home-dir                 home-dir
                :dont-wait-for-completion false}
        pipeline (lambdacd.core/assemble-pipeline pipeline-def config (mongodb-state/new-mongodb-state config))
        [...]
```

   * In this example the URI formed is `mongodb://user:password@localhost:27017/lambdacd`

## TODO

- [ ] Use conversion function introduced with version 2.0.0 for state serialization
- [ ] Add Fongo tests for readable build-number serialization

## License

Copyright © 2015 - 2016 Simon Monecke

Distributed under MIT License
