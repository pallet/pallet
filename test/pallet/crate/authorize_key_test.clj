(ns pallet.crate.authorize-key-test
  (:use [pallet.crate.authorize-key] :reload-all)
  (:require [pallet.template :only [apply-templates]]
            [pallet.resource :only [build-resources]])
  (:use clojure.test
        pallet.test-utils
        [clojure.contrib.java-utils :only [file]]))

(with-private-vars [pallet.crate.authorize-key
                    []])

(deftest authorized-keys-template-test
  (is (= "file=$(getent passwd userx | cut -d: -f6)/.ssh/authorized_keys
cat > ${file} <<EOF\nkey1\nkey2\nEOF
chmod 0644 ${file}
chown userx ${file}
"
         (pallet.template/apply-templates authorized-keys-template ["userx" ["key1" "key2"]] ))))

(deftest authorize-key-test
  (is (= "file=$(getent passwd user2 | cut -d: -f6)/.ssh/authorized_keys
cat > ${file} <<EOF\nkey3\nEOF
chmod 0644 ${file}
chown user2 ${file}
file=$(getent passwd user | cut -d: -f6)/.ssh/authorized_keys
cat > ${file} <<EOF\nkey1\nkey2\nEOF
chmod 0644 ${file}
chown user ${file}
"
         ((pallet.resource/build-resources
           (authorize-key "user" "key1")
           (authorize-key "user" "key2")
           (authorize-key "user2" "key3"))))))
