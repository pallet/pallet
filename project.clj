(defproject com.palletops/pallet "0.8.0-SNAPSHOT"
  :description
  "DevOps for the JVM.

Pallet is a platform for agile and programmatic automation of infrastructure
in the cloud, on server racks or directly on virtual machines. Pallet
provides cloud provider and operating system independence, and allows for an
unprecedented level of customization."

  :url "http://palletops.com"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :scm {:url "git@github.com:pallet/pallet.git"}

  :dependencies [[org.clojure/clojure "1.4.0"]
                 [org.clojure/core.incubator "0.1.0"]
                 [org.clojure/tools.logging "0.2.6"]
                 [org.clojure/tools.macro "0.1.2"]
                 [org.clojure/tools.cli "0.2.2"]
                 [org.clojure/algo.monads "0.1.0"]
                 [com.palletops/chiba "0.2.0"]
                 [com.palletops/thread-expr "1.3.0"]
                 [com.palletops/pallet-common "0.4.0"]
                 [com.palletops/pallet-repl "0.8.0-beta.2"
                  :exclusions [com.palletops/pallet]]
                 ;; [com.palletops/ssh-transport "0.5.1"]
                 [com.palletops/script-exec "0.4.1-SNAPSHOT"
                  ;; :exclusions [com.palletops/ssh-transport]
                  ]
                 [com.palletops/stevedore "0.8.0-beta.6"]
                 ;; [clj-ssh "0.5.7"]
                 [enlive "1.0.1"
                  :exclusions [org.clojure/clojure]]
                 [pallet-fsmop "0.3.1"
                  :exclusions [org.clojure/tools.logging]]
                 [pallet-map-merge "0.1.0"]
                 [org.clojars.runa/clj-schema "0.9.4"
                  :exclusions [org.clojure/clojure]]
                 [commons-codec "1.4"]]
  :classifiers {:tests {:source-paths ^:replace ["test"]
                        :resource-paths ^:replace []}})
