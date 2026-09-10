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
| `:artifact`        | the jar, required; uploaded as `artifact-version.jar`, or `artifact-version-classifier.jar` when its name has that shape. A vector of paths publishes several jars, a `-sources` one say, in one go |
| `:pom-file`        | the POM, default `pom.xml`                                                                |
| `:installer`       | `:remote`, the default, or `:local`                                                       |
| `:repository`      | nothing for Clojars; `:central` for Maven Central; a URL; `{:url ... :id ... :username ... :password ...}`; or `{"id" {:url ...}}` as deps-deploy has it |
| `:auto-publish`    | Central only: publish once validated instead of waiting for the portal's Publish button    |
| `:sign-releases?`  | sign the jar and POM with gpg and publish the `.asc` files                                |
| `:sign-key-id`     | the gpg key to sign with, the default key otherwise                                       |
| `:read-passphrase?`| `true` always asks the gpg passphrase on the console, `false` never does; see Signing      |
| `:settings`        | a settings.xml to read credentials from, default `~/.m2/settings.xml`                     |

## What it does

Uploads each file with an `.md5` and `.sha1` next to it, the signatures
included, then the artifact's `maven-metadata.xml` with the version added.
Returns the URLs uploaded, or with `:installer :local` the files written,
in order. Throws on the first transfer that fails, with the URL, the HTTP
status and what the repository said.

A `-SNAPSHOT` version goes up the way Maven does it: the files under a
timestamped name with the next build number, the version's own
`maven-metadata.xml` updated first, then the artifact's. Every upload is
compared byte for byte with what slipset/deps-deploy sends, Aether
underneath, in the test suite.

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

A `:repository` string that is not a URL is a repository id, as in
deps-deploy: its URL comes from `:mvn/repos` in the project's `deps.edn`
and the user's `~/.clojure/deps.edn`, the project winning, and its
credentials from the `<server>` with that id in settings.xml. `"clojars"`
and `"central"` are built in, Central being its portal. The
`{"id" {...}}` form without a `:url` looks the URL up the same way.

## Signing

`:sign-releases? true` runs `gpg --armour --detach-sign` on the jar and
the POM and uploads the signatures with checksums of their own, as Clojars
requires. The passphrase is asked on the console, as deps-deploy does,
unless `:sign-key-id` is given, when gpg-agent supplies it. Without a
console, in CI say, gpg-agent supplies it either way, where deps-deploy
throws. `:read-passphrase? true` or `false` overrides. `DEPS_DEPLOY_GPG`
names another gpg program.

## Maven Central

Central no longer takes Maven uploads since OSSRH closed in 2025; a
release goes to its Publisher Portal as one signed bundle. `:repository
:central` does that:

```clojure
(dd/deploy {:repository :central
            :artifact ["target/lib.jar" "target/lib-sources.jar"]
            :pom-file "target/classes/META-INF/maven/my.group/lib/pom.xml"
            :sign-releases? true})
```

The portal validates the bundle and holds it for the Publish button on
https://central.sonatype.com/publishing/deployments; `:auto-publish true`
publishes it as soon as it validates. `deploy` waits for either and
returns the deployment id, or throws with the portal's errors.

What Central checks, and what this does about it:

- Signatures on every file: the deploy is always signed, and the public
  key must be on a keyserver Central reads, keys.openpgp.org with the
  email verified for instance.
- A `-sources` jar: give it in `:artifact`.
- A `-javadoc` jar: an empty one is added when you have none.
- POM with name, description, url, licenses, developers and scm: with
  tools.build, `write-pom`'s `:pom-data` and `:scm` supply them.

Credentials are a portal user token, from the `central` server in
`~/.m2/settings.xml` or `CENTRAL_USERNAME` and `CENTRAL_PASSWORD`.
Snapshots are not supported on Central.

## Differences from slipset/deps-deploy

- Keyword option values are not looked up as aliases in `deps.edn`; `:repository :central` means Maven Central.
- `CLOJARS_USERNAME` and `CLOJARS_PASSWORD` apply to Clojars only, not to every repository.
- No S3 repositories.
- Maven Central's portal, which deps-deploy has no path to since OSSRH closed.

## License

Copyright © 2026 Michiel Borkent

Distributed under the EPL License. See LICENSE. The cipher for encrypted
passwords follows plexus-cipher and plexus-sec-dispatcher, Apache License
2.0, see NOTICE.md.
