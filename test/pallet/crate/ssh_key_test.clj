(ns pallet.crate.ssh-key-test
  (:use [pallet.crate.ssh-key] :reload-all)
  (:require [pallet.template :only [apply-templates]]
            [pallet.resource :only [build-resources]])
  (:use clojure.test
        pallet.test-utils))

(deftest authorized-keys-template-test
  (is (= "file=$(getent passwd userx | cut -d: -f6)/.ssh/authorized_keys
cat > ${file} <<EOF\nkey1\nkey2\nEOF
chmod 0644 ${file}
chown userx ${file}
"
         (pallet.template/apply-templates authorized-keys-template ["userx" ["key1" "key2"]] ))))

(with-private-vars [pallet.crate.ssh-key
                    [produce-authorize-key]]
  (deftest produce-authorize-key-test
    (is (= "mkdir -p $(getent passwd userx | cut -d: -f6)/.ssh/\nchown  userx $(getent passwd userx | cut -d: -f6)/.ssh/\nchmod  755 $(getent passwd userx | cut -d: -f6)/.ssh/\nfile=$(getent passwd userx | cut -d: -f6)/.ssh/authorized_keys\ncat > ${file} <<EOF\nkey1\nkey2\nEOF\nchmod 0644 ${file}\nchown userx ${file}\n"
           (produce-authorize-key ["userx" ["key1" "key2"]] )))))

(deftest authorize-key-test
  (is (= "mkdir -p $(getent passwd user2 | cut -d: -f6)/.ssh/\nchown  user2 $(getent passwd user2 | cut -d: -f6)/.ssh/\nchmod  755 $(getent passwd user2 | cut -d: -f6)/.ssh/\nfile=$(getent passwd user2 | cut -d: -f6)/.ssh/authorized_keys\ncat > ${file} <<EOF\nkey3\nEOF\nchmod 0644 ${file}\nchown user2 ${file}\nmkdir -p $(getent passwd user | cut -d: -f6)/.ssh/\nchown  user $(getent passwd user | cut -d: -f6)/.ssh/\nchmod  755 $(getent passwd user | cut -d: -f6)/.ssh/\nfile=$(getent passwd user | cut -d: -f6)/.ssh/authorized_keys\ncat > ${file} <<EOF\nkey1\nkey2\nEOF\nchmod 0644 ${file}\nchown user ${file}\n"
         (pallet.resource/build-resources []
          (authorize-key "user" "key1")
          (authorize-key "user" "key2")
          (authorize-key "user2" "key3")))))

(deftest install-key*-test
  (is (= "mkdir -p $(getent passwd fred | cut -d: -f6)/.ssh/\nchown  fred $(getent passwd fred | cut -d: -f6)/.ssh/\nchmod  755 $(getent passwd fred | cut -d: -f6)/.ssh/\ncat > $(getent passwd fred | cut -d: -f6)/.ssh//id <<EOF\nprivate\nEOF\nchown  fred $(getent passwd fred | cut -d: -f6)/.ssh//id\nchmod  600 $(getent passwd fred | cut -d: -f6)/.ssh//id\ncat > $(getent passwd fred | cut -d: -f6)/.ssh//id.pub <<EOF\npublic\nEOF\nchown  fred $(getent passwd fred | cut -d: -f6)/.ssh//id.pub\nchmod  644 $(getent passwd fred | cut -d: -f6)/.ssh//id.pub\n"
         (install-key* "fred" "id" "private" "public"))))

(deftest install-key-test
  (is (= "mkdir -p $(getent passwd fred | cut -d: -f6)/.ssh/\nchown  fred $(getent passwd fred | cut -d: -f6)/.ssh/\nchmod  755 $(getent passwd fred | cut -d: -f6)/.ssh/\ncat > $(getent passwd fred | cut -d: -f6)/.ssh//id <<EOF\nprivate\nEOF\nchown  fred $(getent passwd fred | cut -d: -f6)/.ssh//id\nchmod  600 $(getent passwd fred | cut -d: -f6)/.ssh//id\ncat > $(getent passwd fred | cut -d: -f6)/.ssh//id.pub <<EOF\npublic\nEOF\nchown  fred $(getent passwd fred | cut -d: -f6)/.ssh//id.pub\nchmod  644 $(getent passwd fred | cut -d: -f6)/.ssh//id.pub\n"
         (pallet.resource/build-resources [] (install-key "fred" "id" "private" "public")))))

