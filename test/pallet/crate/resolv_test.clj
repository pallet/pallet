(ns pallet.crate.resolv-test
  (:use pallet.crate.resolv)
  (:use clojure.test
        pallet.test-utils))

(use-fixtures :once with-ubuntu-script-template)

(with-private-vars [pallet.crate.resolv
                    [write-key-value write-option write-options write
                     merge-resolve-spec]]

  (deftest write-key-value-test
    (is (= "domain name\n" (write-key-value "domain" "name"))))

  (deftest write-option-test
    (is (= "key" (write-option ["key" true])))
    (is (= "key" (write-option [:key true])))
    (is (= "key:value" (write-option ["key" "value"])))
    (is (= "key:value" (write-option [:key :value]))))

  (deftest write-options-test
    (is (= "options key1 key2:value\n"
           (write-options {:key1 true :key2 "value"}))))

  (deftest write-test
    (is (= "domain domainname.somewhere.com
nameserver 123.123.123.123
nameserver 234.234.234.234
search somewhere.com
sortlist 130.155.160.0/255.255.240.0 130.155.0.0
options key1 key2:value
" (write "domainname.somewhere.com"
         ["123.123.123.123" "234.234.234.234"]
         ["somewhere.com"]
         ["130.155.160.0/255.255.240.0" "130.155.0.0"]
         {:key1 true :key2 "value"}))))

  (deftest write-default-domain-test
    (is (= "domain $(dnsdomainname)
nameserver 123.123.123.123
nameserver 234.234.234.234
search somewhere.com
sortlist 130.155.160.0/255.255.240.0 130.155.0.0
options key1 key2:value
" (write nil
         ["123.123.123.123" "234.234.234.234"]
         ["somewhere.com"]
         ["130.155.160.0/255.255.240.0" "130.155.0.0"]
         {:key1 true :key2 "value"}))))

  (deftest merge-resolve-spec-test
    (is (= ["domain" ["ns1" "ns2"] ["s1" "s2"] ["r1" "r2"] {:a 1 :b 2}]
           (merge-resolve-spec
            ["domain" ["ns1"] ["s1"] ["r1"] {:a 1}]
            ["domain" ["ns2"] :search "s2" :sortlist "r2" :b 2])))))

(deftest test-resolv-example
  (is (=
       "file=/etc/resolv.conf
cat > ${file} <<EOF
domain domain
nameserver 123.123.123.123
nameserver 123.123.123.123
search some.domain
options sort:123.12.32.12 rotate attempts:2

EOF
chmod 0644 ${file}
chown root ${file}
"
       (first
        (build-resources
         []
         (resolv "domain" ["123.123.123.123" "123.123.123.123"]
                 :search "some.domain" :sort "123.12.32.12"
                 :rotate true :attempts 2))))))
