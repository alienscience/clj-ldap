
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
            RoundRobinServerSet
            SearchRequest
            LDAPEntrySource
            EntrySourceException
            SearchScope])
  (:import [com.unboundid.ldap.sdk.controls
            PreReadRequestControl
            PostReadRequestControl
            PreReadResponseControl
            PostReadResponseControl])
  (:import [com.unboundid.util.ssl
            SSLUtil
            TrustAllTrustManager
            TrustStoreTrustManager]))

;;======== Helper functions ====================================================

(defn- extract-attribute
  "Extracts [:name value] from the given attribute object. Converts
   the objectClass attribute to a set."
  [attr]
  (let [k (keyword (.getName attr))]
    (cond
      (= :objectClass k)     [k (set (vec (.getValues attr)))]
      (> (.size attr) 1)     [k (vec (.getValues attr))]
      :else                  [k (.getValue attr)])))

(defn- entry-as-map
  "Converts an Entry object into a map optionally adding the DN"
  ([entry]
     (entry-as-map entry true))
  ([entry dn?]
     (let [col-a (.getAttributes entry)
           attrs (seq (.getAttributes entry))]
       (if dn?
         (apply hash-map :dn (.getDN entry)
                (mapcat extract-attribute attrs))
         (apply hash-map
                (mapcat extract-attribute attrs))))))

(defn- add-response-control
  "Adds the values contained in given response control to the given map"
  [m control]
  (condp instance? control
    PreReadResponseControl 
    (update-in m [:pre-read] merge (entry-as-map (.getEntry control) false))
    PostReadResponseControl
    (update-in m [:post-read] merge (entry-as-map (.getEntry control) false))
    m))

(defn- add-response-controls
  "Adds the values contained in the given response controls to the given map"
  [controls m]
  (reduce add-response-control m (seq controls)))

(defn- ldap-result
  "Converts an LDAPResult object into a map"
  [obj]
  (let [res (.getResultCode obj)
        controls (.getResponseControls obj)]
    (add-response-controls
     controls
     {:code (.intValue res)
      :name (.getName res)})))

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

(defn- add-request-controls
  [request options]
  "Adds LDAP controls to the given request"
  (when (contains? options :pre-read)
    (let [attributes (map name (options :pre-read))
          pre-read-control (PreReadRequestControl. (into-array attributes))]
      (.addControl request pre-read-control)))
  (when (contains? options :post-read)
    (let [attributes (map name (options :post-read))
          pre-read-control (PostReadRequestControl. (into-array attributes))]
      (.addControl request pre-read-control))))


(defn- get-modify-request
  "Sets up a ModifyRequest object using the contents of the given map"
  [dn modifications]
  (let [adds (modify-ops ModificationType/ADD (modifications :add))
        deletes (modify-ops ModificationType/DELETE (modifications :delete))
        replacements (modify-ops ModificationType/REPLACE
                                 (modifications :replace))
        increments (modify-ops ModificationType/INCREMENT
                               (modifications :increment))
        all (concat adds deletes replacements increments)]
    (doto (ModifyRequest. dn (into-array all))
      (add-request-controls modifications))))

(defn- next-entry
  "Attempts to get the next entry from an LDAPEntrySource object"
  [source]
  (try
    (.nextEntry source)
    (catch EntrySourceException e
      (if (.mayContinueReading e)
        (.nextEntry source)
        (throw e)))))

(defn- entry-seq
  "Returns a lazy sequence of entries from an LDAPEntrySource object"
  [source]
  (if-let [n (.nextEntry source)]
    (cons n (lazy-seq (entry-seq source)))))

(defn- search-results
  "Returns a sequence of search results for the given search criteria."
  [connection {:keys [base scope filter attributes]}]
  (let [res (.search connection base scope filter attributes)]
    (if (> (.getEntryCount res) 0)
      (remove empty?
              (map entry-as-map (.getSearchEntries res))))))

