(ns bones.editable.protocols)

(defprotocol Client
  (login   [client args tap])
  (logout  [client tap])
  (command [client cmd args tap])
  (query   [client args tap]))
