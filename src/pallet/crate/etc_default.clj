(ns pallet.crate.etc-default
  "Generation and installation of /etc/default-style files."
  (:require
   [pallet.stevedore :as stevedore]
   [pallet.script.lib :as lib]
   [clojure.string :as string])
  (:use
   [pallet.actions :only [remote-file]]
   [pallet.phase :only [def-crate-fn]]))

(def-crate-fn write
  "Writes a KEY=value file to /etc/default/~{filename}, or ~{filename} if
   filename starts with a /.  Note that all values are quoted, and quotes in
   values are escaped, but otherwise, values are written literally.

   e.g. (write \"tomcat6\"
          :JAVA_OPTS \"-Xmx1024m\"
          \"JSP_COMPILER\" \"javac\")"
  [filename & key-value-pairs]
  [file (m-result (if (= \/ (first filename))
                    filename
                   (str (stevedore/script (~lib/etc-default)) "/" filename)))]
  (remote-file
   file
   :owner "root:root"
   :mode 644
   :content (string/join
             \newline
             (for [[k v] (partition 2 key-value-pairs)]
               (str (name k) "=" (pr-str v))))))
