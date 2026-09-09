(ns babashka.publish-jar-test
  (:require [babashka.publish-jar :as publish]
            [babashka.publish-jar.cipher :as cipher]
            [babashka.publish-jar.settings :as settings]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org.httpkit.server :as server]))

(def pom
  "<project><modelVersion>4.0.0</modelVersion>
     <groupId>org.example</groupId><artifactId>demo</artifactId><version>1.2.3</version>
   </project>")

(def child-pom
  "<project><modelVersion>4.0.0</modelVersion>
     <parent><groupId>org.example</groupId><artifactId>parent</artifactId><version>9</version></parent>
     <artifactId>child</artifactId>
   </project>")

(deftest coordinates-test
  (is (= {:group "org.example" :artifact "demo" :version "1.2.3"} (publish/coordinates pom)))
  (is (= {:group "org.example" :artifact "child" :version "9"} (publish/coordinates child-pom))
      "group and version come from the parent"))

(deftest metadata-test
  (let [fresh (publish/updated-metadata nil "org.example" "demo" "1.0.0")
        again (publish/updated-metadata fresh "org.example" "demo" "1.1.0")]
    (is (str/includes? fresh "<version>1.0.0</version>"))
    (is (= ["1.0.0" "1.1.0"] (map second (re-seq #"<version>([^<]+)</version>" again))))
    (is (str/includes? again "<release>1.1.0</release>"))
    (is (= 1 (count (re-seq #"<version>1\.1\.0</version>" (publish/updated-metadata again "org.example" "demo" "1.1.0"))))
        "republishing a version lists it once")))

;; A repository: every PUT is remembered by path, GET serves what was put.
(defn- fake-repo [auth-header]
  (let [store (atom {})
        handler (fn [{:keys [request-method uri headers body]}]
                  (cond
                    (not= auth-header (get headers "authorization")) {:status 401 :body "who?"}
                    (= :put request-method) (do (swap! store assoc uri (slurp body)) {:status 201})
                    (= :get request-method) (if-let [b (get @store uri)] {:status 200 :body b} {:status 404})
                    :else {:status 405}))
        srv (server/run-server handler {:port 0 :legacy-return-value? false})]
    {:store store :stop #(server/server-stop! srv) :url (str "http://localhost:" (server/server-port srv) "/")}))

(deftest publish-test
  (let [dir (io/file (System/getProperty "java.io.tmpdir") (str "publish-jar-" (System/nanoTime)))
        _ (.mkdirs dir)
        jar (io/file dir "demo.jar")
        pom-file (io/file dir "pom.xml")
        _ (spit jar "not really a jar")
        _ (spit pom-file pom)
        {:keys [store stop url]} (fake-repo "Basic dXNlcjpzZWNyZXQ=")]
    (try
      (testing "credentials in the repository map"
        (let [uploaded (publish/publish {:jar (str jar) :pom (str pom-file)
                                         :repository {:url url :username "user" :password "secret"}})
              paths (set (keys @store))]
          (is (= 9 (count uploaded)))
          (is (= #{"/org/example/demo/1.2.3/demo-1.2.3.jar" "/org/example/demo/1.2.3/demo-1.2.3.jar.md5"
                   "/org/example/demo/1.2.3/demo-1.2.3.jar.sha1" "/org/example/demo/1.2.3/demo-1.2.3.pom"
                   "/org/example/demo/1.2.3/demo-1.2.3.pom.md5" "/org/example/demo/1.2.3/demo-1.2.3.pom.sha1"
                   "/org/example/demo/maven-metadata.xml" "/org/example/demo/maven-metadata.xml.md5"
                   "/org/example/demo/maven-metadata.xml.sha1"}
                 paths))
          (is (= "not really a jar" (get @store "/org/example/demo/1.2.3/demo-1.2.3.jar")))
          (is (= "c4e762e76de7329365ade67908e20621" (get @store "/org/example/demo/1.2.3/demo-1.2.3.jar.md5"))
              "md5 of the jar bytes")
          (is (str/includes? (get @store "/org/example/demo/maven-metadata.xml") "<release>1.2.3</release>"))))
      (testing "credentials from settings.xml by server id"
        (let [settings (io/file dir "settings.xml")]
          (spit settings "<settings><servers><server><id>fake</id><username>user</username><password>secret</password></server></servers></settings>")
          (reset! store {})
          (is (= 9 (count (publish/publish {:jar (str jar) :pom (str pom-file)
                                            :repository {:url url :id "fake"}
                                            :settings (str settings)}))))))
      (testing "wrong credentials name the URL and the status"
        (is (thrown-with-msg? Exception #"Could not (read|transfer) .*: HTTP 401"
                              (publish/publish {:jar (str jar) :pom (str pom-file)
                                                :repository {:url url :username "user" :password "wrong"}}))))
      (testing "a snapshot is refused"
        (spit pom-file (str/replace pom "1.2.3" "1.2.3-SNAPSHOT"))
        (is (thrown-with-msg? Exception #"SNAPSHOT"
                              (publish/publish {:jar (str jar) :pom (str pom-file)
                                                :repository {:url url :username "user" :password "secret"}}))))
      (finally (stop)))))

(deftest unknown-repository-test
  (is (thrown-with-msg? Exception #"Unknown repository :nope"
                        (publish/publish {:jar "x" :pom "y" :repository :nope}))))

(deftest settings-test
  (let [f (io/file (System/getProperty "java.io.tmpdir") (str "settings-" (System/nanoTime) ".xml"))]
    (spit f "<settings><servers><server><id>a</id><username>${user.name}</username><password>pw</password></server></servers></settings>")
    (is (= {"a" {:username (System/getProperty "user.name") :password "pw"}} (settings/servers f)))
    (is (= {} (settings/servers (io/file f "missing"))))))

(deftest cipher-test
  ;; vectors made by plexus-cipher 2.0, from babashka's Maven procurer
  (let [{:keys [master master-blob cases not-encrypted]}
        (edn/read-string (slurp (io/resource "cipher-vectors.edn")))
        dir (io/file (System/getProperty "java.io.tmpdir") (str "cipher-" (System/nanoTime)))
        security (io/file dir "settings-security.xml")]
    (.mkdirs dir)
    (spit security (str "<settingsSecurity><master>" master-blob "</master></settingsSecurity>"))
    (is (= master (cipher/master-password security)))
    (doseq [{:keys [dispatcher blob]} cases]
      (is (= dispatcher (cipher/decrypt-password blob {:file security}))))
    (is (= not-encrypted (cipher/decrypt-password not-encrypted {:file security})) "no blob, no change")))
