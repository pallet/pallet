{:dev {:dependencies [[org.clojure/test.check "0.5.7"]]
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
                 [lein-marginalia "0.7.1"]]
       :injections [(require 'pallet.log)
                    (pallet.log/default-log-config)]}
 :doc {:dependencies [[com.palletops/pallet-codox "0.1.0"]]
       :codox {:writer codox-md.writer/write-docs
               :output-dir "autodoc/api/0.8"
               :src-dir-uri "https://github.com/pallet/pallet/blob/develop"
               :src-linenum-anchor-prefix "L"}
       :aliases {"marg" ["marg" "-d" "autodoc/marginalia/0.8/"]
                 "codox" ["doc"]
                 "doc" ["do" "codox," "marg"]}}
 :release
 {:plugins [[lein-set-version "0.2.1"]]
  :set-version
  {:updates [{:path "README.md" :no-snapshot true}]}}
 :no-checkouts {:checkout-deps-shares ^:replace []} ; disable checkouts
 :clojure-1.5.0 {:dependencies [[org.clojure/clojure "1.5.0"]]}
 :clojure-1.5 {:dependencies [[org.clojure/clojure "1.5.1"]]}
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
