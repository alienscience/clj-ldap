
(ns clj-ldap.client
  "LDAP client"
  (:refer-clojure :exclude [get])
  (:require [clojure.string :as string])
  (:import [com.unboundid.ldap.sdk
            LDAPResult
            LDAPConnectionOptions
            LDAPConnection
            ResultCode
            LDAPConnectionPool
            LDAPException
            Attribute
            Entry
            ModificationType
            ModifyRequest
            Modification
            DeleteRequest
            SimpleBindRequest
            RoundRobinServerSet])
  (:import [com.unboundid.util.ssl
            SSLUtil
            TrustAllTrustManager
            TrustStoreTrustManager]))

;;======== Helper functions ====================================================

(defn- ldap-result
  "Converts an LDAPResult object into a vector"
  [obj]
  (let [res (.getResultCode obj)]
    [(.intValue res) (.getName res)]))

(defn- connection-options
  "Returns a LDAPConnectionOptions object"
  [{:keys [connect-timeout timeout]}]
  (let [opt (LDAPConnectionOptions.)]
    (when connect-timeout (.setConnectTimeoutMillis opt connect-timeout))
    (when timeout         (.setResponseTimeoutMillis opt timeout))
    opt))

(defn- create-ssl-factory
  "Returns a SSLSocketFactory object"
  [{:keys [trust-store]}]
  (let [trust-manager (if trust-store
                        (TrustStoreTrustManager. trust-store)
                        (TrustAllTrustManager.))
        ssl-util (SSLUtil. trust-manager)]
    (.createSSLSocketFactory ssl-util)))

(defn- host-as-map
  "Returns a single host as a map containing an :address and an optional
   :port"
  [host]
  (cond
    (nil? host)      {:address "localhost" :port 389}
    (string? host)   (let [[address port] (string/split host #":")]
                       {:address (if (= address "")
                                   "localhost"
                                   address)
                        :port (if port
                                (int (Integer. port)))})
    (map? host)      (merge {:address "localhost"} host)
    :else            (throw
                      (IllegalArgumentException.
                       (str "Invalid host for an ldap connection : "
                            host)))))

(defn- create-connection
  "Create an LDAPConnection object"
  [{:keys [host ssl?] :as options}]
  (let [h (host-as-map host)
        opt (connection-options options)]
    (if ssl?
      (let [ssl (create-ssl-factory options)]
        (LDAPConnection. ssl opt (:address h) (or (:port h) 636)))
      (LDAPConnection. opt (:address h) (or (:port h) 389)))))

(defn- bind-request
  "Returns a BindRequest object"
  [{:keys [bind-dn password]}]
  (if bind-dn
    (SimpleBindRequest. bind-dn password)
    (SimpleBindRequest.)))

(defn- connect-to-host
  "Connect to a single host"
  [options]
  (let [{:keys [num-connections]} options
        connection (create-connection options)
        bind-result (.bind connection (bind-request options))]
    (if (= ResultCode/SUCCESS (.getResultCode bind-result))
      (LDAPConnectionPool. connection (or num-connections 1))
      (throw (LDAPException. bind-result)))))

(defn- create-server-set
  "Returns a RoundRobinServerSet"
  [{:keys [host ssl?] :as options}]
  (let [hosts (map host-as-map host)
        addresses (into-array (map :address hosts))
        opt (connection-options options)]
    (if ssl?
      (let [ssl (create-ssl-factory options)
            ports (int-array (map #(or (:port %) (int 636)) hosts))]
        (RoundRobinServerSet. addresses ports ssl opt))
      (let [ports (int-array (map #(or (:port %) (int 389)) hosts))]
        (RoundRobinServerSet. addresses ports opt)))))

(defn- connect-to-hosts
  "Connects to multiple hosts"
  [options]
  (let [{:keys [num-connections]} options
        server-set (create-server-set options)
        bind-request (bind-request options)]
    (LDAPConnectionPool. server-set bind-request (or num-connections 1))))

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


(defn- get-modify-request
  "Sets up a ModifyRequest object using the contents of the given map"
  [dn modifications]
  (let [adds (modify-ops ModificationType/ADD (modifications :add))
        deletes (modify-ops ModificationType/DELETE (modifications :delete))
        replacements (modify-ops ModificationType/REPLACE
                                 (modifications :replace))]
    (ModifyRequest. dn (into-array (concat adds deletes replacements)))))

;;=========== API ==============================================================

(defn connect
  "Connects to an ldap server and returns a, thread safe, LDAPConnectionPool.
   Options is a map with the following entries:
   :host            Either a string in the form \"address:port\"
                    OR a map containing the keys,
                       :address   defaults to localhost
                       :port      defaults to 389 (or 636 for ldaps),
                    OR a collection containing multiple hosts used for load
                    balancing and failover. This entry is optional.
   :bind-dn         The DN to bind as, optional
   :password        The password to bind with, optional
   :num-connections The number of connections in the pool, defaults to 1
   :ssl?            Boolean, connect over SSL (ldaps), defaults to false
   :trust-store     Only trust SSL certificates that are in this
                    JKS format file, optional, defaults to trusting all
                    certificates
   :connect-timeout The timeout for making connections (milliseconds),
                    defaults to 1 minute   
   :timeout         The timeout when waiting for a response from the server
                    (milliseconds), defaults to 5 minutes
   "
  [options]
  (let [host (options :host)]
    (if (and (coll? host)
             (not (map? host)))
      (connect-to-hosts options)
      (connect-to-host options))))

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
         :attribute-e [value1 value2]}}
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



