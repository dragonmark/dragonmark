(defproject dragonmark "0.1.0-SNAPSHOT"
  :description "Distributed CSP/core.async"
  :url "http://dragonmark.org"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/core.async "0.1.278.0-76b25b-alpha"]]

  :jar-exclusions [#"\.cljx|\.swp|\.swo|\.DS_Store"]
  
  :source-paths ["src/cljx"]
  :test-paths ["target/test-classes"]

  :hooks [cljx.hooks]

  :cljx {:builds [{:source-paths ["src/cljx"]
                   :output-path "target/classes"
                   :rules :clj}

                  {:source-paths ["src/cljx"]
                   :output-path "target/classes"
                   :rules :cljs}

                  {:source-paths ["test/cljx"]
                   :output-path "target/test-classes"
                   :rules :clj}

                  {:source-paths ["test/cljx"]
                   :output-path "target/test-classes"
                   :rules :cljs}]}

  :cljsbuild {:test-commands {"node" ["node" :node-runner "target/testable.js"]}
              :builds [{:source-paths ["target/classes" "target/test-classes"]
                        :compiler {:output-to "target/testable.js"
                                   :optimizations :advanced
                                   :pretty-print true}}]}

  :profiles {:dev {:plugins [[org.clojure/clojurescript "0.0-2156"]
                             [com.keminglabs/cljx "0.3.2"]
                             [lein-cljsbuild "1.0.1"]]
                   :aliases {"cleantest" ["do" "clean," "cljx" "once," "test,"
                                          "cljsbuild" "test"]
                             "deploy" ["do" "clean," "cljx" "once," "deploy" "clojars"]}}}

  :repositories {"sonatype-oss-public"
                 "https://oss.sonatype.org/content/repositories/snapshots/"}

)
