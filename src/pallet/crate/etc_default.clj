(ns pallet.crate.etc-default
  "Generation and installation of /etc/default-style files."
  (:require
   [clojure.string :as string]
   [pallet.actions :refer [remote-file]]
   [pallet.crate :refer [defplan]]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore]
   [pallet.stevedore :refer [with-source-line-comments]]))

(defplan write
  "Writes a KEY=value file to /etc/default/~{filename}, or ~{filename} if
   filename starts with a /.  Note that all values are quoted, and quotes in
   values are escaped, but otherwise, values are written literally.

   e.g. (write \"tomcat6\"
          :JAVA_OPTS \"-Xmx1024m\"
          \"JSP_COMPILER\" \"javac\")"
  [filename & key-value-pairs]
  (let [file (if (= \/ (first filename))
               filename
               (str (with-source-line-comments false
                      (stevedore/script (~lib/etc-default)))
                    "/" filename))]
    (remote-file
     file
     :owner "root"
     :group "root"
     :mode 644
     :content (string/join
               \newline
               (for [[k v] (partition 2 key-value-pairs)]
                 (str (name k) "=" (pr-str v)))))))
