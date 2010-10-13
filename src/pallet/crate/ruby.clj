(ns pallet.crate.ruby
  "Installation of ruby from source"
  (:require
   [pallet.resource :as resource]
   [pallet.stevedore :as stevedore]
   [pallet.resource.package :as package]
   [clojure.string :as string])
  (:use
   [pallet.resource.package :only [package package* package-manager]]
   [pallet.resource.exec-script :only [exec-script]]
   [pallet.resource.remote-file :only [remote-file]]
   [pallet.resource.file]
   [pallet.script :only [defscript]]
   pallet.thread-expr))

(defscript ruby-version [])
(stevedore/defimpl ruby-version :default []
  (ruby "--version" "|" cut "-f2" "-d' '"))

(def src-packages ["zlib-devel"  "gcc" "gcc-c++" "make"
                   "curl-devel" "expat-devel" "gettext-devel"
                   "libncurses5-dev" "libreadline-dev"
                   ;; ubuntu 10.04
                   "zlib1g" "zlib1g-dev" "zlibc"
                   "libssl-dev"])

(def version-regex
     {"1.8.7-p72" #"1.8.7.*patchlevel 72"})

(def version-md5
     {"1.8.7-p72" "5e5b7189674b3a7f69401284f6a7a36d"
      "1.8.7-p299" "43533980ee0ea57381040d4135cf9677"})

(defn ftp-path [tarfile]
  (cond
   (.contains tarfile "1.8") (str "ftp://ftp.ruby-lang.org/pub/ruby/1.8/" tarfile)
   (.contains tarfile "stable") (str "ftp://ftp.ruby-lang.org/pub/ruby/" tarfile)
   :else (str "ftp://ftp.ruby-lang.org/pub/ruby/1.9/" tarfile)))

(defn ruby
  "Install ruby from source"
  ([request] (ruby request "1.8.7-p72"))
  ([request version]
     (let [basename (str "ruby-" version)
           tarfile (str basename ".tar.gz")
           tarpath (str (stevedore/script (tmp-dir)) "/" tarfile)]
       (->
        request
        (for-> [p src-packages]
          (package p))
        (remote-file
         tarpath :url (ftp-path tarfile) :md5 (version-md5 version))
        (exec-script
         (if-not (pipe ("ruby" "--version")
                       (grep (quoted ~(string/replace version "-p" ".*"))))
           (do
             ~(stevedore/checked-script
               "Building ruby"
               (cd (tmp-dir))
               (tar xfz ~tarfile)
               (cd ~basename)
               ("./configure" "--enable-shared" "--enable-pthread")
               (make)
               (make install)
               (if-not (|| (file-exists? "/usr/bin/ruby")
                           (file-exists? "/usr/local/bin/ruby"))
                 (do (println "Could not find ruby executable")
                     (exit 1)))
               (cd "ext/zlib")
               (ruby "extconf.rb" "--with-zlib")
               (make)
               (make install)
               (cd "../../")
               (cd "ext/openssl")
               (ruby "extconf.rb")
               (make)
               (make install)
               (cd "../../")
               (cd "ext/readline")
               (ruby "extconf.rb")
               (make)
               (make install)
               (cd "../../")
               (make)
               (make install)))))))))

(defn ruby-packages
  "Install ruby from packages"
  [request]
  (package/packages
   request
   :aptitude
   ["ruby" "ruby-dev" "rdoc" "ri" "irb" "libopenssl-ruby" "libzlib-ruby"]
   :yum
   ["ruby" "ruby-devel" "ruby-docs" "ruby-ri" "ruby-rdoc" "ruby-irb"]))