(deftest generate-key*-test
  (is (= "mkdir -p $(getent passwd fred | cut -d: -f6)/.ssh/\nchown  fred $(getent passwd fred | cut -d: -f6)/.ssh/\nchmod  755 $(getent passwd fred | cut -d: -f6)/.ssh/\nkey_path=$(getent passwd fred | cut -d: -f6)/.ssh/id_rsa\nif [ ! -e ${key_path} ]; then ssh-keygen -f ${key_path} -t rsa -N \"\";fi\ntouch  ${key_path}\nchown  fred ${key_path}\nchmod  0600 ${key_path}\ntouch  ${key_path}.pub\nchown  fred ${key_path}.pub\nchmod  0644 ${key_path}.pub\n"
         (generate-key* "fred")))
  (is (= "mkdir -p $(getent passwd fred | cut -d: -f6)/.ssh/\nchown  fred $(getent passwd fred | cut -d: -f6)/.ssh/\nchmod  755 $(getent passwd fred | cut -d: -f6)/.ssh/\nkey_path=$(getent passwd fred | cut -d: -f6)/.ssh/id_dsa\nif [ ! -e ${key_path} ]; then ssh-keygen -f ${key_path} -t dsa -N \"\";fi\ntouch  ${key_path}\nchown  fred ${key_path}\nchmod  0600 ${key_path}\ntouch  ${key_path}.pub\nchown  fred ${key_path}.pub\nchmod  0644 ${key_path}.pub\n"
         (generate-key* "fred" :type "dsa")))
  (is (= "mkdir -p $(getent passwd fred | cut -d: -f6)/.ssh/\nchown  fred $(getent passwd fred | cut -d: -f6)/.ssh/\nchmod  755 $(getent passwd fred | cut -d: -f6)/.ssh/\nkey_path=$(getent passwd fred | cut -d: -f6)/.ssh/identity\nif [ ! -e ${key_path} ]; then ssh-keygen -f ${key_path} -t rsa1 -N \"\";fi\ntouch  ${key_path}\nchown  fred ${key_path}\nchmod  0600 ${key_path}\ntouch  ${key_path}.pub\nchown  fred ${key_path}.pub\nchmod  0644 ${key_path}.pub\n"
         (generate-key* "fred" :type "rsa1"))))

(deftest authorize-key-for-localhost*-test
  (is (= "key_file=$(getent passwd fred | cut -d: -f6)/.ssh/id_dsa.pub\nauth_file=$(getent passwd fred | cut -d: -f6)/.ssh/authorized_keys\ntouch  $(getent passwd fred | cut -d: -f6)/.ssh/authorized_keys\nchown  fred $(getent passwd fred | cut -d: -f6)/.ssh/authorized_keys\nchmod  644 $(getent passwd fred | cut -d: -f6)/.ssh/authorized_keys\nif ! grep $(cat ${key_file}) ${auth_file}; then cat ${key_file} >> ${auth_file};fi\n"
         (authorize-key-for-localhost* "fred" "id_dsa.pub")))
  (is (= "key_file=$(getent passwd fred | cut -d: -f6)/.ssh/id_dsa.pub\nauth_file=$(getent passwd tom | cut -d: -f6)/.ssh/authorized_keys\ntouch  $(getent passwd tom | cut -d: -f6)/.ssh/authorized_keys\nchown  tom $(getent passwd tom | cut -d: -f6)/.ssh/authorized_keys\nchmod  644 $(getent passwd tom | cut -d: -f6)/.ssh/authorized_keys\nif ! grep $(cat ${key_file}) ${auth_file}; then cat ${key_file} >> ${auth_file};fi\n"
         (authorize-key-for-localhost* "fred" "id_dsa.pub" :authorize-for-user "tom"))))

