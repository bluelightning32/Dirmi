RELEASING
=========

This file outlines how to publish a new release to Maven Central.

Prerequisites 
-------------

* You will need the Cojen GPG key and passphrase to continue.  Contact
  @jesterpm or @broneill to obtain them.

* You will need an account with Sonatype Nexus. You can create that
  [here](https://issues.sonatype.org/secure/Signup!default.jspa). Contact
  @jesterpm or @broneill for access to the Cojen repository.

Process
-------

1. Increment the version number appropriately.
   Use [Semantic Versioning](http://semver.org/).

    VERSION=2.2.5
    mvn versions:set -DnewVersion=$VERSION

2. Verify the release and make sure all is well.

    mvn clean verify -P release

3. Commit and tag the latest release.

    git commit -am "Release $VERSION"
    git tag -a v$VERSION -m "Release $VERSION"

4. Deploy to Sonatype:

    mvn clean deploy -P release

5. Push commit and tag to GitHub

    git push origin master
    git push origin v$VERSION


