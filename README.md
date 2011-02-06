
# Introduction

clj-ldap is a thin layer on the [unboundid sdk](http://www.unboundid.com/products/ldap-sdk/) and allows clojure programs to talk to ldap servers. 

# Example

    (ns example
      (:require [clj-ldap.client :as ldap]))
      
    (let [connection (ldap/connect {:address "ldap.example.com"})]
       (ldap/get connection "cn=dude,ou=people,dc=example,dc=com"))
       
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
    :address         Address of server, defaults to localhost
    :port            Port to connect to, defaults to 389
    :bind-dn         The DN to bind as, optional
    :password        The password to bind with, optional
    :num-connections The number of connections in the pool, defaults to 1
    :ssl?            Boolean, connect over SSL (ldaps), defaults to false
    :trust-store     Only trust SSL certificates that are in this
                     JKS format file, optional, defaults to trusting all
                     certificates

For example:
    (ldap/connect conn {:address "ldap.example.com" :num-connections 10})
    
## get [connection dn]
  
If successful, returns a map containing the entry for the given DN.
Returns nil if the entry doesn't exist or cannot be read.

    (ldap/get conn "cn=dude,ou=people,dc=example,dc=com")

## add [connection dn entry]

Adds an entry to the connected ldap server. The entry is map of keywords to values which can be strings, sets or vectors.

    (ldap/add conn "cn=dude,ou=people,dc=example,dc=com"
                   {:objectClass #{"top" "person"}
                    :cn "dude"
                    :sn "a"
                    :description "His dudeness"
                    :telephoneNumber ["1919191910" "4323324566"]})
                    
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
         :attribute-e [value1 value2]}
      :increment [:attribute-f attribute-g]}

All the keys in the map are optional e.g:

     (ldap/modify conn "cn=dude,ou=people,dc=example,dc=com"
                  {:add {:telephoneNumber "232546265"}})
      
## delete [connection dn]

Deletes the entry with the given DN on the connected ldap server.

     (ldap/delete conn "cn=dude,ou=people,dc=example,dc=com")

