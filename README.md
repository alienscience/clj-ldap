
# Introduction

clj-ldap is a thin layer on the [unboundid sdk](http://www.unboundid.com/products/ldap-sdk/) and allows clojure programs to talk to ldap servers. This library is available on [clojars.org](http://clojars.org/)

     :dependencies [[clj-ldap "0.0.1"]]

# Example

    (ns example
      (:require [clj-ldap.client :as ldap]))
      
    (def ldap-server (ldap/connect {:host "ldap.example.com"}))
    
    (ldap/get ldap-server "cn=dude,ou=people,dc=example,dc=com")
    
    ;; Returns a map such as
    {:gidNumber "2000"
     :loginShell "/bin/bash"
     :objectClass #{"inetOrgPerson" "posixAccount" "shadowAccount"}
     :mail "dude@example.com"
     :sn "Dudeness"
     :cn "dude"
     :uid "dude"
     :homeDirectory "/home/dude"}

# API

## connect [options]

Connects to an ldap server and returns a, thread safe, [LDAPConnectionPool](http://www.unboundid.com/products/ldap-sdk/docs/javadoc/com/unboundid/ldap/sdk/LDAPConnectionPool.html).
Options is a map with the following entries:

    :host            Either a string in the form "address:port"
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
    :timeout         The timeout when waiting for a response from the server
                     (milliseconds), defaults to 5 minutes

Throws a [LDAPException](http://www.unboundid.com/products/ldap-sdk/docs/javadoc/com/unboundid/ldap/sdk/LDAPException.html) if an error occurs establishing the connection pool or authenticating to any of the servers.

An example:
    (ldap/connect conn {:host "ldap.example.com" :num-connections 10})
    
    (ldap/connect conn {:host [{:address "ldap1.example.com" :port 8000}
                               {:address "ldap3.example.com"}
                               "ldap2.example.com:8001"]
                        :ssl? true
                        :num-connections 9})
                        
    (ldap/connect conn {:host {:port 8000}})
                               
    
## get [connection dn]
  
If successful, returns a map containing the entry for the given DN.
Returns nil if the entry doesn't exist. 

    (ldap/get conn "cn=dude,ou=people,dc=example,dc=com")

Throws a [LDAPException](http://www.unboundid.com/products/ldap-sdk/docs/javadoc/com/unboundid/ldap/sdk/LDAPException.html) on error.

## add [connection dn entry]

Adds an entry to the connected ldap server. The entry is map of keywords to values which can be strings, sets or vectors.


    (ldap/add conn "cn=dude,ou=people,dc=example,dc=com"
                   {:objectClass #{"top" "person"}
                    :cn "dude"
                    :sn "a"
                    :description "His dudeness"
                    :telephoneNumber ["1919191910" "4323324566"]})
                    
Throws a [LDAPException](http://www.unboundid.com/products/ldap-sdk/docs/javadoc/com/unboundid/ldap/sdk/LDAPException.html) if there is an error with the request or the add failed.

## modify [connection dn modifications]                    

Modifies an entry in the connected ldap server. The modifications are
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

All the keys in the map are optional e.g:

     (ldap/modify conn "cn=dude,ou=people,dc=example,dc=com"
                  {:add {:telephoneNumber "232546265"}})


Throws a [LDAPException](http://www.unboundid.com/products/ldap-sdk/docs/javadoc/com/unboundid/ldap/sdk/LDAPException.html) on error.

## search [connection base]  [connection base options]

Runs a search on the connected ldap server, reads all the results into
memory and returns the results as a sequence of maps. An introduction
to ldap searching can be found in this [article](http://www.enterprisenetworkingplanet.com/netsysm/article.php/3317551/Unmasking-the-LDAP-Search-Filter.htm).

Options is a map with the following optional entries:
      :scope       The search scope, can be :base :one or :sub,
                   defaults to :sub
      :filter      A string describing the search filter,
                   defaults to "(objectclass=*)"
      :attributes  A collection of the attributes to return,
                   defaults to all user attributes
e.g
    (ldap/search conn "ou=people,dc=example,dc=com")
    
    (ldap/search conn "ou=people,dc=example,dc=com" {:attributes [:cn]})

Throws a [LDAPSearchException](http://www.unboundid.com/products/ldap-sdk/docs/javadoc/com/unboundid/ldap/sdk/LDAPSearchException.html) on error.

## search! [connection base f]   [connection base options f]

Runs a search on the connected ldap server and executes the given
function (for side effects) on each result. Does not read all the
results into memory.

Options is a map with the following optional entries:
     :scope       The search scope, can be :base :one or :sub,
                  defaults to :sub
     :filter      A string describing the search filter,
                  defaults to "(objectclass=*)"
     :attributes  A collection of the attributes to return,
                  defaults to all user attributes
     :queue-size  The size of the internal queue used to store results before
                  they are passed to the function, the default is 100

e.g
     (ldap/search! conn "ou=people,dc=example,dc=com" println)
     
     (ldap/search! conn "ou=people,dc=example,dc=com"
                        {:filter "sn=dud*"}
                        (fn [x]
                           (println "Hello " (:cn x))))

Throws a [LDAPSearchException](http://www.unboundid.com/products/ldap-sdk/docs/javadoc/com/unboundid/ldap/sdk/LDAPSearchException.html) if an error occurs during search. Throws an [EntrySourceException](http://www.unboundid.com/products/ldap-sdk/docs/javadoc/com/unboundid/ldap/sdk/EntrySourceException.html) if there is an eror obtaining search results. 

## delete [connection dn]

Deletes the entry with the given DN on the connected ldap server.

     (ldap/delete conn "cn=dude,ou=people,dc=example,dc=com")

Throws a [LDAPException](http://www.unboundid.com/products/ldap-sdk/docs/javadoc/com/unboundid/ldap/sdk/LDAPException.html) if the object does not exist or an error occurs.
