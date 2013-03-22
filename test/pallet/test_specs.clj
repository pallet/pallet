(ns pallet.test-specs
  "Various group specs to test pallet on live infrastructure."
  (:require
   [clojure.java.io :refer [file]]
   [pallet.action :refer [with-action-options]]
   [pallet.actions :refer [delete-local-path directory exec-checked-script
                           exec-script* remote-file rsync-directory]]
   [pallet.api :refer [group-spec plan-fn]]
   [pallet.crate :refer [admin-user defplan]]
   [pallet.crate.environment :refer [system-environment]]
   [pallet.script-test :refer [testing-script is-true is=]]
   [pallet.utils :refer [tmpdir tmpfile]]))

(def characters (apply vector "ABCDEFGHIJKLMNOPQRSTUVWXYZ123456789"))
(defn rand-char [] (characters (rand-int (count characters))))
(defn rand-str [n] (apply str (take n (repeatedly rand-char))))

(def remote-file-test
  (let [s (rand-str 10)]
    (group-spec "remote-file"
      :phases {:configure (plan-fn
                            (let [f (tmpfile)
                                  u (:username (admin-user))]
                              (spit f s)
                              (directory "some/path")
                              (remote-file "some/path/file"
                                           :local-file (.getPath f)
                                           :owner u)
                              (directory "/var/lib")
                              (remote-file "/var/lib/file"
                                           :local-file (.getPath f))
                              (delete-local-path (.getPath f))))
               :test (plan-fn
                       (let [u (:username (admin-user))]
                         (exec-script*
                          (testing-script
                           "remote-file"
                           (is-true
                            (file-exists? "some/path/file")
                            "local-file copied correctly")
                           (is=
                            ~u
                            @("stat" "-c" "%U" "some/path/file")
                            "local-file has correct ownership")
                           (is-true
                            (file-exists?
                             (str "/var/lib/pallet/admin-home/" ~u
                                  "/some/path/file"))
                            "local-file copied correctly to parallel tree")
                           (is-true
                            (file-exists?
                             (str "/var/lib/pallet/admin-home/" ~u
                                  "/some/path/file.md5"))
                            "local-file generated md5 in parallel tree")
                           (is-true
                            (file-exists? "/var/lib/file")
                            "local-file copied correctly")))))}
      :roles #{:live-test :remote-file})))

(def rsync-test
  (let [s (rand-str 10)]
    (group-spec "rsync-test"
      :phases {:configure (plan-fn
                            (let [d (tmpdir)
                                  f (file d "afile")
                                  u (:username (admin-user))]
                              (spit f s)
                              (directory "some/path" :owner "duncan")
                              (rsync-directory
                               (str (.getPath d) "/")
                               "some/path/")
                              (delete-local-path (.getPath f))))
               :test (plan-fn
                       (let [u (:username (admin-user))]
                         (exec-script*
                          (testing-script
                           "remote-file"
                           (is-true
                            (file-exists? "some/path/afile")
                            "rsync copied correctly")))))}
      :roles #{:live-test :rsync})))

(def environment-test
  (group-spec "env-test"
    :phases {:configure (plan-fn
                          (system-environment "test" {"PALLET_TEST" "123"}))
             :test (plan-fn
                     (with-action-options {:prefix :no-sudo :script-trace true}
                       (exec-script*
                        (testing-script
                         "environment"
                         (is=
                          "123" @PALLET_TEST
                          "environment set ok"))))
                     (with-action-options {:prefix :no-sudo :script-trace true}
                       (exec-script*
                        (testing-script
                         "environment"
                         (is=
                          "123" @PALLET_TEST
                          "environment set ok without sudo")))))}
    :roles #{:live-test :env}))
