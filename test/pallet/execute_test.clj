(ns pallet.execute-test
  (:use pallet.execute)
  (:use clojure.test
        pallet.test-utils
        clojure.tools.logging)
  (:require
   [clj-ssh.ssh :as ssh]
   [pallet.action-plan :as action-plan]
   [pallet.common.logging.logutils :as logutils]
   [pallet.compute.jvm :as jvm]
   [pallet.compute :as compute]
   [pallet.core :as core]
   [pallet.execute :as execute]
   [pallet.test-utils :as test-utils]
   [pallet.utils :as utils]
   [pallet.script :as script]))

(use-fixtures :once (logutils/logging-threshold-fixture))
