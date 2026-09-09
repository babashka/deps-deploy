(ns babashka.deps-deploy-test
  (:require [babashka.deps-deploy :as publish]
            [babashka.deps-deploy.cipher :as cipher]
            [babashka.deps-deploy.gpg :as gpg]
            [babashka.deps-deploy.settings :as settings]
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

;; A repository: every PUT is remembered by path, GET serves what was put,
;; and a path in refuse gets that response instead.
(defn- fake-repo [auth-header]
  (let [store (atom {})
        refuse (atom {})
        handler (fn [{:keys [request-method uri headers body]}]
                  (cond
                    (not= auth-header (get headers "authorization")) {:status 401 :body "who?"}
                    (get @refuse uri) (get @refuse uri)
                    (= :put request-method) (do (swap! store assoc uri (slurp body)) {:status 201})
                    (= :get request-method) (if-let [b (get @store uri)] {:status 200 :body b} {:status 404})
                    :else {:status 405}))
        srv (server/run-server handler {:port 0 :legacy-return-value? false})]
    {:store store :refuse refuse :stop #(server/server-stop! srv)
     :url (str "http://localhost:" (server/server-port srv) "/")}))

(def version-paths
  #{"/org/example/demo/1.2.3/demo-1.2.3.jar" "/org/example/demo/1.2.3/demo-1.2.3.jar.md5"
    "/org/example/demo/1.2.3/demo-1.2.3.jar.sha1" "/org/example/demo/1.2.3/demo-1.2.3.pom"
    "/org/example/demo/1.2.3/demo-1.2.3.pom.md5" "/org/example/demo/1.2.3/demo-1.2.3.pom.sha1"
    "/org/example/demo/maven-metadata.xml" "/org/example/demo/maven-metadata.xml.md5"
    "/org/example/demo/maven-metadata.xml.sha1"})

(deftest deploy-test
  (let [dir (temp-dir "deps-deploy")
        jar (io/file dir "demo.jar")
        pom-file (io/file dir "pom.xml")
        _ (spit jar "not really a jar")
        _ (spit pom-file pom)
        {:keys [store refuse stop url]} (fake-repo "Basic dXNlcjpzZWNyZXQ=")]
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
      (testing "Clojars' validation error is spelled out"
        (swap! refuse assoc "/org/example/demo/1.2.3/demo-1.2.3.pom"
               {:status 403 :body "{\"type\":\"https://clojars.org/validation-error\",\"status\":403,\"title\":\"Non-SNAPSHOT redeploy\",\"detail\":\"redeploying non-snapshots is not allowed. See https://bit.ly/3EYzhwT\"}"})
        (is (thrown-with-msg? Exception #"HTTP 403 Non-SNAPSHOT redeploy: redeploying non-snapshots is not allowed. See"
                              (publish/deploy {:artifact (str jar) :pom-file (str pom-file)
                                               :repository {:url url :username "user" :password "secret"}})))
        (reset! refuse {}))
      (finally (stop)))))