(defn- search-results!
  "Call the given function with the results of the search using
   the given search criteria"
  [pool {:keys [base scope filter attributes]} queue-size f]
  (let [request (SearchRequest. base scope filter attributes)
        conn (.getConnection pool)]
    (try
      (with-open [source (LDAPEntrySource. conn request false)]
        (doseq [i (remove empty?
                          (map entry-as-map (entry-seq source)))]
          (f i)))
      (.releaseConnection pool conn)
      (catch EntrySourceException e
        (.releaseDefunctConnection pool conn)
        (throw e)))))


(defn- get-scope
  "Converts a keyword into a SearchScope object"
  [k]
  (condp = k
    :base SearchScope/BASE
    :one  SearchScope/ONE
    SearchScope/SUB))

(defn- get-attributes
  "Converts a collection of attributes into an array"
  [attrs]
  (cond
    (or (nil? attrs)
        (empty? attrs))    (into-array java.lang.String
                                       [SearchRequest/ALL_USER_ATTRIBUTES])
    :else                  (into-array java.lang.String
                                       (map name attrs))))

(defn- search-criteria
  "Returns a map of search criteria from the given base and options"
  [base options]
  (let [scope (get-scope (:scope options))
        filter (or (:filter options) "(objectclass=*)")
        attributes (get-attributes (:attributes options))]
    {:base base
     :scope scope
     :filter filter
     :attributes attributes}))

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
   Returns nil if the entry doesn't exist or cannot be read. Takes an
   optional collection that specifies which attributes will be returned
   from the server."
  ([connection dn]
     (get connection dn nil))
  ([connection dn attributes]
     (if-let [result (if attributes
                       (.getEntry connection dn
                                  (into-array java.lang.String
                                              (map name attributes)))
                       (.getEntry connection dn))]
        (entry-as-map result))))

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
      :increment
        {:attribute-f value}
      :pre-read
        #{:attribute-a :attribute-b}
      :post-read
        #{:attribute-c :attribute-d}}

Where :add adds an attribute value, :delete deletes an attribute value and :replace replaces the set of values for the attribute with the ones specified. The entries :pre-read and :post-read specify attributes that have be read and returned either before or after the modifications have taken place. 
"
  [connection dn modifications]
  (let [modify-obj (get-modify-request dn modifications)]
    (ldap-result
     (.modify connection modify-obj))))


(defn delete
  "Deletes the given entry in the connected ldap server. Optionally takes
   a map that can contain the entry :pre-read to indicate the attributes
   that should be read before deletion."
  ([connection dn]
     (delete connection dn nil))
  ([connection dn options]
     (let [delete-obj (DeleteRequest. dn)]
       (when options
         (add-request-controls delete-obj options))
       (ldap-result
        (.delete connection delete-obj)))))


(defn search
  "Runs a search on the connected ldap server, reads all the results into
   memory and returns the results as a sequence of maps.

   Options is a map with the following optional entries:
      :scope       The search scope, can be :base :one or :sub,
                   defaults to :sub
      :filter      A string describing the search filter,
                   defaults to \"(objectclass=*)\"
      :attributes  A collection of the attributes to return,
                   defaults to all user attributes"
  ([connection base]
     (search connection base nil))
  ([connection base options]
     (search-results connection (search-criteria base options))))

(defn search!
  "Runs a search on the connected ldap server and executes the given
   function (for side effects) on each result. Does not read all the
   results into memory.

   Options is a map with the following optional entries:
      :scope       The search scope, can be :base :one or :sub,
                   defaults to :sub
      :filter      A string describing the search filter,
                   defaults to \"(objectclass=*)\"
      :attributes  A collection of the attributes to return,
                   defaults to all user attributes
      :queue-size  The size of the internal queue used to store results before
                   they are passed to the function, the default is 100"
  ([connection base f]
     (search! connection base nil f))
  ([connection base options f]
     (let [queue-size (or (:queue-size options) 100)]
       (search-results! connection
                        (search-criteria base options)
                        queue-size
                        f))))

(defn bind
  "Attempts to bind on the given connection using the given dn and password.
   Returns true if the bind is successful, false if not.
   One use of this function is to securely check a user password without
   reading it."
  [connection bind-dn password]
  (let [bind-request (SimpleBindRequest. bind-dn password)]
    (try
      (.bind connection bind-request)
      true
      (catch Exception e
        false))))
