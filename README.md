# deps-deploy

Deploy jars and POM files to Clojars or another Maven repository, or install
them in `~/.m2/repository`. Runs in
[babashka](https://github.com/babashka/babashka) and on the JVM.

To migrate from [slipset/deps-deploy](https://github.com/slipset/deps-deploy),
change the function name in your `build.clj`:

```clojure
;; before
((requiring-resolve 'deps-deploy.deps-deploy/deploy) opts)
;; after
((requiring-resolve 'babashka.deps-deploy/deploy) opts)
```

The options and command-line arguments are compatible, with the
[differences listed below](#differences-from-slipsetdeps-deploy).

## Usage

Add this dependency to the `:deps` map in your `deps.edn` or `bb.edn`:

```clojure
io.github.babashka/deps-deploy {:mvn/version "0.0.1"}
```

To deploy to Clojars, set `CLOJARS_USERNAME` and `CLOJARS_PASSWORD`.
Use a Clojars deploy token as the password.

```clojure
(require '[babashka.deps-deploy :as dd])

;; Deploy to Clojars
(dd/deploy {:artifact "target/lib.jar"
            :pom-file "target/classes/META-INF/maven/my.group/lib/pom.xml"})

;; Install locally
(dd/deploy {:installer :local
            :artifact "target/lib.jar"
            :pom-file "target/classes/META-INF/maven/my.group/lib/pom.xml"})
```

With tools.build, use `write-pom` to create the POM and `jar` to create the jar.

From the command line, these commands deploy to Clojars using `pom.xml` in
the current directory:

```shell
bb -m babashka.deps-deploy deploy target/lib.jar
clojure -M -m babashka.deps-deploy deploy target/lib.jar
```

## Options

| Option | Description |
|--------|-------------|
| `:artifact` | Required. Path to a jar, or a vector of paths to deploy several jars together. See file names below. |
| `:pom-file` | Path to the POM. Default: `"pom.xml"`. |
| `:installer` | `:remote` to deploy to a repository, or `:local` to install locally. Default: `:remote`. |
| `:repository` | Default: Clojars. Accepts `:central`, a URL, a repository id, `{:url ... :id ... :username ... :password ...}`, or `{"id" {:url ...}}`. |
| `:auto-publish` | Maven Central only. Set to `true` to publish automatically after validation. Default: wait for manual publication in the portal. |
| `:sign-releases?` | Sign the jars and POM with gpg and upload the `.asc` files. Maven Central always requires signing. |
| `:sign-key-id` | The gpg key to use for signing. If omitted, gpg uses its default key. |
| `:read-passphrase?` | `true` always prompts for the gpg passphrase on the console. `false` disables the prompt. See [Signing](#signing) for the default behavior. |
| `:settings` | Path to the Maven settings file for credentials. Default: `~/.m2/settings.xml`. |

Jars are uploaded as `artifact-version.jar`, using the coordinates in the POM.
To include a classifier, name the input file `artifact-version-classifier.jar`.
For example, use `lib-1.0.0-sources.jar` for the sources of `lib` version `1.0.0`.

## Deployment behavior

For Maven repositories other than Central, `deploy` uploads each file with
`.md5` and `.sha1` checksums, including signatures. It also updates
`maven-metadata.xml` with the version.

For `-SNAPSHOT` versions, file names include a timestamp and the next build
number. The deployment updates the version metadata before the artifact
metadata.

`deploy` returns the uploaded URLs in order. With `:installer :local`, it
returns the paths of the files written. If an HTTP transfer fails, it throws
an exception with the URL, HTTP status, and repository response.

Maven Central uses a bundle upload and returns a deployment id.
See [Maven Central](#maven-central).

## Credentials

Credentials come from the first available source, in this order:

1. `:username` and `:password` in the repository map.
2. Environment variables: `CLOJARS_USERNAME` and `CLOJARS_PASSWORD` for Clojars,
   or `CENTRAL_USERNAME` and `CENTRAL_PASSWORD` for Maven Central.
3. The `<server>` entry with a matching repository id in the Maven settings file.

Blank values count as unset. Encrypted passwords use Maven's
`settings-security.xml` format.

To deploy to a custom repository, pass a repository map as `:repository`:

```clojure
{:url "https://repo.example.com/releases/"
 :id "example"            ; the <server> id in settings.xml
 :username "..."          ; or leave both out and use settings.xml
 :password "..."}
```

To use a repository id, set `:repository` to a string such as `"releases"`.
The URL comes from `:mvn/repos` in the project or user `deps.edn`.
Project settings take precedence. The user file defaults to `~/.clojure/deps.edn`.
Credentials come from the matching `<server>` entry in the Maven settings file.

The built-in ids are `"clojars"` and `"central"`. The `"central"` id uses the
Publisher Portal. A `{"id" {...}}` map without `:url` also resolves its URL
by repository id.

To override the default Clojars URL, set `CLOJARS_URL`.

## Signing

To sign the jars and POM, set `:sign-releases? true`.
The deployment runs `gpg --armour --detach-sign` and uploads the signatures.
For Clojars, it also uploads checksums for each signature.

By default, signing prompts for a passphrase on the console.
If you specify `:sign-key-id`, gpg-agent handles the passphrase instead.
Without a console, such as in CI, gpg-agent also handles the passphrase.

To always prompt on the console, set `:read-passphrase? true`.
This requires a console. To disable the prompt, set `:read-passphrase? false`.
To use another gpg executable, set `DEPS_DEPLOY_GPG`.

## Maven Central

To deploy a release through the Maven Central Publisher Portal, set
`:repository :central`:

```clojure
(dd/deploy {:repository :central
            :artifact ["target/lib.jar" "target/lib-1.0.0-sources.jar"]
            :pom-file "target/classes/META-INF/maven/my.group/lib/pom.xml"
            :sign-releases? true})
```

This example assumes that the POM specifies artifact `lib` and version `1.0.0`.

By default, `deploy` waits for validation and returns the deployment id.
Then publish the release from the
[deployments page](https://central.sonatype.com/publishing/deployments).

To publish automatically after validation, set `:auto-publish true`.
With this option, `deploy` waits for publication before it returns.
If validation or publication fails, it throws an exception with the portal errors.

Before you deploy:

- Publish the public key for your signing key on a keyserver that Central supports.
- Include a sources jar in `:artifact`, named `artifact-version-sources.jar`.
- Include a javadoc jar, or let deps-deploy add a placeholder jar.
- Include name, description, url, licenses, developers, and scm in the POM.
  With tools.build, use the `:pom-data` and `:scm` options of `write-pom`.

Use a portal user token for credentials. Set `CENTRAL_USERNAME` and
`CENTRAL_PASSWORD`, or add a `central` server entry to `~/.m2/settings.xml`.

This library supports releases only for Maven Central.

## Differences from slipset/deps-deploy

- Keyword option values do not resolve to aliases in `deps.edn`.
  `:repository :central` selects Maven Central.
- `CLOJARS_USERNAME` and `CLOJARS_PASSWORD` apply only to Clojars.
- S3 repositories are not supported.
- Maven Central deployments use the Publisher Portal.

## License

Copyright © 2026 Michiel Borkent

Distributed under the EPL License. See LICENSE. The cipher for encrypted
passwords follows plexus-cipher and plexus-sec-dispatcher, Apache License
2.0, see NOTICE.md.
