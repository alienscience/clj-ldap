
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
(def base* "ou=people,dc=alienscience,dc=org,dc=uk")
(def dn*  (str "cn=%s," base*))
(def object-class* #{"top" "person"})

;; Variable to catch side effects
(def *side-effects* nil)

;; Result of a successful write
(def success*      {:code 0 :name "success"})

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
  [
   (ldap/connect {:host {:port port}})
   (ldap/connect {:host {:address "localhost"
                         :port port}
                  :num-connections 4})
   (ldap/connect {:host (str "localhost:" port)})
   (ldap/connect {:ssl? true
                  :host {:port ssl-port}})
   (ldap/connect {:host {:port port}
                  :connect-timeout 1000
                  :timeout 5000})
   (ldap/connect {:host [(str "localhost:" port)
                         {:port ssl-port}]})
   (ldap/connect {:host [(str "localhost:" ssl-port)
                         {:port ssl-port}]
                  :ssl? true
                  :num-connections 5})  
   ])


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
         (assoc (:object person-a*) :dn (:dn person-a*))))
  (is (= (ldap/get *conn* (:dn person-b*))
         (assoc (:object person-b*) :dn (:dn person-b*))))
  (is (= (ldap/get *conn* (:dn person-a*) [:cn :sn])
         {:dn (:dn person-a*)
          :cn (-> person-a* :object :cn)
          :sn (-> person-a* :object :sn)})))

(deftest test-add-delete
  (is (= (ldap/add *conn* (:dn person-c*) (:object person-c*))
         success*))
  (is (= (ldap/get *conn* (:dn person-c*))
         (assoc (:object person-c*) :dn (:dn person-c*))))
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
           (-> (:object person-a*)
               (dissoc :description)
               (assoc :dn (:dn person-a*)))))
    (is (= (ldap/get *conn* (:dn person-b*))
           (-> (:object person-b*)
               (assoc :telephoneNumber (second b-phonenums))
               (assoc :dn (:dn person-b*)))))))

(deftest test-modify-replace
  (let [new-phonenums (-> person-b* :object :telephoneNumber)]
    (is (= (ldap/modify *conn* (:dn person-a*)
                        {:replace {:telephoneNumber new-phonenums}})
           success*))
    (is (= (ldap/get *conn* (:dn person-a*))
           (-> (:object person-a*)
               (assoc :telephoneNumber new-phonenums)
               (assoc :dn (:dn person-a*)))))))

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


(deftest test-search
  (is (= (set (map :cn
                   (ldap/search *conn* base* {:attributes [:cn]})))
         (set [nil "testa" "testb" "Saul Hazledine"])))
  (is (= (set (map :cn
                   (ldap/search *conn* base*
                                {:attributes [:cn] :filter "cn=test*"})))
         (set ["testa" "testb"])))
  (binding [*side-effects* #{}]
    (ldap/search! *conn* base* {:attributes [:cn :sn] :filter "cn=test*"}
                  (fn [x]
                    (set! *side-effects*
                          (conj *side-effects* (dissoc x :dn)))))
    (is (= *side-effects*
           (set [{:cn "testa" :sn "a"}
                 {:cn "testb" :sn "b"}])))))
