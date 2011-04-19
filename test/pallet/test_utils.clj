(ns pallet.test-utils
  (:require
   [pallet.core :as core]
   [pallet.execute :as execute]
   [pallet.target :as target]
   [pallet.script :as script]
   [pallet.parameter :as parameter]
   [pallet.compute.node-list :as node-list]
   [pallet.utils :as utils]
   [clojure.java.io :as io]
   clojure.contrib.logging)
  (:use clojure.test))

(defmacro with-private-vars [[ns fns] & tests]
  "Refers private fns from ns and runs tests in context.  From users mailing
list, Alan Dipert and MeikelBrandmeyer."
  `(let ~(reduce #(conj %1 %2 `@(ns-resolve '~ns '~%2)) [] fns)
     ~@tests))

(defmacro logging-to-stdout
  "Send log messages to stdout for inspection"
  [& forms]
  `(binding [clojure.contrib.logging/impl-write! (fn [_# _# msg# _#]
                                                   (println msg#))]
     ~@forms))

(defmacro suppress-logging
  "Prevent log messages to reduce test log noise"
  [& forms]
  `(binding [clojure.contrib.logging/impl-write! (fn [& _#])]
     ~@forms))

(def dev-null
  (proxy [java.io.OutputStream] []
    (write ([i-or-bytes])
           ([bytes offset len]))))

(defmacro suppress-output
  "Prevent stdout to reduce test log noise"
  [& forms]
  `(binding [*out* (io/writer dev-null)]
    ~@forms))

(def null-print-stream
  (java.io.PrintStream. dev-null))

(defn no-output-fixture
  "A fixture for no output from tests"
  [f]
  (let [out# System/out]
    (System/setOut null-print-stream)
    (try
      (f)
      (finally (System/setOut out#)))))

(def log-priorities
  {:warn org.apache.log4j.Priority/WARN
   :debug org.apache.log4j.Priority/DEBUG
   :fatal org.apache.log4j.Priority/FATAL
   :info org.apache.log4j.Priority/INFO
   :error org.apache.log4j.Priority/ERROR})

(defn console-logging-threshold
  "A fixture for no output from tests"
  ([] (console-logging-threshold :error))
  ([level]
     (fn [f]
       (let [console (.. (org.apache.log4j.Logger/getRootLogger)
                         (getAppender "console"))
             threshold (.getThreshold console)]
         (try
           (.setThreshold
            console (level log-priorities org.apache.log4j.Priority/WARN))
           (f)
           (finally
            (.setThreshold console threshold)))))))

(defmacro with-console-logging-threshold
  "A scope for no output from tests"
  [level & body]
  `((console-logging-threshold ~level) (fn [] ~@body)))

(defmacro bash-out
  "Check output of bash. Macro so that errors appear on the correct line."
  ([str] `(bash-out ~str 0 ""))
  ([str exit err-msg]
     `(let [r# (suppress-logging
                (execute/bash ~str))]
       (is (= ~err-msg (:err r#)))
       (is (= ~exit (:exit r#)))
       (:out r#))))

(defn test-username
  "Function to get test username. This is a function to avoid issues with AOT."
  [] (or (. System getProperty "ssh.username")
         (. System getProperty "user.name")))

(def ubuntu-session {:server {:image {:os-family :ubuntu}}})
(def centos-session {:server {:image {:os-family :centos}}})

(defn with-ubuntu-script-template
  [f]
  (script/with-script-context [:ubuntu]
    (f)))

(defn make-node
  "Simple node for testing"
  [tag & {:as options}]
  (apply
   node-list/make-node
   tag (:group-name options (:tag options tag))
   (:ip options "1.2.3.4") (:os-family options :ubuntu)
   (apply concat options)))

(defn make-localhost-node
  "Simple localhost node for testing"
  [& {:as options}]
  (apply node-list/make-localhost-node (apply concat options)))

(defmacro build-resources
  "Forwarding definition"
  [& args]
  `(do
     (require 'pallet.build-actions)
     (utils/deprecated-macro
      ~&form
      (utils/deprecate-rename
       'pallet.test-utils/build-resources
       'pallet.build-actions/build-actions))
     ((resolve 'pallet.build-actions/build-actions) ~@args)))

(defn test-session
  "Build a test session"
  [& components]
  (reduce merge components))

(defn server
  "Build a server for the session map"
  [& {:as options}]
  (apply core/server-spec (apply concat options)))

(defn target-server
  "Build the target server for the session map"
  [& {:as options}]
  {:server (apply core/server-spec (apply concat options))})

(defn group
  "Build a group for the session map"
  [name & {:as options}]
  (apply core/group-spec name (apply concat options)))

(defn target-group
  "Build the target group for the session map"
  [name & {:as options}]
  {:group (apply core/group-spec name (apply concat options))})
