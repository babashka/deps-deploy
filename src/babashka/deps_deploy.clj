(ns babashka.deps-deploy
  "Deploys a jar and its POM to a Maven repository, or installs them in
  ~/.m2. Plain HTTP with basic auth, no Maven, so it runs on the JVM and in
  babashka. A stand-in for slipset/deps-deploy with its options, so a
  build.clj moves by changing one symbol."
  (:require [babashka.deps-deploy.central :as central]
            [babashka.deps-deploy.gpg :as gpg]
            [babashka.deps-deploy.settings :as settings]
            [babashka.http-client :as http]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.security MessageDigest]))

(defn- clojars []
  {:id "clojars"
   :url (or (System/getenv "CLOJARS_URL") "https://repo.clojars.org/")})

;;;; POM

(defn coordinates
  "group, artifact and version from POM text, the parent's when the POM
  inherits them."
  [pom-text]
  (let [root (settings/parse pom-text)
        parent (settings/child root "parent")
        get (fn [tag] (or (settings/child-text root tag)
                          (some-> parent (settings/child-text tag))))]
    {:group (get "groupId")
     :artifact (settings/child-text root "artifactId")
     :version (get "version")}))

(defn classifier
  "The classifier in a jar file name, artifact-version-classifier.jar; nil
  for artifact-version.jar or any other name."
  [{:keys [artifact version]} file]
  (let [name (.getName (io/file (str file)))
        prefix (str artifact "-" version "-")]
    (when (and (str/starts-with? name prefix) (str/ends-with? name ".jar"))
      (let [c (subs name (count prefix) (- (count name) 4))]
        (when (re-matches #"[\p{Alnum}_.-]+" c) c)))))

;;;; checksums

(defn- hex [^bytes bs]
  (apply str (map #(format "%02x" (bit-and % 0xff)) bs)))

(defn- digest [algorithm ^bytes bs]
  (hex (.digest (MessageDigest/getInstance algorithm) bs)))

;;;; HTTP

(defn- opts [auth]
  (cond-> {:throw false}
    auth (assoc :basic-auth auth)))

(defn- explain
  "The response body for an error message: Clojars' validation error as
  its title and detail, anything else trimmed."
  [body]
  (let [body (str/trim (str body))
        field (fn [name] (second (re-find (re-pattern (str "\"" name "\":\"([^\"]*)\"")) body)))]
    (cond (str/blank? body) ""
          (str/includes? body "clojars.org/validation-error") (str " " (field "title") ": " (field "detail"))
          :else (str " " body))))

(defn- put!
  "PUTs bytes to url. Throws with the status and body on anything but 2xx."
  [url auth ^bytes body]
  (let [{:keys [status] :as resp} (http/request (assoc (opts auth)
                                                       :method :put
                                                       :uri url
                                                       :body body
                                                       :headers {"Content-Type" "application/octet-stream"}))]
    (when-not (<= 200 status 299)
      (throw (ex-info (str "Could not transfer " url ": HTTP " status (explain (:body resp)))
                      {:url url :status status :body (:body resp)})))
    url))

(defn- get-text
  "The body at url, nil when the repository has nothing there."
  [url auth]
  (let [{:keys [status body]} (http/get url (opts auth))]
    (cond (= 200 status) body
          (#{404 410} status) nil
          :else (throw (ex-info (str "Could not read " url ": HTTP " status) {:url url :status status})))))

;;;; metadata

(defn snapshot? [version]
  (str/ends-with? (str version) "-SNAPSHOT"))

(defn- now
  "The moment of a deploy, as Maven writes it: :updated for lastUpdated,
  :timestamp for snapshot file names."
  []
  (let [t (java.time.ZonedDateTime/now java.time.ZoneOffset/UTC)
        fmt (fn [p] (.format (java.time.format.DateTimeFormatter/ofPattern p) t))]
    {:updated (fmt "yyyyMMddHHmmss") :timestamp (fmt "yyyyMMdd.HHmmss")}))

(defn updated-metadata
  "The artifact's maven-metadata.xml with version added: the existing
  versions kept in order, version appended once, release set to it for a
  release and kept as it was for a snapshot. The shape is Aether's, which
  writes no latest."
  ([existing group artifact version] (updated-metadata existing group artifact version (:updated (now))))
  ([existing group artifact version updated]
   (let [old (when existing (map second (re-seq #"<version>([^<]+)</version>" existing)))
         versions (distinct (concat old [version]))
         release (if (snapshot? version)
                   (when existing (second (re-find #"<release>([^<]+)</release>" existing)))
                   version)]
     (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
          "<metadata>\n"
          "  <groupId>" group "</groupId>\n"
          "  <artifactId>" artifact "</artifactId>\n"
          "  <versioning>\n"
          (when release (str "    <release>" release "</release>\n"))
          "    <versions>\n"
          (apply str (map #(str "      <version>" % "</version>\n") versions))
          "    </versions>\n"
          "    <lastUpdated>" updated "</lastUpdated>\n"
          "  </versioning>\n"
          "</metadata>\n"))))

(defn- build-number
  "The buildNumber in a snapshot's maven-metadata.xml, 0 without one."
  [existing]
  (or (some-> existing (->> (re-find #"<buildNumber>(\d+)</buildNumber>") second) parse-long) 0))

(defn- snapshot-entry
  "The classifier and extension of a snapshot file name, given the stem
  artifact-version it starts with."
  [stem name]
  (let [rest (subs name (count stem))
        [_ classifier extension] (re-matches #"(?:-([^.]+))?\.(.+)" rest)]
    {:classifier classifier :extension extension}))

(defn- old-snapshot-entries [existing]
  (when existing
    (for [entry (re-seq #"(?s)<snapshotVersion>(.*?)</snapshotVersion>" existing)
          :let [text (second entry)
                get (fn [tag] (second (re-find (re-pattern (str "<" tag ">([^<]+)</" tag ">")) text)))]]
      {:classifier (get "classifier") :extension (get "extension") :value (get "value") :updated (get "updated")})))

(defn- snapshot-value
  "The timestamped version a snapshot's files carry: 1.0.0-SNAPSHOT
  deployed as build 3 is 1.0.0-20260909.215341-3."
  [version {:keys [timestamp]} build]
  (str (subs version 0 (- (count version) (count "-SNAPSHOT"))) "-" timestamp "-" build))

(defn snapshot-metadata
  "The version's maven-metadata.xml after a snapshot deploy, as Aether
  writes it: the new build's timestamp and number, one snapshotVersion per
  file uploaded in this deploy, then the earlier entries this deploy did
  not replace."
  [existing group artifact version {:keys [timestamp updated] :as moment} build names]
  (let [value (snapshot-value version moment build)
        stem (str artifact "-" value)
        fresh (map #(assoc (snapshot-entry stem %) :value value :updated updated) names)
        superseded (set (map (juxt :classifier :extension) fresh))
        kept (remove #(superseded ((juxt :classifier :extension) %)) (old-snapshot-entries existing))]
    (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
         "<metadata modelVersion=\"1.1.0\">\n"
         "  <groupId>" group "</groupId>\n"
         "  <artifactId>" artifact "</artifactId>\n"
         "  <versioning>\n"
         "    <lastUpdated>" updated "</lastUpdated>\n"
         "    <snapshot>\n"
         "      <timestamp>" timestamp "</timestamp>\n"
         "      <buildNumber>" build "</buildNumber>\n"
         "    </snapshot>\n"
         "    <snapshotVersions>\n"
         (apply str (for [{:keys [classifier extension value updated]} (concat fresh kept)]
                      (str "      <snapshotVersion>\n"
                           (when classifier (str "        <classifier>" classifier "</classifier>\n"))
                           "        <extension>" extension "</extension>\n"
                           "        <value>" value "</value>\n"
                           "        <updated>" updated "</updated>\n"
                           "      </snapshotVersion>\n")))
         "    </snapshotVersions>\n"
         "  </versioning>\n"
         "  <version>" version "</version>\n"
         "</metadata>\n")))

;;;; repositories and credentials

(defn- repository
  "A flat map with :id and :url from the :repository option: nil for
  Clojars, :central for Maven Central's portal, a URL, a flat map, or
  deps-deploy's {\"id\" {:url ...}}."
  [r]
  (cond (nil? r) (clojars)
        (= :central r) {:id "central" :url central/url}
        (string? r) (if (re-find #"^[a-z0-9+]+://" r)
                      {:url r}
                      (throw (ex-info (str "Repository " r " needs a URL: {\"" r "\" {:url ...}}") {:repository r})))
        (and (map? r) (:url r)) r
        (and (map? r) (= 1 (count r)) (string? (key (first r))) (:url (val (first r))))
        (let [[id m] (first r)] (assoc m :id id))
        :else (throw (ex-info (str "Unknown :repository " (pr-str r)) {:repository r}))))

(defn- present [s]
  (when-not (str/blank? s) s))

(def ^:private env-credentials
  "The environment variables Clojars and Central credentials come from."
  {"clojars" ["CLOJARS_USERNAME" "CLOJARS_PASSWORD"]
   "central" ["CENTRAL_USERNAME" "CENTRAL_PASSWORD"]})

(defn- credentials
  "username and password: the repository's own, then for Clojars
  CLOJARS_USERNAME and CLOJARS_PASSWORD and for Central CENTRAL_USERNAME
  and CENTRAL_PASSWORD, then the <server> with the repository's id in
  settings.xml. Throws when none of them has both."
  [{:keys [id url username password]} settings-file]
  (let [[user-var pass-var] (get env-credentials id)]
    (or (when (and (present username) (present password)) {:username username :password password})
        (when user-var
          (let [u (present (System/getenv user-var)) p (present (System/getenv pass-var))]
            (when (and u p) {:username u :password p})))
        (when id (get (settings/servers (or settings-file (settings/user-settings-file))) id))
        (throw (ex-info (str "No credentials for " (or id url) ": give :username and :password"
                             (when user-var (str ", set " user-var " and " pass-var))
                             (if id (str " or add a <server> with id " id " to settings.xml") " or give the repository an :id for settings.xml"))
                        {:repository (or id url)})))))

(defn- with-slash [url]
  (if (str/ends-with? url "/") url (str url "/")))

;;;; files to publish

(defn- read-bytes [file]
  (with-open [in (io/input-stream file)] (.readAllBytes in)))

(defn- artifacts
  "The :artifact option as a sequence: one jar or several."
  [artifact]
  (if (or (string? artifact) (instance? java.io.File artifact)) [artifact] (seq artifact)))

(defn- passphrase
  "As deps-deploy: asked on the console unless :sign-key-id is given.
  Without a console gpg-agent supplies it, where deps-deploy would throw.
  :read-passphrase? true or false overrides."
  [{:keys [sign-key-id read-passphrase?]}]
  (cond (true? read-passphrase?) (gpg/read-passphrase)
        (false? read-passphrase?) nil
        sign-key-id nil
        (System/console) (gpg/read-passphrase)
        :else nil))

(defn- files
  "The files to publish as [name bytes] pairs: the POM, each jar under its
  Maven name, and with :sign-releases? a gpg signature for each. The names
  carry file-version: the version, or a snapshot's timestamped one."
  [{:keys [artifact sign-releases? sign-key-id] :as options} coords pom-text file-version]
  (let [{:keys [artifact-id]} coords
        jar-name (fn [jar] (str artifact-id "-" file-version (some->> (classifier coords jar) (str "-")) ".jar"))
        pom-name (str artifact-id "-" file-version ".pom")
        plain (into [[pom-name (.getBytes ^String pom-text "UTF-8")]]
                    (map (fn [jar] [(jar-name jar) (read-bytes jar)])) (artifacts artifact))
        signed (when sign-releases?
                 (let [dir (io/file (System/getProperty "java.io.tmpdir") (str "deps-deploy-" (System/nanoTime)))
                       gpg-opts {:key-id sign-key-id :passphrase (passphrase options)}]
                   (.mkdirs dir)
                   (mapv (fn [[name ^bytes bytes]]
                           (let [copy (io/file dir name)]
                             (io/copy bytes copy)
                             [(str name ".asc") (read-bytes (gpg/sign! copy gpg-opts))]))
                         plain)))]
    (into plain signed)))

(defn- version-path [{:keys [group artifact-id version]}]
  (str (str/replace group "." "/") "/" artifact-id "/" version "/"))

;;;; Maven Central

(defn- empty-jar
  "A jar with nothing in it, as Central accepts for javadoc."
  ^bytes []
  (central/bundle [["README.txt" (.getBytes "No javadoc: this is a Clojure library.\n" "UTF-8")]]))

(defn- deploy-central
  "Zips the signed files with md5 and sha1 sidecars, an empty -javadoc jar
  added when none is given, uploads the bundle and waits until the portal
  has validated it, or published it with :auto-publish. Returns the
  deployment id."
  [{:keys [auto-publish] :as options} repo credentials coords pom-text]
  (let [{:keys [group artifact-id version]} coords
        _ (when (snapshot? version)
            (throw (ex-info "Central's portal takes releases only; snapshots are not supported" {:version version})))
        _ (when (false? (:sign-releases? options))
            (throw (ex-info "Central requires signatures; leave :sign-releases? on" {})))
        jars (vec (artifacts (:artifact options)))
        classified (fn [c] (some #(= c (classifier coords %)) jars))
        _ (when-not (classified "sources")
            (throw (ex-info "Central requires a -sources jar; add it to :artifact" {:artifact (:artifact options)})))
        jars (if (classified "javadoc")
               jars
               (let [dir (io/file (System/getProperty "java.io.tmpdir") (str "deps-deploy-" (System/nanoTime)))
                     javadoc (io/file dir (str artifact-id "-" version "-javadoc.jar"))]
                 (.mkdirs dir)
                 (io/copy (empty-jar) javadoc)
                 (conj jars (str javadoc))))
        to-upload (files (assoc options :sign-releases? true :artifact jars) coords pom-text version)
        with-sums (mapcat (fn [[name ^bytes bytes]]
                            (cond-> [[name bytes]]
                              (not (str/ends-with? name ".asc"))
                              (conj [(str name ".md5") (.getBytes ^String (digest "MD5" bytes) "UTF-8")]
                                    [(str name ".sha1") (.getBytes ^String (digest "SHA-1" bytes) "UTF-8")])))
                          to-upload)
        dir (version-path coords)
        zip (central/bundle (map (fn [[name bytes]] [(str dir name) bytes]) with-sums))
        deployment-name (str group ":" artifact-id ":" version)
        publishing-type (if auto-publish :automatic :user-defined)]
    (println "Deploying" deployment-name "to Central as" (:username credentials)
             (if auto-publish "and publishing" "for you to publish on the portal"))
    (let [id (central/upload! (merge repo credentials) deployment-name zip publishing-type)]
      (println "Deployment" id "uploaded, waiting for the portal")
      (let [state (central/wait! (merge repo credentials) id (if auto-publish "PUBLISHED" "VALIDATED") options)]
        (println "Deployment" id state)
        id))))

;;;; remote

(defn- upload-all
  "Uploads the files, a snapshot's version-level maven-metadata.xml, then
  the artifact's maven-metadata.xml, each with md5 and sha1 sidecars."
  [options repo {:keys [username password]} coords pom-text]
  (let [auth [username password]
        {:keys [group artifact-id version]} coords
        version-url (str (with-slash (:url repo)) (version-path coords))
        metadata-url (str (with-slash (:url repo)) (str/replace group "." "/") "/" artifact-id "/maven-metadata.xml")
        upload! (fn [url ^bytes body]
                  [(put! url auth body)
                   (put! (str url ".md5") auth (.getBytes ^String (digest "MD5" body) "UTF-8"))
                   (put! (str url ".sha1") auth (.getBytes ^String (digest "SHA-1" body) "UTF-8"))])
        moment (now)
        snapshot (snapshot? version)
        old-snapshot (when snapshot (get-text (str version-url "maven-metadata.xml") auth))
        build (inc (build-number old-snapshot))
        file-version (if snapshot (snapshot-value version moment build) version)
        to-upload (files options coords pom-text file-version)
        metadata (updated-metadata (get-text metadata-url auth) group artifact-id version (:updated moment))]
    (println "Deploying" (str group "/" artifact-id "-" version) "to" (or (:id repo) (:url repo)) "as" username)
    (into []
          (concat (mapcat (fn [[name bytes]] (upload! (str version-url name) bytes)) to-upload)
                  (when snapshot
                    (upload! (str version-url "maven-metadata.xml")
                             (.getBytes ^String (snapshot-metadata old-snapshot group artifact-id version moment build (map first to-upload)) "UTF-8")))
                  (upload! metadata-url (.getBytes ^String metadata "UTF-8"))))))

(defn- deploy-remote
  "To Central's portal as a bundle, to any other repository as PUTs."
  [{:keys [settings] :as options} coords pom-text]
  (let [repo (repository (:repository options))
        creds (credentials repo settings)]
    (if (central/portal? repo)
      (deploy-central options repo creds coords pom-text)
      (upload-all options repo creds coords pom-text))))

;;;; local

(defn- local-repository
  "~/.m2/repository, or the maven.repo.local property as Maven honours it."
  []
  (if-let [p (System/getProperty "maven.repo.local")]
    (io/file p)
    (io/file (System/getProperty "user.home") ".m2" "repository")))

(defn- local-snapshot-metadata
  "The version's maven-metadata-local.xml for an installed snapshot: a
  local copy under the plain version, as Maven's install writes it."
  [group artifact version updated names]
  (let [stem (str artifact "-" version)]
    (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
         "<metadata modelVersion=\"1.1.0\">\n"
         "  <groupId>" group "</groupId>\n"
         "  <artifactId>" artifact "</artifactId>\n"
         "  <versioning>\n"
         "    <lastUpdated>" updated "</lastUpdated>\n"
         "    <snapshot>\n"
         "      <localCopy>true</localCopy>\n"
         "    </snapshot>\n"
         "    <snapshotVersions>\n"
         (apply str (for [name names
                          :let [{:keys [classifier extension]} (snapshot-entry stem name)]]
                      (str "      <snapshotVersion>\n"
                           (when classifier (str "        <classifier>" classifier "</classifier>\n"))
                           "        <extension>" extension "</extension>\n"
                           "        <value>" version "</value>\n"
                           "        <updated>" updated "</updated>\n"
                           "      </snapshotVersion>\n")))
         "    </snapshotVersions>\n"
         "  </versioning>\n"
         "  <version>" version "</version>\n"
         "</metadata>\n")))

(defn- deploy-local
  "Writes the files under the plain version, a snapshot's version-level
  maven-metadata-local.xml, then the artifact's maven-metadata-local.xml."
  [options coords pom-text]
  (let [{:keys [group artifact-id version]} coords
        dir (io/file (local-repository) (version-path coords))
        artifact-dir (.getParentFile dir)
        metadata (io/file artifact-dir "maven-metadata-local.xml")
        {:keys [updated]} (now)
        to-write (files options coords pom-text version)]
    (println "Installing" (str group "/" artifact-id "-" version) "in" (str (local-repository)))
    (.mkdirs dir)
    (let [written (mapv (fn [[name ^bytes bytes]]
                          (let [f (io/file dir name)]
                            (io/copy bytes f)
                            (str f)))
                        to-write)
          written (if (snapshot? version)
                    (let [f (io/file dir "maven-metadata-local.xml")]
                      (spit f (local-snapshot-metadata group artifact-id version updated (map first to-write)))
                      (conj written (str f)))
                    written)]
      (spit metadata (updated-metadata (when (.exists metadata) (slurp metadata)) group artifact-id version updated))
      (conj written (str metadata)))))

;;;; entry points

(defn deploy
  "Deploys a jar and its POM to a Maven repository, or installs them in
  ~/.m2/repository. The options are deps-deploy's:

    :artifact         path to the jar, required; uploaded as
                      artifact-version.jar, or artifact-version-classifier.jar
                      when its name has that shape. A vector of paths
                      publishes several jars, a -sources one say, in one go
    :pom-file         path to the POM, default \"pom.xml\"
    :installer        :remote, the default, or :local
    :repository       nil for Clojars; :central for Maven Central's portal;
                      a URL; {:url :id :username :password}; or
                      {\"id\" {:url ...}} as deps-deploy has it
    :auto-publish     Central only: publish once validated; without it the
                      bundle waits for the Publish button on the portal
    :sign-releases?   sign the jar and POM with gpg and publish the .asc files
    :sign-key-id      the gpg key to sign with; gpg-agent then supplies the
                      passphrase, otherwise it is asked on the console, as
                      deps-deploy does. Without a console gpg-agent
                      supplies it either way
    :read-passphrase? true always asks on the console, false never does
    :settings         settings.xml to read credentials from, default ~/.m2/settings.xml

  Credentials: the repository's :username and :password, for Clojars
  then CLOJARS_USERNAME and CLOJARS_PASSWORD, a deploy token as the
  password, for Central CENTRAL_USERNAME and CENTRAL_PASSWORD, a portal
  user token, then the <server> in settings.xml whose id is the
  repository's. CLOJARS_URL replaces the Clojars URL. Blank values count
  as unset.

  Uploads each file with an md5 and sha1 next to it, then the artifact's
  maven-metadata.xml with the version added. A -SNAPSHOT version goes up
  under a timestamped name with the next build number, and the version's
  own maven-metadata.xml is updated before the artifact's. Returns the
  URLs uploaded, or the files written, in order. Throws on the first
  failure, with the URL and status.

  Central takes releases only, signed, with a -sources jar in :artifact;
  an empty -javadoc jar is added when none is given. The files go up as
  one bundle and deploy waits until the portal has validated it, then
  returns the deployment id."
  [{:keys [artifact pom-file installer] :or {pom-file "pom.xml" installer :remote} :as options}]
  (when-not artifact (throw (ex-info "Missing :artifact, the jar to deploy" {})))
  (when-not (#{:remote :local} installer)
    (throw (ex-info (str "Unknown :installer " (pr-str installer) ", use :remote or :local") {:installer installer})))
  (doseq [jar (artifacts artifact)]
    (when-not (.exists (io/file jar)) (throw (ex-info (str "No such file: " jar) {:artifact jar}))))
  (when-not (.exists (io/file pom-file)) (throw (ex-info (str "No such file: " pom-file) {:pom-file pom-file})))
  (let [pom-text (slurp pom-file)
        {:keys [group artifact version] :as coords} (coordinates pom-text)
        coords (assoc coords :artifact-id artifact)
        options (assoc options :pom-file pom-file :installer installer)]
    (when (some str/blank? [group artifact version])
      (throw (ex-info (str "No coordinates in " pom-file) {:pom-file pom-file :coordinates coords})))
    (if (= :local installer)
      (deploy-local options coords pom-text)
      (deploy-remote options coords pom-text))))

(defn -main
  "deploy or install, the jar, then optionally true to sign and a key id,
  as deps-deploy's main."
  [deploy-or-install artifact & [sign-releases sign-key-id]]
  (deploy {:installer (case deploy-or-install
                        "deploy" :remote
                        "install" :local
                        (throw (ex-info (str "Expected deploy or install, got " deploy-or-install) {})))
           :sign-releases? (= "true" sign-releases)
           :sign-key-id sign-key-id
           :artifact artifact}))
