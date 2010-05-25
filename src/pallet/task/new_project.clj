(ns pallet.task.new-project
  "Create a new pallet project.
     pallet new-project <project-directory>."
  (:import
   [java.io
    File OutputStreamWriter FileOutputStream PrintWriter]))

;;; We don't want this task to depend on clojure.contrib, or on lein

;; (defn spit
;;   "Opposite of slurp.  Opens f, writes content, then closes f."
;;   [f content]
;;   (with-open [w (PrintWriter.
;;                  (OutputStreamWriter.
;;                   (FileOutputStream. f)))]
;;     (.print w content)))

(defn file
  [& args]
  (File. (apply str (interpose "/" args))))

(defn ns->path [n]
  (str (.. (str n)
           (replace \- \_)
           (replace \. \/))
       ".clj"))

(defn new-project-lein
  [project-name project-dir]
  (let [project-name (symbol project-name)
        group-id (namespace project-name)
        artifact-id (name project-name)]
    (.mkdirs (File. project-dir))
    (spit (file project-dir "project.clj")
          (str "(defproject " project-name " \"1.0.0-SNAPSHOT\"\n"
               "  :description \"FIXME: write\"\n"
               "  :dependencies [[pallet/pallet \"0.0.1-SNAPSHOT\"]])\n"))
    (let [project-ns  (str (.replace (str project-name) "/" ".") ".nodes")
          project-clj (ns->path project-ns)
          test-clj (.replace project-clj ".clj" "_test.clj")]
      (.mkdirs (file project-dir "test"))
      (.mkdirs (.getParentFile (file project-dir "src" project-clj)))
      (spit (file project-dir "src" project-clj)
            (str "(ns " project-ns
                 "\n  \"Admin and provisioning for FIXME:project.\" "
                 "\n  (:require"
                 "\n    [pallet.core :as core]))\n"))
      (.mkdirs (.getParentFile (file project-dir "test" test-clj)))
      (spit (file project-dir "test" test-clj)
            (str "(ns " (str project-ns "-test")
                 "\n  (:use [" project-ns "] :reload-all)"
                 "\n  (:use [clojure.test]))\n\n"
                 "(deftest replace-me ;; FIXME: write\n  (is false))\n"))
      (spit (file project-dir ".gitignore")
            (apply str (interpose"\n" ["pom.xml" "*jar" "lib" "classes"])))
      (spit (file project-dir "README")
            (apply str (interpose "\n\n" [(str "# " artifact-id)
                                          "FIXME: write description"
                                          "## Usage" "FIXME: write"
                                          "## Installation" "FIXME: write"
                                          "## License" "FIXME: write\n"])))
      (println "Created new project in:" project-dir)
      (println "You should now run 'lein deps' in that directory."))))

(defn new-project
  {:no-service-required true}
  [& args]
  (let [name (first args)
        dir (or (second args) "pallet")]
    (if (.exists (File. dir))
      (do
        (println "Directory" dir "already exists.")
        (System/exit 1))
      (new-project-lein name dir))))
