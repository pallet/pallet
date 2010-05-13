(ns pallet.keychain
  (:require
   [clojure.contrib.shell :as shell]))

(defmulti keychain-passphrase "Obtain password for path" (fn [system path] system))

(defmethod keychain-passphrase :default
  [system path]
  nil)

(defmethod keychain-passphrase "Mac OS X"
  [system path]
  (let [result (shell/sh
                :return-map true
                "/usr/bin/security" "find-generic-password" "-a"
                (format "%s" path)
                "-g")]
    (when (zero? (result :exit))
      (second (re-find #"password: \"(.*)\"" (result :err))))))

(defn passphrase
  "Obtain a passphrase for the given key path"
  [path]
  (keychain-passphrase (System/getProperty "os.name") path))
