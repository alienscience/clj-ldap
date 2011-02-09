(defproject clj-ldap "0.0.1"
  :description "Clojure ldap client"
  :dependencies [[org.clojure/clojure "1.2.0"]
                 [org.clojure/clojure-contrib "1.2.0"]
                 [com.unboundid/unboundid-ldapsdk "2.0.0"]]
  :dev-dependencies [[swank-clojure "1.2.1"]
                     [org.apache.directory.server/apacheds-all "1.5.5"]
                     [org.slf4j/slf4j-simple "1.5.6"]
                     [clj-file-utils "0.2.1"]]
  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo
            :comments "same as Clojure"})

