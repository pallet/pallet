(ns pallet.crate.initd-test
  (:require
   [clojure.test :refer :all]
   [pallet.actions :refer [remote-file]]
   [pallet.api :refer [plan-fn] :as api]
   [pallet.build-actions :as build-actions]
   [pallet.crate.initd :as initd]
   [pallet.crate.service :refer [service-supervisor-config]]
   [pallet.crate.service-test :refer [service-supervisor-test]]
   [pallet.script :refer [defimpl defscript]]
   [pallet.stevedore :refer [fragment]]
   [pallet.test-utils :refer :all]))

(defscript init-script "Return an init script" [])

(defn initd-test [config]
  (service-supervisor-test :initd config {:process-name "sleep 100"}))

(def initd-test-spec
  (api/server-spec
   :extends [(initd/server-spec {})]
   :phases {:settings (plan-fn (service-supervisor-config
                                :initd
                                {:service-name "myjob"
                                 :init-file {:content (fragment (init-script))}}
                                {}))
            :configure (plan-fn
                         (remote-file
                          "/tmp/myjob"
                          :content (fragment
                                    ("#!/bin/bash")
                                    ("exec" "sleep" 100000000))
                          :mode "0755"))
            :test (plan-fn
                    (initd-test {:service-name "myjob"}))}))


(defimpl init-script :default []
  "#! /bin/sh
### BEGIN INIT INFO
# Provides:          myjob
# Required-Start:    $syslog
# Required-Stop:     $syslog
# Default-Start:     3 5
# Default-Stop:      0 1 2 6
# Description:       Start myjob
### END INIT INFO

# Using the lsb functions to perform the operations.
. /lib/lsb/init-functions

# Check for missing binaries
MYJOB_BIN=/tmp/myjob
test -x $MYJOB_BIN || { echo \"$MYJOB_BIN not installed\";
        if [ \"$1\" = \"stop\" ]; then exit 0;
        else exit 5; fi; }

 case \"$1\" in
    start)
        echo -n \"Starting myjob \"
        start-stop-daemon --start --name sleep --background --startas $MYJOB_BIN
        rv=$?
        sleep 2
        exit $rv
        ;;
    stop)
        echo -n \"Shutting down myjob \"
        start-stop-daemon --stop --oknodo --name sleep
        ;;
    restart)
        ## Stop the service and regardless of whether it was
        ## running or not, start it again.
        $0 stop
        $0 start
        ;;
    status)
        echo -n \"Checking for service myjob \"
        start-stop-daemon --status --name sleep
        ;;
    *)
        ## If no parameters are given, print which are avaiable.
        echo \"Usage: $0 {start|stop|status|restart}\"
        exit 1
        ;;
esac")

(defimpl init-script [:suse] []
  "#! /bin/sh
### BEGIN INIT INFO
# Provides:          myjob
# Required-Start:    $syslog
# Required-Stop:     $syslog
# Default-Start:     3 5
# Default-Stop:      0 1 2 6
# Description:       Start myjob
### END INIT INFO

# Check for missing binaries
MYJOB_BIN=/tmp/myjob
test -x $MYJOB_BIN || { echo \"$MYJOB_BIN not installed\";
        if [ \"$1\" = \"stop\" ]; then exit 0;
        else exit 5; fi; }

# Load the rc.status script for this service.
. /etc/rc.status

# Reset status of this service
rc_reset

 case \"$1\" in
    start)
        echo -n \"Starting myjob \"
        ## Start daemon with startproc(8). If this fails
        ## the return value is set appropriately by startproc.
        startproc $MYJOB_BIN

        # Remember status and be verbose
        rc_status -v
        ;;
    stop)
        echo -n \"Shutting down myjob \"
        ## Stop daemon with killproc(8) and if this fails
        ## killproc sets the return value according to LSB.

        killproc -TERM $MYJOB_BIN

        # Remember status and be verbose
        rc_status -v
        ;;
    restart)
        ## Stop the service and regardless of whether it was
        ## running or not, start it again.
        $0 stop
        $0 start

        # Remember status and be quiet
        rc_status
        ;;
    reload)
        # If it supports signaling:
        echo -n \"Reload service myjob \"
        killproc -HUP $MYJOB_BIN
        #touch /var/run/MYJOB.pid
        rc_status -v

        ## Otherwise if it does not support reload:
        #rc_failed 3
        #rc_status -v
        ;;
    status)
        echo -n \"Checking for service myjob \"
        ## Check status with checkproc(8), if process is running
        ## checkproc will return with exit status 0.

        # Return value is slightly different for the status command:
        # 0 - service up and running
        # 1 - service dead, but /var/run/  pid  file exists
        # 2 - service dead, but /var/lock/ lock file exists
        # 3 - service not running (unused)
        # 4 - service status unknown :-(
        # 5--199 reserved (5--99 LSB, 100--149 distro, 150--199 appl.)

        # NOTE: checkproc returns LSB compliant status values.
        checkproc $MYJOB_BIN
        # NOTE: rc_status knows that we called this init script with
        # \"status\" option and adapts its messages accordingly.
        rc_status -v
        ;;
    *)
        ## If no parameters are given, print which are avaiable.
        echo \"Usage: $0 {start|stop|status|restart|reload}\"
        exit 1
        ;;
esac

rc_exit")
