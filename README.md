# lambdacd-mongodb

[![Clojars Project](http://clojars.org/lambdacd-mongodb/latest-version.svg)](http://clojars.org/lambdacd-mongodb)

If you use [LambdaCD](https://github.com/flosell/lambdacd) in an environment without persistence you certainly noticed that after restarting LambdaCD the build history is lost. lambdacd-mongodb stores the state of your pipeline in a MongoDB and it restores it at the next startup.

Tested with LambdaCD version 0.5.3

## Example

1. Start your local MongoDB daemon (default port: 27017)
2. Run `lein run`
3. Let the pipeline run for a few times
4. Stop LambdaCD by pressing strg-c in your terminal
5. Restart LambdaCD bei running `lein run` again
6. The build history should still be there

## Configuration

1. To use lambdacd-mongodb you have to create a map containing your MongoDB configuration.
   * The :col key specifies the collection which is used to store all builds from one pipeline. Do not use a collection for more than one pipeline!
   * The :max-builds ist optional (default: 20) and definies how many inactive builds are loaded. Active builds are thrown away because you can not continue them.
2. Add the mongodb configuration map to the main configuration by using the key name :mongodb-cfg
3. Use the assemble-pipeline function from the lambdacd-mongodb.mongodb-state

```clojure
(defn -main [& args]
  (let [home-dir (util/create-temp-dir)
        mongodb-cfg {:host "localhost"
                     :port 27017
                     :db   "lambdacd"
                     :col  "test-project"
                     :max-builds 10}
        config {:mongodb-cfg              mongodb-cfg
                :home-dir                 home-dir
                :dont-wait-for-completion false}
        pipeline (lambdacd.core/assemble-pipeline pipeline-def config (mongodb-state/new-mongodb-state config))
        [...]
```

## TODO

- [x] Exception-Handling
- [ ] Tests

## License

Copyright © 2015 Simon Monecke

Distributed under MIT License
