(ns pallet.crate.authorize-key-test
  (:use [pallet.crate.authorize-key] :reload-all)
  (:require [pallet.template :only [apply-templates]]
            [pallet.resource :only [build-resources]])
  (:use clojure.test
        pallet.test-utils
        [clojure.contrib.java-utils :only [file]]))

(deftest authorized-keys-template-test
  (is (= "file=$(getent passwd userx | cut -d: -f6)/.ssh/authorized_keys
cat > ${file} <<EOF\nkey1\nkey2\nEOF
chmod 0644 ${file}
chown userx ${file}
"
         (pallet.template/apply-templates authorized-keys-template ["userx" ["key1" "key2"]] ))))

(with-private-vars [pallet.crate.authorize-key
                    [produce-authorize-key]]
  (deftest produce-authorize-key-test
    (is (= "dir=$(getent passwd userx | cut -d: -f6)/.ssh
mkdir -p ${dir}
chmod 755 ${dir}
chown userx ${dir}
file=$(getent passwd userx | cut -d: -f6)/.ssh/authorized_keys
cat > ${file} <<EOF
key1
key2
EOF
chmod 0644 ${file}
chown userx ${file}
"
           (produce-authorize-key ["userx" ["key1" "key2"]] )))))

(deftest authorize-key-test
  (is (= "dir=$(getent passwd user2 | cut -d: -f6)/.ssh
mkdir -p ${dir}
chmod 755 ${dir}
chown user2 ${dir}
file=$(getent passwd user2 | cut -d: -f6)/.ssh/authorized_keys
cat > ${file} <<EOF
key3
EOF
chmod 0644 ${file}
chown user2 ${file}
dir=$(getent passwd user | cut -d: -f6)/.ssh
mkdir -p ${dir}
chmod 755 ${dir}
chown user ${dir}
file=$(getent passwd user | cut -d: -f6)/.ssh/authorized_keys
cat > ${file} <<EOF
key1
key2
EOF
chmod 0644 ${file}
chown user ${file}
"
         ((pallet.resource/build-resources
           (authorize-key "user" "key1")
           (authorize-key "user" "key2")
           (authorize-key "user2" "key3"))))))
