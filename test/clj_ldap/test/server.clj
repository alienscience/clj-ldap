
(ns clj-ldap.test.server
  "An embedded ldap server for unit testing"
  (:require [clj-ldap.client :as ldap])
  (:use clojure.contrib.def)
  (:use [clj-file-utils.core :only [rm-rf mkdir-p]])
  (:import [org.apache.directory.server.core
            DefaultDirectoryService
            DirectoryService])
  (:import [org.apache.directory.server.ldap
            LdapServer])
  (:import [org.apache.directory.server.protocol.shared.transport
            TcpTransport])
  (:import [java.util HashSet])
  (:import [org.apache.directory.server.core.partition.impl.btree.jdbm
            JdbmPartition
            JdbmIndex]))

(defonce server (atom nil))

(defn- add-partition! 
  "Adds a partition to the embedded directory service"
  [service id dn]
  (let [partition (doto (JdbmPartition.)
                    (.setId id)
                    (.setSuffix dn))]
    (.addPartition service partition)
    partition))

(defn- add-index!
  "Adds an index to the given partition on the given attributes"
  [partition & attrs]
  (let [indexed-attrs (HashSet.)]
    (doseq [attr attrs]
      (.add indexed-attrs (JdbmIndex. attr)))
    (.setIndexedAttributes partition indexed-attrs)))

(defn- start-ldap-server
  "Start up an embedded ldap server"
  [port]
  (let [work-path (doto "/tmp/apacheds" rm-rf mkdir-p)
        work-dir  (java.io.File. work-path)
        directory-service (doto (DefaultDirectoryService.)
                            (.setShutdownHookEnabled true)
                            (.setWorkingDirectory work-dir))
        ldap-transport (TcpTransport. port)
        ldap-server (doto (LdapServer.)
                      (.setDirectoryService directory-service)
                      (.setAllowAnonymousAccess true)
                      (.setTransports (into-array [ldap-transport])))]
    (-> (add-partition! directory-service
                        "clojure" "dc=alienscience,dc=org,dc=uk")
        (add-index! "objectClass" "ou" "uid"))
    (.startup directory-service)
    (.start ldap-server)
    [directory-service ldap-server]))

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
  "Stops the embedded ldap server"
  []
  (if @server
    (let [[directory-service ldap-server] @server]
      (.stop ldap-server)
      (.shutdown directory-service)
      (reset! server nil))))

(defn start!
  "Starts an embedded ldap server on the given port"
  [port]
  (stop!)
  (let [s (start-ldap-server port)
        conn (ldap/connect {:address "localhost" :port port})]
    (add-toplevel-objects! conn)
    (reset! server s)))
