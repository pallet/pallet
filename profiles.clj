{:dev {:dependencies [[ch.qos.logback/logback-classic "1.0.9"]
                      [org.clojure/clojure "1.7.0"]]
       ;; We would like to create aliases in the dev profile, but this
       ;; causes a stack overflow in lein 2.0.0
       ;; https://github.com/technomancy/leiningen/pull/993
       ;; :aliases {"marg" ["with-profile" "+doc"
       ;;                   "marg" "-d" "autodoc/marginalia/0.8/"]
       ;;           "codox" ["with-profile" "+doc" "doc"]
       ;;           "doc" ["do" "codox," "marg"]}
       :checkout-deps-shares ^:replace [:source-paths :test-paths
                                        :compile-path]
       :plugins [[codox/codox.leiningen "0.6.4"]
                 [lein-marginalia "0.7.1"]
                 [lein-pallet-release "RELEASE"]],}
 :repl {:dependencies [[org.clojure/clojure "1.7.0"]]}
 :provided {:dependencies [[org.clojure/clojure "1.7.0"]]}
 :doc {:dependencies [[com.palletops/pallet-codox "0.1.0"]]
       :codox {:writer codox-md.writer/write-docs
               :output-dir "autodoc/api/0.8"
               :src-dir-uri "https://github.com/pallet/pallet/blob/develop"
               :src-linenum-anchor-prefix "L"}
       :aliases {"marg" ["marg" "-d" "autodoc/marginalia/0.8/"]
                 "codox" ["doc"]
                 "doc" ["do" "codox," "marg"]}}
 :no-checkouts {:checkout-deps-shares ^:replace []} ; disable checkouts
 :clojure-1.5.0 {:dependencies [[org.clojure/clojure "1.5.0"]]}
 :clojure-1.8.0 {:dependencies [[org.clojure/clojure "1.8.0"]]}
 :jclouds {:repositories
           {"sonatype"
            "https://oss.sonatype.org/content/repositories/releases/"}
           :dependencies [[org.cloudhoist/pallet-jclouds "1.5.2"]
                          [org.jclouds/jclouds-allblobstore "1.5.5"]
                          [org.jclouds/jclouds-allcompute "1.5.5"]
                          [org.jclouds.driver/jclouds-slf4j "1.5.5"
                           :exclusions [org.slf4j/slf4j-api]]
                          [org.jclouds.driver/jclouds-sshj "1.5.5"]]}
 :vmfest {:dependencies [[com.palletops/pallet-vmfest "0.3.0-alpha.5"]]}
 :pallet-lein {:plugins [[com.palletops/pallet-lein "0.8.0-alpha.1"]]}}
