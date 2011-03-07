
(ns clj-ldap.test.server
  "An embedded unboundid ldap server for unit testing"
  (:require [clj-ldap.client :as ldap])
  (:use clojure.contrib.def)
  (:import [com.unboundid.ldap.listener
            InMemoryDirectoryServer
            InMemoryDirectoryServerConfig])
  (:import [javax.net.ssl
            SSLSocketFactory
            SSLServerSocketFactory]))

(defonce server (atom nil))
(defonce ssl-server (atom nil))

(defn- start-ldap-server
  "Start up an embedded ldap server"
  [port]
  (let [config (doto (InMemoryDirectoryServerConfig.
                      (into-array ["dc=alienscience,dc=org,dc=uk"]))
                 (.setListenPort port))
        server (doto (InMemoryDirectoryServer. config)
                 .startListening)]
    server))

(defn- start-ssl-ldap-server
  "Start up an embedded ldap server listening over ssl"
  [port]
  (let [config (doto (InMemoryDirectoryServerConfig.
                      (into-array ["dc=alienscience,dc=org,dc=uk"]))
                 (.setListenPort port)
                 (.setServerSocketFactory (SSLServerSocketFactory/getDefault))
                 (.setClientSocketFactory (SSLSocketFactory/getDefault)))
        server (doto (InMemoryDirectoryServer. config)
                 .startListening)]
    server))

(defn- stop-ldap-server
  "Stops an embedded ldap server"
  [server]
  (.shutDown server true))


(defn- add-toplevel-objects!
  "Adds top level objects, needed for testing, to the ldap server"
  [connection]
  (ldap/add connection "dc=alienscience,dc=org,dc=uk"
            {:objectClass ["top" "domain" "extensibleObject"]
             :dc "alienscience"})
  (ldap/add connection "ou=people,dc=alienscience,dc=org,dc=uk"
            {:objectClass ["top" "organizationalUnit"]
             :ou "people"})
  (ldap/add connection
            "cn=Saul Hazledine,ou=people,dc=alienscience,dc=org,dc=uk"
            {:objectClass ["top" "Person"]
             :cn "Saul Hazledine"
             :sn "Hazledine"
             :description "Creator of bugs"}))

(defn stop!
  "Stops the embedded ldap servers"
  []
  (if @server
    (let [ldap-server @server]
      (reset! server nil)
      (stop-ldap-server ldap-server)))
  (if @ssl-server
    (let [ldap-server @ssl-server]
      (reset! ssl-server nil)
      (stop-ldap-server ldap-server))))

(defn start!
  "Starts embedded ldap servers on the given ports"
  [port ssl-port]
  (stop!)
  (reset! server (start-ldap-server port))
  (reset! ssl-server (start-ssl-ldap-server ssl-port))
  (let [conn (ldap/connect {:host {:address "localhost" :port port}})]
    (add-toplevel-objects! conn))
  ;; TODO: fix problem connecting over SSL
  )
