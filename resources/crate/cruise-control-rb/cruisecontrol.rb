### BEGIN INIT INFO
# Provides:          cruisecontrol.rb
# Required-Start:    $local_fs $remote_fs
# Required-Stop:     $local_fs $remote_fs
# Default-Start:     2 3 4 5
# Default-Stop:      S 0 1 6
# Short-Description: CruiseControl.rb
# Description:       Continuous build integration system. This runs the web interface (via mongrel and the builders).
### END INIT INFO

# Author: Robert Coup <robert.coup@onetrackmind.co.nz>
#
# Please remove the "Author" lines above and replace them
# with your own name if you copy and modify this script.

# Do NOT "set -e"


# PATH should only include /usr/* if it runs after the mountnfs.sh script
PATH=/usr/sbin:/usr/bin:/sbin:/bin:/usr/local/bin:/var/lib/gems/1.8/bin/
CCRBDIR=/opt/cruisecontrolrb
DESC="Continous Integration"
NAME=cruisecontrolrb
DAEMON_ARGS="start --daemon --port 3131 --trace"
SCRIPTNAME=/etc/init.d/$NAME
CCRBUSER="ccrb"
export CRUISE_DATA_ROOT=/var/lib/cruisecontrolrb

# Read configuration variable file if it is present
[ -r /etc/default/$NAME ] && . /etc/default/$NAME

EXTRAARGS="--chuid $CCRBUSER --chdir $CCRBDIR --quiet $EXTRAARGS"
PIDFILE=$CCRBDIR/tmp/pids/mongrel.pid
BUILDER_PIDS=$CCRBDIR/tmp/pids/builders
DAEMON=$CCRBDIR/cruise

# Exit if the package is not installed
[ -x "$DAEMON" ] || exit 0

# Load the VERBOSE setting and other rcS variables
[ -f /etc/default/rcS ] && . /etc/default/rcS

# Define LSB log_* functions.
# Depend on lsb-base (>= 3.0-6) to ensure that this file is present.
. /lib/lsb/init-functions

#
# Function that starts the daemon/service
#
do_start()
{
	# Return
	#   0 if daemon has been started
	#   1 if daemon was already running
	#   2 if daemon could not be started
	start-stop-daemon --start --test --pidfile $PIDFILE $EXTRAARGS --exec $DAEMON > /dev/null \
		|| return 1
	start-stop-daemon --start --pidfile $PIDFILE $EXTRAARGS --exec $DAEMON -- \
		$DAEMON_ARGS \
		|| return 2
	# Add code here, if necessary, that waits for the process to be ready
	# to handle requests from services started subsequently which depend
	# on this one.  As a last resort, sleep for some time.
}

#
# Function that stops the daemon/service
#
do_stop()
{
	# Return
	#   0 if daemon has been stopped
	#   1 if daemon was already stopped
	#   2 if daemon could not be stopped
	#   other if a failure occurred
	start-stop-daemon --stop --retry=TERM/30/KILL/5 --pidfile $PIDFILE
	RETVAL="$?"
	[ "$RETVAL" = 2 ] && return 2

	# Kill all builders
	for BPID in `ls $BUILDER_PIDS/*.pid`; do
	        start-stop-daemon --stop --signal KILL --pidfile "$BPID"
		[ "$?" = 2 ] && return 2
		rm -f "$BPID"
	done
	# Many daemons don't delete their pidfiles when they exit.
	rm -f $PIDFILE

	return "$RETVAL"
}

#
# Function that sends a SIGHUP to the daemon/service
#
do_reload() {
	#
	# If the daemon can reload its configuration without
	# restarting (for example, when it is sent a SIGHUP),
	# then implement that here.
	#
	start-stop-daemon --stop --signal 1 --pidfile $PIDFILE
	return 0
}

case "$1" in
  start)
	log_daemon_msg "Starting $DESC" "$NAME"
	do_start
	case "$?" in
		0|1) [ "$VERBOSE" != no ] && log_end_msg 0 ;;
		2) [ "$VERBOSE" != no ] && log_end_msg 1 ;;
	esac
	;;
  stop)
	log_daemon_msg "Stopping $DESC" "$NAME"
	do_stop
	case "$?" in
		0|1) [ "$VERBOSE" != no ] && log_end_msg 0 ;;
		2) [ "$VERBOSE" != no ] && log_end_msg 1 ;;
	esac
	;;
  #reload|force-reload)
	#
	# If do_reload() is not implemented then leave this commented out
	# and leave 'force-reload' as an alias for 'restart'.
	#
	#log_daemon_msg "Reloading $DESC" "$NAME"
	#do_reload
	#log_end_msg $?
	#;;
  restart|force-reload)
	#
	# If the "reload" option is implemented then remove the
	# 'force-reload' alias
	#
	log_daemon_msg "Restarting $DESC" "$NAME"
	do_stop
	case "$?" in
	  0|1)
		do_start
		case "$?" in
			0) log_end_msg 0 ;;
			1) log_end_msg 1 ;; # Old process is still running
			*) log_end_msg 1 ;; # Failed to start
		esac
		;;
	  *)
	  	# Failed to stop
		log_end_msg 1
		;;
	esac
	;;
  *)
	#echo "Usage: $SCRIPTNAME {start|stop|restart|reload|force-reload}" >&2
	echo "Usage: $SCRIPTNAME {start|stop|restart|force-reload}" >&2
	exit 3
	;;
esac

:
