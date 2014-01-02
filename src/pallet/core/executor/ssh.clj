(ns pallet.core.executor.ssh
  "An action executor over ssh"
  (:require
   [pallet.core.executor.protocols :refer :all]
   [pallet.ssh.execute :as ssh]))

(deftype SshActionExecutor [result-chan]
  ActionExecutor
  (execute [executor target user action action-options]
    (ssh/ssh-script-on-target
     session action action-type script)))
