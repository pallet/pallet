#!/usr/bin/env python

# Taken from ganglia distribution contrib directory, and modified as per
# http://www.ibm.com/developerworks/linux/library/l-ganglia-nagios-2/index.html

import sys
import getopt
import socket
import xml.parsers.expat

class GParser:
  def __init__(self, host, metric):
    self.inhost =0
    self.inmetric = 0
    self.value = None
    self.host = host
    self.metric = metric

  def parse(self, file):
    p = xml.parsers.expat.ParserCreate()
    p.StartElementHandler = parser.start_element
    p.EndElementHandler = parser.end_element
    p.ParseFile(file)
    if self.value == None:
      raise Exception('Host/value not found')
    return float(self.value)

  def start_element(self, name, attrs):
    if name == "HOST":
      if attrs["NAME"]==self.host:
        self.inhost=1
    elif self.inhost==1 and name == "METRIC" and attrs["NAME"]==self.metric:
      self.value=attrs["VAL"]

  def end_element(self, name):
    if name == "HOST" and self.inhost==1:
      self.inhost=0

def usage():
  print """Usage: check_ganglia \
-h|--host= -m|--metric= -w|--warning= \
-c|--critical= [-s|--server=] [-p|--port=] """
  sys.exit(3)

if __name__ == "__main__":
##############################################################
  ganglia_host = '127.0.0.1'
  ganglia_port = 8649
  host = None
  metric = None
  warning = None
  critical = None

  try:
    options, args = getopt.getopt(sys.argv[1:],
      "h:m:w:c:s:p:",
      ["host=", "metric=", "warning=", "critical=", "server=", "port="],
      )
  except getopt.GetoptError, err:
    print "check_gmond:", str(err)
    usage()
    sys.exit(3)

  for o, a in options:
    if o in ("-h", "--host"):
       host = a
    elif o in ("-m", "--metric"):
       metric = a
    elif o in ("-w", "--warning"):
       warning = float(a)
    elif o in ("-c", "--critical"):
       critical = float(a)
    elif o in ("-p", "--port"):
       ganglia_port = int(a)
    elif o in ("-s", "--server"):
       ganglia_host = a

  if critical == None or warning == None or metric == None or host == None:
    usage()
    sys.exit(3)

  try:
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.connect((ganglia_host,ganglia_port))
    parser = GParser(host, metric)
    value = parser.parse(s.makefile("r"))
    s.close()
  except Exception, err:
    print "CHECKGANGLIA UNKNOWN: Error while getting value \"%s\"" % (err)
    sys.exit(3)

  if critical > warning:
    if value >= critical:
      print "CHECKGANGLIA CRITICAL: %s is %.2f" % (metric, value)
      sys.exit(2)
    elif value >= warning:
      print "CHECKGANGLIA WARNING: %s is %.2f" % (metric, value)
      sys.exit(1)
    else:
      print "CHECKGANGLIA OK: %s is %.2f" % (metric, value)
      sys.exit(0)
  else:
    if critical >= value:
      print "CHECKGANGLIA CRITICAL: %s is %.2f" % (metric, value)
      sys.exit(2)
    elif warning >= value:
      print "CHECKGANGLIA WARNING: %s is %.2f" % (metric, value)
      sys.exit(1)
    else:
      print "CHECKGANGLIA OK: %s is %.2f" % (metric, value)
      sys.exit(0)
