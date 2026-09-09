(ns babashka.deps-deploy.cipher
  "Encrypted passwords in settings.xml, as Maven and tools.deps read them:
  the legacy format of plexus-cipher and plexus-sec-dispatcher 2.0. A
  password is a {...} blob: base64 of an 8-byte salt, one byte of pad
  length, then AES/CBC/PKCS5 ciphertext. Key and IV are the SHA-256 digest
  of password and salt. The master password lives in settings-security.xml,
  encrypted the same way under the fixed password settings.security.
  After plexus-cipher and plexus-sec-dispatcher, Apache License 2.0, see
  NOTICE.md. Copied from babashka's Maven procurer."
  (:require [clojure.data.xml :as xml]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.security MessageDigest]
           [java.util Arrays Base64]
           [javax.crypto Cipher]
           [javax.crypto.spec IvParameterSpec SecretKeySpec]))

(def ^:private master-key "settings.security")

;; DefaultPlexusCipher's ENCRYPTED_STRING_PATTERN: a blob is the text between
;; an unescaped { and the next unescaped }, surrounded by anything.
(def ^:private encrypted-re #"(?s).*?[^\\]?\{(.*?[^\\])\}.*")

(defn encrypted?
  "True when s carries a {...} blob. A bare {} or escaped braces do not."
  [s]
  (boolean (and s (re-matches encrypted-re s))))

(defn- undecorate [s]
  (second (re-matches encrypted-re s)))

(defn decrypt
  "The plaintext of a bare blob, without braces, under password."
  [^String blob ^String password]
  (let [all (.decode (Base64/getDecoder) blob)
        total (alength all)
        salt (Arrays/copyOfRange all 0 8)
        pad-len (aget all 8)
        ciphertext (Arrays/copyOfRange all 9 (- total pad-len))
        digest (doto (MessageDigest/getInstance "SHA-256")
                 (.update (.getBytes password "UTF-8"))
                 (.update salt 0 8))
        key-and-iv (.digest digest)
        cipher (doto (Cipher/getInstance "AES/CBC/PKCS5Padding")
                 (.init Cipher/DECRYPT_MODE
                        (SecretKeySpec. (Arrays/copyOfRange key-and-iv 0 16) "AES")
                        (IvParameterSpec. (Arrays/copyOfRange key-and-iv 16 32))))]
    (String. (.doFinal cipher ciphertext) "UTF-8")))

(defn- child-text [root tag]
  (some->> (:content root)
           (filter #(and (map? %) (= tag (name (:tag %)))))
           first
           :content
           (filter string?)
           (apply str)
           str/trim
           not-empty))

(defn security-file
  "settings-security.xml: the settings.security system property, or the
  one in ~/.m2."
  []
  (or (System/getProperty "settings.security")
      (str (io/file (System/getProperty "user.home") ".m2" "settings-security.xml"))))

(defn- read-master
  "The encrypted master from a settings-security.xml, following one
  <relocation> the way SecUtil does."
  [path]
  (let [root (xml/parse-str (slurp path))]
    (if-let [relocation (child-text root "relocation")]
      (let [target (io/file (.getParentFile (io/file path)) relocation)]
        (some-> (xml/parse-str (slurp target)) (child-text "master")))
      (child-text root "master"))))

(defn master-password
  "The decrypted master password from file. Throws with a reason when the
  file is missing or has no master."
  [file]
  (when-not (.exists (io/file file))
    (throw (ex-info (str "Cannot decrypt: " file " does not exist") {:file file})))
  (let [master (read-master file)]
    (when (str/blank? master)
      (throw (ex-info (str "Cannot decrypt: no master password in " file) {:file file})))
    (if (encrypted? master)
      (decrypt (undecorate master) master-key)
      master)))

(defn decrypt-password
  "s as Maven sees it: the plaintext of a {...} blob, s itself when there is
  none. server names the settings entry in the error."
  ([s] (decrypt-password s {}))
  ([s {:keys [server file]}]
   (if-not (encrypted? s)
     s
     (let [bare (undecorate s)
           where (if server (str "password of server " server) "password")]
       (when (str/starts-with? bare "[")
         (throw (ex-info (str "Cannot decrypt the " where
                              ": custom dispatchers ([type=...]) are not supported")
                         {:server server})))
       (try
         (decrypt bare (master-password (or file (security-file))))
         (catch Exception e
           (throw (ex-info (str "Cannot decrypt the " where ": " (ex-message e))
                           {:server server} e))))))))
