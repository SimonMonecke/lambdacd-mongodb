# lambdacd-mongodb

If you already use [LambdaCD](https://github.com/flosell/lambdacd) for your projects you certainly noticed that after restarting LambdaCD the build history is lost. lambdacd-mongodb stores the state of your pipeline in a MongoDB and it restores it at the next startup.

## Example

1. Start your local MongoDB daemon (default port: 27017)
2. Run `lein run`
3. Let the pipeline run for a few times
4. Stop LambdaCD by pressing strg-c in your terminal
5. Restart LambdaCD bei running `lein run` again
6. The build history should still be there

## Configuration

1. To use lambdacd-mongodb you have to create a map containing your MongoDB configuration. The :col key specifies the collection which is used to store all builds from one pipeline. Do not use a collection for more than one pipeline!
2. Add the mongodb configuration map to the main configuration by using the key name :mongodb-cfg
3. Use the assemble-pipeline function from the lambdacd-mongodb.mongodb-state

```clojure
(defn -main [& args]
  (let [home-dir (util/create-temp-dir)
        mongodb-cfg {:host "localhost"
                     :port 27017
                     :db   "lambdacd"
                     :col  "test-project"}
        config {:mongodb-cfg              mongodb-cfg
                :home-dir                 home-dir
                :dont-wait-for-completion false}
        pipeline (mongodb-state/assemble-pipeline pipeline-def config)
        [...]
```

## TODO

- [ ] Tests
- [ ] No-Memory-Mode: Send a request every time you need any part of the state. At the moment the state is only loaded once and then it is stored in memory

## License

Copyright © 2015 Simon Monecke

Distributed under MIT License
