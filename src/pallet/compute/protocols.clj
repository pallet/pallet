(ns pallet.compute.protocols)

(defprotocol JumpHosts
  "Provide a SPI for specifying jump-hosts"
  (jump-hosts [compute]))