(deftest snapshot-test
  (let [dir (temp-dir "deps-deploy-snapshot")
        jar (io/file dir "demo.jar")
        sources (io/file dir "demo-1.2.3-SNAPSHOT-sources.jar")
        pom-file (io/file dir "pom.xml")
        _ (spit jar "jar")
        _ (spit sources "sources")
        _ (spit pom-file (str/replace pom "1.2.3" "1.2.3-SNAPSHOT"))
        {:keys [store stop url]} (fake-repo "Basic dXNlcjpzZWNyZXQ=")
        repository {:url url :username "user" :password "secret"}
        version-dir "/org/example/demo/1.2.3-SNAPSHOT/"
        names (fn [] (->> (keys @store) (filter #(str/starts-with? % version-dir)) (map #(subs % (count version-dir))) set))]
    (try
      (testing "the first build"
        (let [uploaded (publish/deploy {:artifact (str jar) :pom-file (str pom-file) :repository repository})]
          (is (= 12 (count uploaded)) "pom, jar, the version's and the artifact's metadata, each with two checksums")
          (is (some #(re-find #"demo-1\.2\.3-\d{8}\.\d{6}-1\.jar$" %) uploaded) "the jar goes up under a timestamped name, build 1")
          (let [meta (get @store (str version-dir "maven-metadata.xml"))]
            (is (str/includes? meta "<buildNumber>1</buildNumber>"))
            (is (str/includes? meta "<extension>jar</extension>"))
            (is (str/includes? meta "<version>1.2.3-SNAPSHOT</version>")))
          (let [meta (get @store "/org/example/demo/maven-metadata.xml")]
            (is (str/includes? meta "<version>1.2.3-SNAPSHOT</version>"))
            (is (not (str/includes? meta "<release>")) "a snapshot sets no release"))))
      (testing "the second build, with a sources jar, replaces the first's pom and jar entries"
        (publish/deploy {:artifact [(str jar) (str sources)] :pom-file (str pom-file) :repository repository})
        (let [meta (get @store (str version-dir "maven-metadata.xml"))]
          (is (str/includes? meta "<buildNumber>2</buildNumber>"))
          (is (= 3 (count (re-seq #"<snapshotVersion>" meta))) "pom, jar and sources")
          (is (str/includes? meta "<classifier>sources</classifier>"))
          (is (= 2 (count (filter #(re-find #"-2(-sources)?\.jar$" %) (names)))) "two jars in build 2")
          (is (= 1 (count (filter #(re-find #"-1\.jar$" %) (names)))) "build 1's jar stays in the repository")))
      (testing "an entry the new build does not replace is kept"
        (let [meta (publish/snapshot-metadata (get @store (str version-dir "maven-metadata.xml"))
                                              "org.example" "demo" "1.2.3-SNAPSHOT"
                                              {:timestamp "20990101.000000" :updated "20990101000000"} 3
                                              ["demo-1.2.3-20990101.000000-3.pom"])]
          (is (= 3 (count (re-seq #"<snapshotVersion>" meta))))
          (is (str/includes? meta "<value>1.2.3-20990101.000000-3</value>"))
          (is (re-find #"<classifier>sources</classifier>\s*<extension>jar</extension>\s*<value>1.2.3-\d{8}\.\d{6}-2</value>" meta)
              "the sources entry keeps build 2's value")))
      (finally (stop)))))

(deftest install-snapshot-test
  (let [dir (temp-dir "deps-deploy-install-snapshot")
        jar (io/file dir "demo.jar")
        pom-file (io/file dir "pom.xml")
        repo (io/file dir "m2")]
    (spit jar "jar")
    (spit pom-file (str/replace pom "1.2.3" "1.2.3-SNAPSHOT"))
    (System/setProperty "maven.repo.local" (str repo))
    (try
      (let [written (publish/deploy {:installer :local :artifact (str jar) :pom-file (str pom-file)})]
        (is (= 4 (count written)) "pom, jar, the version's and the artifact's local metadata")
        (is (.exists (io/file repo "org/example/demo/1.2.3-SNAPSHOT/demo-1.2.3-SNAPSHOT.jar")) "installed under the plain version")
        (let [meta (slurp (io/file repo "org/example/demo/1.2.3-SNAPSHOT/maven-metadata-local.xml"))]
          (is (str/includes? meta "<localCopy>true</localCopy>"))
          (is (str/includes? meta "<value>1.2.3-SNAPSHOT</value>"))))
      (finally (System/clearProperty "maven.repo.local")))))

(deftest install-test
  (let [dir (temp-dir "deps-deploy-install")
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
  (let [dir (temp-dir "deps-deploy-options")
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
  (let [dir (temp-dir "deps-deploy-sign")]
    (if-let [gpg-script (throwaway-gpg dir)]
      (let [jar (io/file dir "demo.jar")
            pom-file (io/file dir "pom.xml")
            {:keys [store stop url]} (fake-repo "Basic dXNlcjpzZWNyZXQ=")]
        (spit jar "jar")
        (spit pom-file pom)
        (System/setProperty "deps-deploy.gpg" gpg-script)
        (try
          (let [uploaded (publish/deploy {:artifact (str jar) :pom-file (str pom-file) :sign-releases? true
                                          :repository {:url url :username "user" :password "secret"}})]
            (is (= 15 (count uploaded)) "the two signatures come with checksums of their own")
            (is (str/starts-with? (get @store "/org/example/demo/1.2.3/demo-1.2.3.jar.asc") "-----BEGIN PGP SIGNATURE-----"))
            (is (contains? @store "/org/example/demo/1.2.3/demo-1.2.3.pom.asc.sha1")))
          (testing "a wrong key names gpg's complaint"
            (is (thrown-with-msg? Exception #"gpg failed to sign .*demo-1.2.3.pom \(exit \d+\)"
                                  (publish/deploy {:artifact (str jar) :pom-file (str pom-file) :sign-releases? true
                                                   :sign-key-id "nobody@example.com"
                                                   :repository {:url url :username "user" :password "secret"}}))))
          (finally (System/clearProperty "deps-deploy.gpg") (stop))))
      (println "gpg unavailable, skipping sign-test"))))

(deftest gpg-missing-test
  (System/setProperty "deps-deploy.gpg" (str (io/file (temp-dir "deps-deploy-nogpg") "gpg-that-is-not-there")))
  (try
    (is (thrown-with-msg? Exception #"Could not run .*gpg-that-is-not-there"
                          (gpg/sign! (io/file (System/getProperty "java.io.tmpdir") "x") {})))
    (finally (System/clearProperty "deps-deploy.gpg"))))

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
