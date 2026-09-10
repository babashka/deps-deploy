(ns babashka.deps-deploy.central
  "Maven Central through the Publisher Portal: the files of a release
  zipped into a bundle, uploaded in one request, then validated by the
  portal. Names follow the central-publishing-maven-plugin."
  (:require [babashka.http-client :as http]
            [clojure.string :as str])
  (:import [java.io ByteArrayOutputStream]
           [java.util Base64]
           [java.util.zip ZipEntry ZipOutputStream]))

(def url "https://central.sonatype.com/")

(defn portal?
  "Whether a repository is the portal, by its host."
  [{:keys [url]}]
  (boolean (some->> url (re-find #"^https?://central\.sonatype\.com(/|$)"))))

(defn- bearer
  "The portal's token: user and password joined and base64 encoded."
  [username password]
  (str "Bearer " (.encodeToString (Base64/getEncoder) (.getBytes (str username ":" password) "UTF-8"))))

(defn bundle
  "A zip of [path bytes] entries."
  ^bytes [entries]
  (let [out (ByteArrayOutputStream.)]
    (with-open [zip (ZipOutputStream. out)]
      (doseq [[path ^bytes bytes] entries]
        (.putNextEntry zip (ZipEntry. ^String path))
        (.write zip bytes)
        (.closeEntry zip)))
    (.toByteArray out)))

(defn- field [json name]
  (second (re-find (re-pattern (str "\"" name "\"\\s*:\\s*\"([^\"]*)\"")) (str json))))

(defn- failed [what {:keys [status body]}]
  (ex-info (str "Central " what ": HTTP " status (let [b (str/trim (str body))] (when-not (str/blank? b) (str " " b))))
           {:status status :body body}))

(defn upload!
  "Uploads a bundle and returns its deployment id. publishing-type is
  :automatic, published once validated, or :user-defined, held for the
  portal's Publish button."
  [{:keys [url username password]} name ^bytes zip publishing-type]
  (let [{:keys [status body] :as resp}
        (http/post (str (if (str/ends-with? url "/") url (str url "/")) "api/v1/publisher/upload")
                   {:headers {"Authorization" (bearer username password)}
                    :query-params {"name" name
                                   "publishingType" (if (= :automatic publishing-type) "AUTOMATIC" "USER_DEFINED")}
                    :multipart [{:name "bundle" :content zip :file-name (str name ".zip")
                                 :content-type "application/octet-stream"}]
                    :throw false})]
    (when-not (= 201 status) (throw (failed "refused the upload" resp)))
    (str/trim (str body))))

(defn status
  "The deployment's state, one of PENDING VALIDATING VALIDATED PUBLISHING
  PUBLISHED FAILED, and the portal's errors when it failed."
  [{:keys [url username password]} deployment-id]
  (let [{:keys [status body] :as resp}
        (http/post (str (if (str/ends-with? url "/") url (str url "/")) "api/v1/publisher/status")
                   {:headers {"Authorization" (bearer username password)}
                    :query-params {"id" deployment-id}
                    :throw false})]
    (when-not (= 200 status) (throw (failed "would not report the status" resp)))
    {:state (field body "deploymentState")
     :errors (second (re-find #"\"errors\"\s*:\s*(\{.*\})" (str body)))
     :body body}))

(defn wait!
  "Polls until the deployment reaches state, every interval-ms, or throws
  when it fails or timeout-ms passes."
  [repo deployment-id state {:keys [interval-ms timeout-ms] :or {interval-ms 5000 timeout-ms 600000}}]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)
        done? (case state
                "PUBLISHED" #{"PUBLISHED"}
                "VALIDATED" #{"VALIDATED" "PUBLISHING" "PUBLISHED"})]
    (loop []
      (let [{current :state errors :errors} (status repo deployment-id)]
        (cond (= "FAILED" current)
              (throw (ex-info (str "Central rejected deployment " deployment-id
                                   (when errors (str ": " errors)))
                              {:deployment-id deployment-id :errors errors}))
              (done? current) current
              (> (System/currentTimeMillis) deadline)
              (throw (ex-info (str "Central deployment " deployment-id " still " current " after "
                                   (quot timeout-ms 1000) " s")
                              {:deployment-id deployment-id :state current}))
              :else (do (Thread/sleep ^long interval-ms) (recur)))))))
