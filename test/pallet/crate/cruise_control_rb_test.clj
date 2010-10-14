(ns pallet.crate.cruise-control-rb-test
  (:use pallet.crate.cruise-control-rb)
  (:require
   [pallet.template :only [apply-templates]]
   [pallet.core :as core]
   [pallet.stevedore :as stevedore]
   [pallet.resource :as resource]
   [pallet.resource.service :as service]
   [pallet.target :as target]
   [net.cgrand.enlive-html :as xml])
  (:use clojure.test
        pallet.test-utils))

(use-fixtures :once with-ubuntu-script-template)

(deftest cruise-control-rb-job-test
  (is (= (stevedore/checked-script
          "cruise-control-rb"
          (export CRUISE_DATA_ROOT=/var/lib/cruisecontrolrb)
          (if-not (file-exists? "/var/lib/cruisecontrolrb/projects/name")
            (sudo
             -u ccrb
             "CRUISE_DATA_ROOT=/var/lib/cruisecontrolrb"
             "/opt/cruisecontrolrb/cruise"
             add name
             --repository "git://host/repo.git"
             --source-control "git ")))
         (first
          (build-resources
           [] (cruise-control-rb-job "name" "git://host/repo.git"))))))

(deftest cruise-control-rb-init-test
  (is (= (first
          (build-resources
           []
           (service/init-script
            "cruisecontrol.rb"
            :content "### BEGIN INIT INFO\n# Provides:          cruisecontrol.rb\n# Required-Start:    $local_fs $remote_fs\n# Required-Stop:     $local_fs $remote_fs\n# Default-Start:     2 3 4 5\n# Default-Stop:      S 0 1 6\n# Short-Description: CruiseControl.rb\n# Description:       Continuous build integration system. This runs the web interface (via mongrel and the builders).\n### END INIT INFO\n\n# Author: Robert Coup <robert.coup@onetrackmind.co.nz>\n#\n# Please remove the \"Author\" lines above and replace them\n# with your own name if you copy and modify this script.\n\n# Do NOT \"set -e\"\n\n\n# PATH should only include /usr/* if it runs after the mountnfs.sh script\nPATH=/usr/sbin:/usr/bin:/sbin:/bin:/usr/local/bin:/var/lib/gems/1.8/bin/\nCCRBDIR=/opt/cruisecontrolrb\nDESC=\"Continous Integration\"\nNAME=cruisecontrolrb\nDAEMON_ARGS=\"start --daemon --port 3131 --trace\"\nSCRIPTNAME=/etc/init.d/$NAME\nCCRBUSER=\"ccrb\"\nexport CRUISE_DATA_ROOT=/var/lib/cruisecontrolrb\n\n# Read configuration variable file if it is present\n[ -r /etc/default/$NAME ] && . /etc/default/$NAME\n\nEXTRAARGS=\"--chuid $CCRBUSER --chdir $CCRBDIR --quiet $EXTRAARGS\"\nPIDFILE=$CCRBDIR/tmp/pids/mongrel.pid\nBUILDER_PIDS=$CCRBDIR/tmp/pids/builders\nDAEMON=$CCRBDIR/cruise\n\n# Exit if the package is not installed\n[ -x \"$DAEMON\" ] || exit 0\n\n# Load the VERBOSE setting and other rcS variables\n[ -f /etc/default/rcS ] && . /etc/default/rcS\n\n# Define LSB log_* functions.\n# Depend on lsb-base (>= 3.0-6) to ensure that this file is present.\n. /lib/lsb/init-functions\n\n#\n# Function that starts the daemon/service\n#\ndo_start()\n{\n\t# Return\n\t#   0 if daemon has been started\n\t#   1 if daemon was already running\n\t#   2 if daemon could not be started\n\tstart-stop-daemon --start --test --pidfile $PIDFILE $EXTRAARGS --exec $DAEMON > /dev/null \\\n\t\t|| return 1\n\tstart-stop-daemon --start --pidfile $PIDFILE $EXTRAARGS --exec $DAEMON -- \\\n\t\t$DAEMON_ARGS \\\n\t\t|| return 2\n\t# Add code here, if necessary, that waits for the process to be ready\n\t# to handle requests from services started subsequently which depend\n\t# on this one.  As a last resort, sleep for some time.\n}\n\n#\n# Function that stops the daemon/service\n#\ndo_stop()\n{\n\t# Return\n\t#   0 if daemon has been stopped\n\t#   1 if daemon was already stopped\n\t#   2 if daemon could not be stopped\n\t#   other if a failure occurred\n\tstart-stop-daemon --stop --retry=TERM/30/KILL/5 --pidfile $PIDFILE\n\tRETVAL=\"$?\"\n\t[ \"$RETVAL\" = 2 ] && return 2\n\n\t# Kill all builders\n\tfor BPID in `ls $BUILDER_PIDS/*.pid`; do\n\t        start-stop-daemon --stop --signal KILL --pidfile \"$BPID\"\n\t\t[ \"$?\" = 2 ] && return 2\n\t\trm -f \"$BPID\"\n\tdone\n\t# Many daemons don't delete their pidfiles when they exit.\n\trm -f $PIDFILE\n\n\treturn \"$RETVAL\"\n}\n\n#\n# Function that sends a SIGHUP to the daemon/service\n#\ndo_reload() {\n\t#\n\t# If the daemon can reload its configuration without\n\t# restarting (for example, when it is sent a SIGHUP),\n\t# then implement that here.\n\t#\n\tstart-stop-daemon --stop --signal 1 --pidfile $PIDFILE\n\treturn 0\n}\n\ncase \"$1\" in\n  start)\n\tlog_daemon_msg \"Starting $DESC\" \"$NAME\"\n\tdo_start\n\tcase \"$?\" in\n\t\t0|1) [ \"$VERBOSE\" != no ] && log_end_msg 0 ;;\n\t\t2) [ \"$VERBOSE\" != no ] && log_end_msg 1 ;;\n\tesac\n\t;;\n  stop)\n\tlog_daemon_msg \"Stopping $DESC\" \"$NAME\"\n\tdo_stop\n\tcase \"$?\" in\n\t\t0|1) [ \"$VERBOSE\" != no ] && log_end_msg 0 ;;\n\t\t2) [ \"$VERBOSE\" != no ] && log_end_msg 1 ;;\n\tesac\n\t;;\n  #reload|force-reload)\n\t#\n\t# If do_reload() is not implemented then leave this commented out\n\t# and leave 'force-reload' as an alias for 'restart'.\n\t#\n\t#log_daemon_msg \"Reloading $DESC\" \"$NAME\"\n\t#do_reload\n\t#log_end_msg $?\n\t#;;\n  restart|force-reload)\n\t#\n\t# If the \"reload\" option is implemented then remove the\n\t# 'force-reload' alias\n\t#\n\tlog_daemon_msg \"Restarting $DESC\" \"$NAME\"\n\tdo_stop\n\tcase \"$?\" in\n\t  0|1)\n\t\tdo_start\n\t\tcase \"$?\" in\n\t\t\t0) log_end_msg 0 ;;\n\t\t\t1) log_end_msg 1 ;; # Old process is still running\n\t\t\t*) log_end_msg 1 ;; # Failed to start\n\t\tesac\n\t\t;;\n\t  *)\n\t  \t# Failed to stop\n\t\tlog_end_msg 1\n\t\t;;\n\tesac\n\t;;\n  *)\n\t#echo \"Usage: $SCRIPTNAME {start|stop|restart|reload|force-reload}\" >&2\n\techo \"Usage: $SCRIPTNAME {start|stop|restart|force-reload}\" >&2\n\texit 3\n\t;;\nesac\n\n:\n"
            :literal true)))
         (first
          (build-resources
           [:node-type {:tag :n :image {:os-family :ubuntu}}]
           (cruise-control-rb-init))))))
