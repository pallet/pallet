(ns pallet.ssh.execute-test
  (:require
   [clojure.string :refer [trim]]
   [clojure.test :refer :all]
   [clojure.tools.logging :refer [debugf]]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture
                                           with-log-to-string]]
   [pallet.compute.node-list :refer [make-localhost-node]]
   [pallet.core.node-os :refer [with-script-for-node]]
   [pallet.ssh.execute
    :refer [get-connection ssh-script-on-target with-connection]]
   [pallet.transport :as transport]
   [pallet.user :refer [*admin-user*]]))

(use-fixtures :once (logging-threshold-fixture))

(def open-channel clj-ssh.ssh/open-channel)

;; TODO
;; (deftest with-connection-test
;;   (let [session {:server {:node (make-localhost-node)
;;                           :image {:os-family :ubuntu}}
;;                  :user *admin-user*}]
;;     (testing "default"
;;       (with-connection session
;;         [connection]
;;         (is connection)))
;;     (testing "caching"
;;       (let [original-connection (get-connection session)]
;;         (with-connection session
;;           [connection]
;;           (is connection))
;;         (is (= original-connection (get-connection session))
;;             "connection cached")
;;         (is (transport/open? (get-connection session)))))
;;     (testing "fail on general open-channel exception"
;;       (let [log-out
;;             (with-log-to-string []
;;               (with-redefs [clj-ssh.ssh/open-channel
;;                             (fn [session session-type]
;;                               (throw
;;                                (ex-info
;;                                 (format
;;                                  "clj-ssh open-channel failure: %s" "something")
;;                                 {:type :clj-ssh/open-channel-failure
;;                                  :reason :clj-ssh/unknown}
;;                                 (com.jcraft.jsch.JSchException.
;;                                  "something"))))]
;;                 (try
;;                   (with-connection session
;;                     [connection]
;;                     (transport/exec connection {:execv ["echo" "1"]} {})
;;                     (is nil))           ; should never get here
;;                   (is nil)              ; should never get here
;;                   (catch Exception e
;;                     (is (instance? com.jcraft.jsch.JSchException (.getCause e)))
;;                     (is (= :clj-ssh/unknown (:reason (ex-data e))))
;;                     (is (= :clj-ssh/open-channel-failure
;;                            (:type (ex-data e))))))))]
;;         (is log-out)))
;;     (testing "retry on session is not opened open-channel exception"
;;       (let [a (atom nil)
;;             seen (atom nil)
;;             c (atom 0)
;;             original-connection (get-connection session)
;;             log-out
;;             (with-log-to-string []
;;               (with-redefs [clj-ssh.ssh/open-channel
;;                             (fn [session session-type]
;;                               (swap! c inc)
;;                               (debugf (Exception. "here") "Inc")
;;                               (if @a
;;                                 (open-channel session session-type)
;;                                 (do
;;                                   (reset! a true)
;;                                   (throw
;;                                    (ex-info
;;                                     (format "clj-ssh open-channel failure")
;;                                     {:type :clj-ssh/open-channel-failure
;;                                      :reason :clj-ssh/channel-open-failed}
;;                                     (com.jcraft.jsch.JSchException.
;;                                      "some exception"))))))]
;;                 (with-connection session
;;                   [connection]
;;                   (transport/exec connection {:execv ["echo" "1"]} {})
;;                   (reset! seen true))
;;                 (is @seen)
;;                 (is (= 3 @c))           ; 1 failed + sftp +exec
;;                 (is (not= original-connection (get-connection session))
;;                     "new cached connection")))]
;;         (is log-out "exception is logged")))
;;     (testing "new session after :new-login-after-action"
;;       (with-script-for-node (:server session) nil
;;         (let [original-connection (get-connection session)]
;;           (ssh-script-on-target
;;            session {:node-value-path (keyword (name (gensym "nv")))}
;;            nil [{} "echo 1"])
;;           (is (= original-connection (get-connection session)))
;;           (ssh-script-on-target
;;            session {:node-value-path (keyword (name (gensym "nv")))
;;                     :new-login-after-action true}
;;            nil [{} "echo 1"])
;;           (is (not= original-connection (get-connection session)))
;;           (let [second-connection (get-connection session)]
;;             (ssh-script-on-target
;;              session {:node-value-path (keyword (name (gensym "nv")))}
;;              nil [{} "echo 1"])
;;             (is (not= original-connection (get-connection session)))
;;             (is (= second-connection (get-connection session)))))))
;;     (testing "agent-forward"
;;       (with-script-for-node (:server session) nil
;;         (let [[r s] (ssh-script-on-target
;;                      session {:node-value-path (keyword (name (gensym "nv")))}
;;                      nil [{} "echo $SSH_AUTH_SOCK"])]
;;           (is (= "" (trim (:out r)))))
;;         ;; TODO Fix this
;;         ;; (let [[r s] (ssh-script-on-target
;;         ;;              session {:node-value-path (keyword (name (gensym "nv")))
;;         ;;                       :ssh-agent-forwarding true}
;;         ;;              nil [{} "echo $SSH_AUTH_SOCK"])]
;;         ;;   (is (not= "" (trim (:out r)))))
;;         ))
;;     (testing "env"
;;       (with-script-for-node (:server session) nil
;;         (let [[r s] (ssh-script-on-target
;;                      session {:node-value-path (keyword (name (gensym "nv")))}
;;                      nil [{} "echo $XXX"])]
;;           (is (= "" (trim (:out r)))))
;;         (let [[r s] (ssh-script-on-target
;;                      session {:node-value-path (keyword (name (gensym "nv")))
;;                               :script-env {:XXX "abcd"}}
;;                      nil [{} "echo $XXX"])]
;;           (is (= "abcd" (trim (:out r)))))))
;;     (testing "env-fwd"
;;       (with-script-for-node (:server session) nil
;;         (let [[r s] (ssh-script-on-target
;;                      session {:node-value-path (keyword (name (gensym "nv")))}
;;                      nil [{} "echo $SSH_CLIENT"])]
;;           (is (= "" (trim (:out r)))))
;;         (let [[r s] (ssh-script-on-target
;;                      session {:node-value-path (keyword (name (gensym "nv")))
;;                               :script-env-fwd [:SSH_CLIENT]}
;;                      nil [{} "echo $SSH_CLIENT"])]
;;           (is (= "127.0.0.1" (subs (:out r) 0 9))))))))
