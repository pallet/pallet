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
                 [org.clojure/tools.logging "0.2.0"]
                 [org.clojure/tools.macro "0.1.1"]
                 [org.clojure/tools.cli "0.2.2"]
                 [org.clojure/algo.monads "0.1.0"]
                 [com.palletops/chiba "0.2.0"]
                 [com.palletops/thread-expr "1.3.0"]
                 [com.palletops/pallet-common "0.4.0"]
                 [com.palletops/script-exec "0.3.2"]
                 [com.palletops/stevedore "0.8.0-beta.2"]
                 [enlive "1.0.1"
                  :exclusions [org.clojure/clojure]]
                 [pallet-fsmop "0.2.6"]
                 [pallet-map-merge "0.1.0"]
                 [org.clojars.runa/clj-schema "0.9.2"]
                 [useful "0.8.6"]
                 [commons-codec "1.4"]]
  :classifiers {:tests {:source-paths ^:replace ["test"]
                        :resource-paths ^:replace []}})
