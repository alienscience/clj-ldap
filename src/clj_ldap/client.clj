
(ns clj-ldap.client
  "LDAP client"
  (:refer-clojure :exclude [get])
  (:require [clojure.string :as string])
  (:import [com.unboundid.ldap.sdk
            LDAPResult
            LDAPConnection
            ResultCode
            LDAPConnectionPool
            LDAPException
            Attribute
            Entry
            ModificationType
            ModifyRequest
            Modification
            DeleteRequest])
  (:import [com.unboundid.util.ssl
            SSLUtil
            TrustAllTrustManager
            TrustStoreTrustManager]))

;;======== Helper functions ====================================================

(defn- create-connection
  "Create an LDAPConnection object"
  [address port ssl? trust-store]
  (if ssl?
    (let [trust-manager (if trust-store
                          (TrustStoreTrustManager. trust-store)
                          (TrustAllTrustManager.))
          ssl-util (SSLUtil. trust-manager)]
      (LDAPConnection. (.createSSLSocketFactory ssl-util)
                       address
                       (or port 636)))
    (LDAPConnection. address (or port 389))))

(defn- ldap-result
  "Converts an LDAPResult object into a clojure datastructure"
  [obj]
  (let [rc (.getResultCode obj)]
    [(.intValue rc) (.getName rc)]))

(defn- extract-attribute
  "Extracts [:name value] from the given attribute object. Converts
   the objectClass attribute to a set."
  [attr]
  (let [k (keyword (.getName attr))]
    (cond
      (= :objectClass k)     [k (set (vec (.getValues attr)))]
      (> (.size attr) 1)     [k (vec (.getValues attr))]
      :else                  [k (.getValue attr)])))


(defn- set-entry-kv!
  "Sets the given key/value pair in the given entry object"
  [entry-obj k v]
  (let [name-str (name k)]
    (.addAttribute entry-obj
                   (if (coll? v)
                     (Attribute. name-str (into-array v))
                     (Attribute. name-str (str v))))))

(defn- set-entry-map!
  "Sets the attributes in the given entry object using the given map"
  [entry-obj m]
  (doseq [[k v] m]
    (set-entry-kv! entry-obj k v)))

(defn- create-modification
  "Creates a modification object"
  [modify-op attribute values]
  (cond
    (coll? values)    (Modification. modify-op attribute (into-array values))
    (= :all values)   (Modification. modify-op attribute)
    :else             (Modification. modify-op attribute (str values))))

(defn- modify-ops
  "Returns a sequence of Modification objects to do the given operation
   using the contents of the given map."
  [modify-op modify-map]
  (for [[k v] modify-map]
    (create-modification modify-op (name k) v)))

(defn- modify-incs
  "Returns a sequence of Modification objects that increment the given
   attribute(s)"
  [attributes]
  (if attributes
    (if (coll? attributes)
      (map #(Modification. ModificationType/INCREMENT %) attributes)
      [(Modification. ModificationType/INCREMENT attributes)])))

(defn- get-modify-request
  "Sets up a ModifyRequest object using the contents of the given map"
  [dn modifications]
  (let [adds (modify-ops ModificationType/ADD (modifications :add))
        deletes (modify-ops ModificationType/DELETE (modifications :delete))
        replacements (modify-ops ModificationType/REPLACE
                                 (modifications :replace))
        incs (modify-incs (modifications :increment))]
    (ModifyRequest. dn (into-array (concat adds deletes replacements incs)))))

;;=========== API ==============================================================

(defn connect
  "Connects to an ldap server and returns a, thread safe, LDAPConnectionPool.
   Options is a map with the following entries:
   :address         Address of server, defaults to localhost
   :port            Port to connect to, defaults to 389 (or 636 for ldaps)
   :bind-dn         The DN to bind as, optional
   :password        The password to bind with, optional
   :num-connections The number of connections in the pool, defaults to 1
   :ssl?            Boolean, connect over SSL (ldaps), defaults to false
   :trust-store     Only trust SSL certificates that are in this
                    JKS format file, optional, defaults to trusting all
                    certificates
   "
  [{:keys [address port bind-dn password num-connections
           ssl? trust-store] :as options}]
  
  (let [connection (create-connection (or address "localhost")
                                      port ssl? trust-store)
        bind-result (.bind connection bind-dn password)]
    (if (= ResultCode/SUCCESS (.getResultCode bind-result))
      (LDAPConnectionPool. connection (or num-connections 1))
      (throw (LDAPException. bind-result)))))

(defn get
  "If successful, returns a map containing the entry for the given DN.
   Returns nil if the entry doesn't exist or cannot be read."
  [connection dn]
  (if-let [result (.getEntry connection dn)]
    (let [attrs (seq (.getAttributes result))]
      (apply hash-map
             (mapcat extract-attribute attrs)))))

(defn add
  "Adds an entry to the connected ldap server. The entry is assumed to be
   a map."
  [connection dn entry]
  (let [entry-obj (Entry. dn)]
    (set-entry-map! entry-obj entry)
    (ldap-result
     (.add connection entry-obj))))

(defn modify
  "Modifies an entry in the connected ldap server. The modifications are
   a map in the form:
     {:add
        {:attribute-a some-value
         :attribute-b [value1 value2]}
      :delete
        {:attribute-c :all
         :attribute-d some-value
         :attribute-e [value1 value2]}
      :replace
        {:attibute-d value
         :attribute-e [value1 value2]}
      :increment [:attribute-f attribute-g]}
"
  [connection dn modifications]
  (let [modify-obj (get-modify-request dn modifications)]
    (ldap-result
     (.modify connection modify-obj))))


(defn delete
  "Deletes the given entry in the connected ldap server"
  [connection dn]
  (let [delete-obj (DeleteRequest. dn)]
    (ldap-result
     (.delete connection delete-obj))))



