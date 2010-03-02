(ns pallet.template-test
  (:use [pallet.template] :reload-all)
  (:use clojure.test
        pallet.test-utils))

(with-private-vars [pallet.template [apply-template-file]]
  (deftest apply-template-file-test
    (is (= "file=/etc/file
cat > ${file} <<EOF
some content
EOF"
           (apply-template-file [{:path "/etc/file"} "some content"]))))

  (deftest apply-template-file-test
    (is (= "file=/etc/file
cat > ${file} <<EOF
some content
EOF
chmod 0666 ${file}
chgrp grp ${file}
chown user ${file}"
           (apply-template-file [{:path "/etc/file" :owner "user" :mode "0666" :group "grp"}
                                 "some content"])))))

(deftest apply-template-file-test
    (is (= "file=/etc/file
cat > ${file} <<EOF
some content
EOF
file=/etc/file2
cat > ${file} <<EOF
some content2
EOF
"
           (apply-templates (fn [] {{:path "/etc/file"} "some content"
                                    {:path "/etc/file2"} "some content2"})
                            nil))))
