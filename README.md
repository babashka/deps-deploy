# deps-deploy

A stand-in for [slipset/deps-deploy](https://github.com/slipset/deps-deploy)
that runs in [babashka](https://github.com/babashka/babashka) as well as
on the JVM. Deploys a jar and its POM to a Maven repository, Clojars first,
or installs them in `~/.m2`. Plain Clojure over `babashka.http-client`, no
Maven underneath.

Same options, same `-main`. A `build.clj` moves over by changing one
symbol:

```clojure
;; before
((requiring-resolve 'deps-deploy.deps-deploy/deploy) opts)
;; after
((requiring-resolve 'babashka.deps-deploy/deploy) opts)
```

## Usage

```clojure
;; deps.edn or bb.edn
io.github.babashka/deps-deploy {:mvn/version "0.0.1"}
```

```clojure
(require '[babashka.deps-deploy :as dd])

;; to Clojars, credentials from CLOJARS_USERNAME and CLOJARS_PASSWORD
(dd/deploy {:artifact "target/lib.jar"
            :pom-file "target/classes/META-INF/maven/my.group/lib/pom.xml"})

;; into ~/.m2/repository
(dd/deploy {:installer :local
            :artifact "target/lib.jar"
            :pom-file "target/classes/META-INF/maven/my.group/lib/pom.xml"})
```

With tools.build, `write-pom` writes the POM at that path and `jar` the
jar. From the command line:

```
bb -m babashka.deps-deploy deploy target/lib.jar
clojure -M -m babashka.deps-deploy deploy target/lib.jar
```

## Options

| option             | meaning                                                                                   |
|--------------------|-------------------------------------------------------------------------------------------|
| `:artifact`        | the jar, required; uploaded as `artifact-version.jar`, or `artifact-version-classifier.jar` when its name has that shape |
| `:pom-file`        | the POM, default `pom.xml`                                                                |
| `:installer`       | `:remote`, the default, or `:local`                                                       |
| `:repository`      | nothing for Clojars; a URL; `{:url ... :id ... :username ... :password ...}`; or `{"id" {:url ...}}` as deps-deploy has it |
| `:sign-releases?`  | sign the jar and POM with gpg and publish the `.asc` files                                |
| `:sign-key-id`     | the gpg key to sign with, the default key otherwise                                       |
| `:read-passphrase?`| ask for the gpg passphrase on the console; without it gpg-agent supplies it               |
| `:settings`        | a settings.xml to read credentials from, default `~/.m2/settings.xml`                     |

## What it does

Uploads each file with an `.md5` and `.sha1` next to it, the signatures
included, then the artifact's `maven-metadata.xml` with the version added.
Returns the URLs uploaded, or with `:installer :local` the files written,
in order. Throws on the first transfer that fails, with the URL and the
HTTP status.

## Credentials

In order: `:username` and `:password` in the repository map; for Clojars
the `CLOJARS_USERNAME` and `CLOJARS_PASSWORD` environment variables, a
deploy token as the password; the `<server>` in `~/.m2/settings.xml`
whose id is the repository's. Blank values count as unset. Encrypted
passwords from `settings-security.xml` are read the way Maven reads them.
`CLOJARS_URL` replaces the Clojars URL.

```clojure
{:url "https://repo.example.com/releases/"
 :id "example"            ; the <server> id in settings.xml
 :username "..."          ; or leave both out and use settings.xml
 :password "..."}
```

## Signing

`:sign-releases? true` runs `gpg --armour --detach-sign` on the jar and
the POM and uploads the signatures with checksums of their own, as Clojars
requires. gpg-agent handles the passphrase; `:read-passphrase? true` asks
for it on the console instead, and fails with a message when there is no
console. `DEPS_DEPLOY_GPG` names another gpg program.

## Differences from slipset/deps-deploy

- `:repository` strings are URLs, not aliases into `deps.edn`'s `:mvn/repos`.
- `CLOJARS_USERNAME` and `CLOJARS_PASSWORD` apply to Clojars only, not to every repository.
- No S3 repositories.
- Snapshots are refused for now.

## License

Copyright © 2026 Michiel Borkent

Distributed under the EPL License. See LICENSE. The cipher for encrypted
passwords follows plexus-cipher and plexus-sec-dispatcher, Apache License
2.0, see NOTICE.md.
