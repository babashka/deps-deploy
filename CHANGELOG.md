# Changelog

## Unreleased

- `deploy`: a stand-in for slipset/deps-deploy: a jar and POM with checksums and `maven-metadata.xml` to any Maven repository by HTTP, or into `~/.m2` with `:installer :local`, gpg signatures with `:sign-releases?`, snapshots, several jars in one `:artifact`; deps-deploy's options.
