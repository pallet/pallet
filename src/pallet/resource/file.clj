(ns pallet.resource.file
  "Compatability namespace"
  (:require
   pallet.action.file
   pallet.script.lib
   [pallet.utils :as utils]))

(utils/forward-to-script-lib
 rm mv cp ln backup-option basename ls cat tail diff cut chown chgrp chmod
 touch md5sum md5sum-verify sed-file download-file tmp-dir make-temp-file
 heredoc-in heredoc)

(utils/forward-fns
 pallet.action.file
 adjust-file write-md5-for-file touch-file
 file symbolic-link fifo sed)
