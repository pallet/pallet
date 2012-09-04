(ns pallet.repl
  "A namespace that can be used to pull in most of pallet's namespaces.  uesful
  when working at the clojure REPL."
  (:use
   [pallet.utils :exclude [make-user]]
   pallet.api
   pallet.actions
   clj-ssh.ssh))

(defmacro use-pallet
  "Macro that will use pallet's namespaces, to provide an easy to access REPL."
  []
  '(do
     (clojure.core/use
      '[pallet.utils :exclude [make-user]]
      'pallet.api
      'pallet.actions
      'clj-ssh.ssh)))
