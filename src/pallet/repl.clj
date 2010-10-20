(ns pallet.repl
  "A namespace that can be used to pull in most of pallet's namespaces.  uesful
  when working at the clojure REPL."
  (:use
   [org.jclouds.compute
    :exclude [destroy-node nodes run-nodes destroy-nodes-with-tag
              terminated? id running? tag hostname compute-service]]
   pallet.utils
   [pallet.compute :exclude [make-node]]
   pallet.core
   pallet.resource
   pallet.resource.package
   pallet.crate.automated-admin-user
   pallet.crate.public-dns-if-no-nameserver
   pallet.task.feedback
   clj-ssh.ssh))

(defmacro use-pallet
  "Macro that will use pallet's namespaces, to provide an easy to access REPL."
  []
  '(do
     (clojure.core/use
      '[org.jclouds.compute
        :exclude [destroy-node nodes run-nodes destroy-nodes-with-tag
                  terminated? id running? tag hostname compute-service]]
      'pallet.utils
      '[pallet.compute :exclude [make-node]]
      'pallet.core
      'pallet.maven
      'pallet.resource
      'pallet.resource.package
      'pallet.crate.automated-admin-user
      'pallet.crate.public-dns-if-no-nameserver
      'pallet.task.feedback
      'clj-ssh.ssh)))
