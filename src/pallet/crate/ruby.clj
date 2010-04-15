(ns pallet.crate.ruby
 "Installation of ruby from source"
  (:use
   [pallet.resource.package :only [package package-manager]]
   [pallet.resource.exec-script :only [exec-script]]
   [pallet.resource.remote-file :only [remote-file]]
   [pallet.resource.file]
   [pallet.stevedore :only [script defimpl]]
   [pallet.script :only [defscript]]
   [pallet.target :only [packager]]))

(defscript ruby-version [])
(defimpl ruby-version :default []
  (ruby "--version" "|" cut "-f2" "-d' '"))

(def src-packages ["zlib-devel" "gcc" "gcc-c++" "make"
                   "curl-devel" "expat-devel" "gettext-devel"])

(def version-regex
     {"1.8.7-p72" #"1.8.7.*patchlevel 72"})

(def version-md5
     {"1.8.7-p72" "5e5b7189674b3a7f69401284f6a7a36d"})

(defn ftp-path [tarfile]
  (if (.contains tarfile "1.8")
    (str "ftp://ftp.ruby-lang.org/pub/ruby/1.8/" tarfile)
    (str "ftp://ftp.ruby-lang.org/pub/ruby/1.9/" tarfile)))

(defn ruby
  "Install ruby from source"
  ([] (ruby "1.8.7-p72"))
  ([version]
     (let [basename (str "ruby-" version)
           tarfile (str basename ".tar.gz")
           tarpath (str (script (tmp-dir)) "/" tarfile)]
       (doseq [p src-packages]
         (package p))
       (remote-file
        tarpath :url (ftp-path tarfile) :md5 (version-md5 version))
       (exec-script
        (script
         (cd (tmp-dir))
         (tar xfz ~tarfile)
         (cd ~basename)
         ("./configure" "--enable-shared" "--enable-pthread")
         (make)
         (make install)
         (if-not (file-exists? "/usr/bin/ruby")
           (exit 1))
         (cd "ext/zlib")
         (ruby "extconf.rb" "--with-zlib")
         (cd "../../")
         (cd "ext/openssl")
         (ruby "extconf.rb")
         (cd "../../")
         (make)
         (make install))))))


(def ruby-package-names
     {:aptitude
      ["ruby" "ruby-dev" "rdoc" "ri" "irb" "libopenssl-ruby"]
      :yum
      ["ruby" "ruby-devel" "ruby-docs" "ruby-ri" "ruby-rdoc" "ruby-irb"]})


(defn ruby-packages
  "Install ruby from packages"
  []
  (doseq [p (ruby-package-names (packager))]
    (package p)))
