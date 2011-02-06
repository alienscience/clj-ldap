
(ns clj-ldap.test.client
  "Automated tests for clj-ldap"
  (:require [clj-ldap.client :as ldap])
  (:require [clj-ldap.test.server :as server])
  (:use clojure.test)
  (:import [com.unboundid.ldap.sdk LDAPException]))


;; Tests are run over a variety of connection types
(def port* 8000)
(def ssl-port* 8001)
(def *connections* nil)
(def *conn* nil)

;; Tests concentrate on a single object class
(def dn*           "cn=%s,ou=people,dc=alienscience,dc=org,dc=uk")
(def object-class* #{"top" "person"})

;; Result of a successful write
(def success*      [0 "success"])

;; People to test with
(def person-a*
     {:dn (format dn* "testa")
      :object {:objectClass object-class*
               :cn "testa"
               :sn "a"
               :description "description a"
               :telephoneNumber "000000001"
               :userPassword "passa"}})

(def person-b*
     {:dn (format dn* "testb")
      :object {:objectClass object-class*
               :cn "testb"
               :sn "b"
               :description "description b"
               :telephoneNumber ["000000002" "00000003"]
               :userPassword "passb"}})

(def person-c*
     {:dn (format dn* "testc")
      :object {:objectClass object-class*
               :cn "testc"
               :sn "c"
               :description "description c"
               :telephoneNumber "000000004"
               :userPassword "passc"}})

(defn- connect-to-server
  "Opens a sequence of connection pools on the localhost server with the
   given ports"
  [port ssl-port]
  [(ldap/connect {:port port})
   (ldap/connect {:address "localhost"
                  :port port
                  :num-connections 4})
   (ldap/connect {:ssl? true :port ssl-port})])

(defn- test-server
  "Setup server"
  [f]
  (server/start! port* ssl-port*)
  (binding [*connections* (connect-to-server port* ssl-port*)]
    (f))
  (server/stop!))

(defn- test-data
  "Provide test data"
  [f]
  (doseq [connection *connections*]
    (binding [*conn* connection]
      (try
        (ldap/add *conn* (:dn person-a*) (:object person-a*))
        (ldap/add *conn* (:dn person-b*) (:object person-b*))
        (catch Exception e))
      (f)
      (try
        (ldap/delete *conn* (:dn person-a*))
        (ldap/delete *conn* (:dn person-b*))
        (catch Exception e)))))

(use-fixtures :each test-data)
(use-fixtures :once test-server)

(deftest test-get
  (is (= (ldap/get *conn* (:dn person-a*))
         (:object person-a*)))
  (is (= (ldap/get *conn* (:dn person-b*))
         (:object person-b*))))

(deftest test-add-delete
  (is (= (ldap/add *conn* (:dn person-c*) (:object person-c*))
         success*))
  (is (= (ldap/get *conn* (:dn person-c*))
         (:object person-c*)))
  (is (= (ldap/delete *conn* (:dn person-c*))
         success*))
  (is (nil? (ldap/get *conn* (:dn person-c*)))))

(deftest test-modify-add
  (is (= (ldap/modify *conn* (:dn person-a*)
                      {:add {:objectClass "residentialperson"
                             :l "Hollywood"}})
         success*))
  (is (= (ldap/modify
          *conn* (:dn person-b*)
          {:add {:telephoneNumber ["0000000005" "0000000006"]}})
         success*))
  (let [new-a (ldap/get *conn* (:dn person-a*))
        new-b (ldap/get *conn* (:dn person-b*))
        obj-a (:object person-a*)
        obj-b (:object person-b*)]
    (is  (= (:objectClass new-a)
            (conj (:objectClass obj-a) "residentialPerson")))
    (is (= (:l new-a) "Hollywood"))
    (is (= (set (:telephoneNumber new-b))
           (set (concat (:telephoneNumber obj-b)
                        ["0000000005" "0000000006"]))))))

(deftest test-modify-delete
  (let [b-phonenums (-> person-b* :object :telephoneNumber)]
    (is (= (ldap/modify *conn* (:dn person-a*)
                        {:delete {:description :all}})
           success*))
    (is (= (ldap/modify *conn* (:dn person-b*)
                        {:delete {:telephoneNumber (first b-phonenums)}})
           success*))
    (is (= (ldap/get *conn* (:dn person-a*))
           (dissoc (:object person-a*) :description)))
    (is (= (ldap/get *conn* (:dn person-b*))
           (assoc (:object person-b*) :telephoneNumber (second b-phonenums))))))

(deftest test-modify-replace
  (let [new-phonenums (-> person-b* :object :telephoneNumber)]
    (is (= (ldap/modify *conn* (:dn person-a*)
                        {:replace {:telephoneNumber new-phonenums}})
           success*))
    (is (= (ldap/get *conn* (:dn person-a*))
           (assoc (:object person-a*) :telephoneNumber new-phonenums)))))

(deftest test-modify-all
  (let [b (:object person-b*)
        b-phonenums (:telephoneNumber b)]
    (is (= (ldap/modify *conn* (:dn person-b*)
                        {:add {:telephoneNumber "0000000005"}
                         :delete {:telephoneNumber (second b-phonenums)}
                         :replace {:description "desc x"}})
           success*))
    (let [new-b (ldap/get *conn* (:dn person-b*))]
      (is (= (set (:telephoneNumber new-b))
             (set [(first b-phonenums) "0000000005"])))
      (is (= (:description new-b) "desc x")))))

