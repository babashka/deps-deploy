(ns babashka.publish-jar-test
  (:require [babashka.publish-jar :as publish]
            [babashka.publish-jar.cipher :as cipher]
            [babashka.publish-jar.gpg :as gpg]
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

(defn- temp-dir [prefix]
  (doto (io/file (System/getProperty "java.io.tmpdir") (str prefix "-" (System/nanoTime)))
    (.mkdirs)))

(deftest coordinates-test
  (is (= {:group "org.example" :artifact "demo" :version "1.2.3"} (publish/coordinates pom)))
  (is (= {:group "org.example" :artifact "child" :version "9"} (publish/coordinates child-pom))
      "group and version come from the parent"))

(deftest classifier-test
  (let [coords {:artifact "demo" :version "1.2.3"}]
    (is (nil? (publish/classifier coords "target/demo.jar")))
    (is (nil? (publish/classifier coords "target/demo-1.2.3.jar")))
    (is (= "sources" (publish/classifier coords "target/demo-1.2.3-sources.jar")))
    (is (= "aarch64-linux" (publish/classifier coords "demo-1.2.3-aarch64-linux.jar")))))

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

(def version-paths
  #{"/org/example/demo/1.2.3/demo-1.2.3.jar" "/org/example/demo/1.2.3/demo-1.2.3.jar.md5"
    "/org/example/demo/1.2.3/demo-1.2.3.jar.sha1" "/org/example/demo/1.2.3/demo-1.2.3.pom"
    "/org/example/demo/1.2.3/demo-1.2.3.pom.md5" "/org/example/demo/1.2.3/demo-1.2.3.pom.sha1"
    "/org/example/demo/maven-metadata.xml" "/org/example/demo/maven-metadata.xml.md5"
    "/org/example/demo/maven-metadata.xml.sha1"})

(deftest deploy-test
  (let [dir (temp-dir "publish-jar")
        jar (io/file dir "demo.jar")
        pom-file (io/file dir "pom.xml")
        _ (spit jar "not really a jar")
        _ (spit pom-file pom)
        {:keys [store stop url]} (fake-repo "Basic dXNlcjpzZWNyZXQ=")]
    (try
      (testing "credentials in the repository map, jar renamed to artifact-version.jar"
        (let [uploaded (publish/deploy {:artifact (str jar) :pom-file (str pom-file)
                                        :repository {:url url :username "user" :password "secret"}})]
          (is (= 9 (count uploaded)))
          (is (= version-paths (set (keys @store))))
          (is (= "not really a jar" (get @store "/org/example/demo/1.2.3/demo-1.2.3.jar")))
          (is (= "c4e762e76de7329365ade67908e20621" (get @store "/org/example/demo/1.2.3/demo-1.2.3.jar.md5"))
              "md5 of the jar bytes")
          (is (str/includes? (get @store "/org/example/demo/maven-metadata.xml") "<release>1.2.3</release>"))))
      (testing "deps-deploy's nested repository map"
        (reset! store {})
        (is (= 9 (count (publish/deploy {:artifact (str jar) :pom-file (str pom-file)
                                         :repository {"fake" {:url url :username "user" :password "secret"}}})))))
      (testing "a classifier in the jar name is kept"
        (let [sources (io/file dir "demo-1.2.3-sources.jar")]
          (spit sources "sources")
          (reset! store {})
          (publish/deploy {:artifact (str sources) :pom-file (str pom-file)
                           :repository {:url url :username "user" :password "secret"}})
          (is (= "sources" (get @store "/org/example/demo/1.2.3/demo-1.2.3-sources.jar")))))
      (testing "credentials from settings.xml by server id"
        (let [settings (io/file dir "settings.xml")]
          (spit settings "<settings><servers><server><id>fake</id><username>user</username><password>secret</password></server></servers></settings>")
          (reset! store {})
          (is (= 9 (count (publish/deploy {:artifact (str jar) :pom-file (str pom-file)
                                           :repository {:url url :id "fake"}
                                           :settings (str settings)}))))))
      (testing "wrong credentials name the URL and the status"
        (is (thrown-with-msg? Exception #"Could not (read|transfer) .*: HTTP 401"
                              (publish/deploy {:artifact (str jar) :pom-file (str pom-file)
                                               :repository {:url url :username "user" :password "wrong"}}))))
      (testing "blank credentials count as missing"
        (is (thrown-with-msg? Exception #"No credentials for fake"
                              (publish/deploy {:artifact (str jar) :pom-file (str pom-file)
                                               :repository {:url url :id "fake" :username "" :password ""}
                                               :settings (str (io/file dir "none.xml"))}))))
      (testing "a snapshot is refused"
        (spit pom-file (str/replace pom "1.2.3" "1.2.3-SNAPSHOT"))
        (is (thrown-with-msg? Exception #"SNAPSHOT"
                              (publish/deploy {:artifact (str jar) :pom-file (str pom-file)
                                               :repository {:url url :username "user" :password "secret"}}))))
      (finally (stop)))))

(deftest install-test
  (let [dir (temp-dir "publish-jar-install")
        jar (io/file dir "demo.jar")
        pom-file (io/file dir "pom.xml")
        repo (io/file dir "m2")]
    (spit jar "jar")
    (spit pom-file pom)
    (System/setProperty "maven.repo.local" (str repo))
    (try
      (let [written (publish/deploy {:installer :local :artifact (str jar) :pom-file (str pom-file)})]
        (is (= 3 (count written)))
        (is (= "jar" (slurp (io/file repo "org/example/demo/1.2.3/demo-1.2.3.jar"))))
        (is (= pom (slurp (io/file repo "org/example/demo/1.2.3/demo-1.2.3.pom"))))
        (is (str/includes? (slurp (io/file repo "org/example/demo/maven-metadata-local.xml")) "<version>1.2.3</version>")))
      (finally (System/clearProperty "maven.repo.local")))))

(deftest options-test
  (is (thrown-with-msg? Exception #"Missing :artifact" (publish/deploy {})))
  (is (thrown-with-msg? Exception #"Unknown :installer :nope" (publish/deploy {:artifact "x" :installer :nope})))
  (is (thrown-with-msg? Exception #"No such file: x" (publish/deploy {:artifact "x"})))
  (let [dir (temp-dir "publish-jar-options")
        jar (io/file dir "demo.jar")
        pom-file (io/file dir "pom.xml")]
    (spit jar "jar")
    (spit pom-file pom)
    (is (thrown-with-msg? Exception #"Repository nope needs a URL"
                          (publish/deploy {:artifact (str jar) :pom-file (str pom-file) :repository "nope"}))
        "a repository id without a URL is an error, not a NullPointerException")
    (is (thrown-with-msg? Exception #"Unknown :repository :nope"
                          (publish/deploy {:artifact (str jar) :pom-file (str pom-file) :repository :nope})))))

;; gpg with a throwaway key; skipped where gpg or a shell is missing
(defn- throwaway-gpg
  "A script running gpg with its own home dir holding one key, nil when
  gpg is unavailable."
  [dir]
  ;; a short home dir: gpg's agent socket path has a length limit
  (let [home (doto (io/file (System/getProperty "java.io.tmpdir") (str "g" (rand-int 1000000))) (.mkdirs))
        script (io/file dir "gpg.sh")
        windows? (str/includes? (str/lower-case (System/getProperty "os.name")) "windows")]
    (when-not windows?
      (.setReadable home false false)
      (.setReadable home true true)
      (.setWritable home false false)
      (.setWritable home true true)
      (.setExecutable home false false)
      (.setExecutable home true true)
      (spit script (str "#!/bin/sh\nexec gpg --homedir " home " \"$@\"\n"))
      (.setExecutable script true)
      (let [{:keys [exit]} (try (let [p (.start (ProcessBuilder. ["sh" (str script) "--batch" "--passphrase" "" "--quick-gen-key" "test@example.com" "default" "default" "never"]))]
                                  (slurp (.getErrorStream p))
                                  {:exit (.waitFor p)})
                                (catch java.io.IOException _ {:exit -1}))]
        (when (zero? exit) (str script))))))

(deftest sign-test
  (let [dir (temp-dir "publish-jar-sign")]
    (if-let [gpg-script (throwaway-gpg dir)]
      (let [jar (io/file dir "demo.jar")
            pom-file (io/file dir "pom.xml")
            {:keys [store stop url]} (fake-repo "Basic dXNlcjpzZWNyZXQ=")]
        (spit jar "jar")
        (spit pom-file pom)
        (System/setProperty "publish-jar.gpg" gpg-script)
        (try
          (let [uploaded (publish/deploy {:artifact (str jar) :pom-file (str pom-file) :sign-releases? true
                                          :repository {:url url :username "user" :password "secret"}})]
            (is (= 15 (count uploaded)) "the two signatures come with checksums of their own")
            (is (str/starts-with? (get @store "/org/example/demo/1.2.3/demo-1.2.3.jar.asc") "-----BEGIN PGP SIGNATURE-----"))
            (is (contains? @store "/org/example/demo/1.2.3/demo-1.2.3.pom.asc.sha1")))
          (testing "a wrong key names gpg's complaint"
            (is (thrown-with-msg? Exception #"gpg failed to sign .*demo-1.2.3.jar \(exit \d+\)"
                                  (publish/deploy {:artifact (str jar) :pom-file (str pom-file) :sign-releases? true
                                                   :sign-key-id "nobody@example.com"
                                                   :repository {:url url :username "user" :password "secret"}}))))
          (finally (System/clearProperty "publish-jar.gpg") (stop))))
      (println "gpg unavailable, skipping sign-test"))))

(deftest gpg-missing-test
  (System/setProperty "publish-jar.gpg" (str (io/file (temp-dir "publish-jar-nogpg") "gpg-that-is-not-there")))
  (try
    (is (thrown-with-msg? Exception #"Could not run .*gpg-that-is-not-there"
                          (gpg/sign! (io/file (System/getProperty "java.io.tmpdir") "x") {})))
    (finally (System/clearProperty "publish-jar.gpg"))))

(deftest settings-test
  (let [f (io/file (System/getProperty "java.io.tmpdir") (str "settings-" (System/nanoTime) ".xml"))]
    (spit f "<settings><servers><server><id>a</id><username>${user.name}</username><password>pw</password></server></servers></settings>")
    (is (= {"a" {:username (System/getProperty "user.name") :password "pw"}} (settings/servers f)))
    (is (= {} (settings/servers (io/file f "missing"))))))

(deftest cipher-test
  ;; vectors made by plexus-cipher 2.0, from babashka's Maven procurer
  (let [{:keys [master master-blob cases not-encrypted]}
        (edn/read-string (slurp (io/resource "cipher-vectors.edn")))
        dir (temp-dir "cipher")
        security (io/file dir "settings-security.xml")]
    (spit security (str "<settingsSecurity><master>" master-blob "</master></settingsSecurity>"))
    (is (= master (cipher/master-password security)))
    (doseq [{:keys [dispatcher blob]} cases]
      (is (= dispatcher (cipher/decrypt-password blob {:file security}))))
    (is (= not-encrypted (cipher/decrypt-password not-encrypted {:file security})) "no blob, no change")))
