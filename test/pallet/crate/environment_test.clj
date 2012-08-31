(ns pallet.crate.environment-test
  (:use
   [pallet.action.remote-file :only [remote-file]]
   [pallet.build-actions :only [build-session]]
   [pallet.core :only [group-spec lift]]
   [pallet.utils :only [*admin-user* tmpfile with-temporary]]
   [pallet.phase :only [phase-fn]]
   [pallet.script :only [with-script-context]]
   [pallet.stevedore :only [script with-script-language]]
   [pallet.test-utils :only [make-localhost-node test-username]]
   [clojure.java.io :only [file]]
   pallet.crate.environment
   clojure.test
   pallet.test-utils)
  (:require
   [pallet.build-actions :as build-actions]))

(defn- local-test-user
  []
  (assoc *admin-user* :username (test-username) :no-sudo true))

(deftest system-environment-file-test
  (with-script-language :pallet.stevedore.bash/bash
    (is (= ["/etc/environment" true]
           (with-script-context [:ubuntu]
             (system-environment-file
              {:server {:image {:os-family :ubuntu}}}
              "xx" {}))))
    (is (= ["/etc/profile.d/xx.sh" false]
           (with-script-context [:centos]
             (system-environment-file
              {:server {:image {:os-family :centos}}}
              "xx" {}))))))


(deftest service-test
  (is
   (= "echo \"Add testenv environment to /etc/environment...\"\n{ pallet_set_env() {\nk=$1\ns=$2\nif grep \"^${k}=\" /etc/environment; then sed -i -e \"s/^${k}=.*/${s}/\" /etc/environment;else\nsed -i -e \"$ a \\\\\n${s}\" /etc/environment\nfi\n} && pallet_set_env A A=1 && pallet_set_env B B=\"b\"; } || { echo \"Add testenv environment to /etc/environment\" failed; exit 1; } >&2 \necho \"...done\"\n"
    (first
     (build-actions/build-actions {}
       (system-environment "testenv" {"A" 1 :B "b"})))))
  (is
   (=
    "echo \"Add testenv environment to /etc/environment...\"\n{ pallet_set_env() {\nk=$1\ns=$2\nif grep \"^${k}=\" /etc/environment; then sed -i -e \"s/^${k}=.*/${s}/\" /etc/environment;else\nsed -i -e \"$ a \\\\\n${s}\" /etc/environment\nfi\n} && pallet_set_env A 'A=1' && pallet_set_env B 'B=\"b\"'; } || { echo \"Add testenv environment to /etc/environment\" failed; exit 1; } >&2 \necho \"...done\"\n"
    (first
     (build-actions/build-actions {}
       (system-environment "testenv" {"A" 1 :B "b"} :literal true))))))

(deftest service-local-test
  (with-temporary [env-file (tmpfile)]
    (let [user (local-test-user)
          node (make-localhost-node)
          f (file "/etc/environment/pallet-testenv")
          a (atom nil)
          path (.getAbsolutePath env-file)
          get-sysenv (clj-action [session]
                       (reset! a (system-environment-file
                                  session "pallet-testenv" {:path path})))
          local (group-spec "local")]
      (spit env-file "preamble\n")
      (testing "insert"
        (let [result (lift {local node} :user user
                           :phase (phase-fn
                                       (system-environment
                                        "pallet-testenv"
                                        {"a" "$xxxx"}
                                        :literal true
                                        :path path)
                                       (get-sysenv)))
              [path shared] @a]
          (is path)
          (is shared)
          (is (= (.getAbsolutePath env-file) path))
          (if shared
            (do
              (is (re-find #"a=\"\$xxxx\"" (slurp path))))
            (= "a=\"$xxxx\"" (slurp path)))))
      (testing "replace"
        (let [result (lift {local node} :user user
                           :phase (phase-fn
                                       (system-environment
                                        "pallet-testenv"
                                        {"a" "$xxyy"}
                                        :literal true
                                        :path path)
                                       (get-sysenv)))
              [path shared] @a]
          (is path)
          (is shared)
          (is (= (.getAbsolutePath env-file) path))
          (if shared
            (do
              (is (re-find #"a=\"\$xxyy\"" (slurp path)))
              (is (not (re-find #"a=\"\$xxxx\"" (slurp path)))))
            (= "a=\"$xxyy\"" (slurp path)))))
      (.delete f))))
