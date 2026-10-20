# Release Guide

Releases are made by three GitHub Actions workflows:

| Workflow | Runs when | What it does |
|---|---|---|
| **Prepare release** (`prepare-release.yml`) | You start it | Sets `pom.xml` to the new version on `release/v<version>` and opens a PR to `main`. |
| **Release** (`release.yml`) | That PR is merged | Tags the merge commit, creates a **draft** GitHub release with release notes, moves `main` to the next snapshot, then runs Publish packages. |
| **Publish packages** (`publish-packages.yml`) | Called by Release, or started by you to retry | Builds and tests the tag, publishes it, and makes the release public once everything is out. |

## Making a release

1. **Actions → Prepare release → Run workflow** with the new version, e.g. `0.2.0`. Below 1.0, a
   breaking change (`feat!`, `BREAKING CHANGE:`) bumps the minor version.
2. Review and merge the `chore(release): release 0.2.0` pull request.
3. Wait for the **Release** run. When everything is published, the draft release becomes public
   and `main` is at `0.2.1-SNAPSHOT`. If publishing fails, the release stays a draft (see below).

## What gets published

`io.github.ismailza:resilient-cache-spring-boot-starter:<version>`, with sources, javadoc and
signatures, in this order, skipping whatever is already published:

1. Build and test once, then publish to **Maven Central**. The build waits until Central reports
   the version as published, not only validated (`waitUntil` in `pom.xml`).
2. Publish the same files to **GitHub Packages**.
3. Attach the jar to the **GitHub release**.

The jar is attached last, so a jar on the release means everything is published; only then does
the release become public.

## Release notes

Generated when the draft is created, by `.github/scripts/release-notes.mjs`, from the commits since
the previous tag. Only breaking changes, `feat` and `fix` commits are listed; the notes end with
the dependency to add. The draft can be edited by hand before it is published.

## When something fails

The release stays a draft. Retry with:

```sh
gh workflow run publish-packages.yml -f tag=v0.2.0
```

or **Re-run failed jobs** on the failed run. A retry is safe to run as often as needed:

- It only builds the existing tag. It never bumps a version or creates or moves a tag.
- Anything already published is skipped. Maven Central and GitHub Packages also refuse to
  overwrite a published version.
- Before publishing, it asks each repository whether the version exists. Only "yes" (HTTP 200)
  or "no" (HTTP 404) is accepted; an authentication error, rate limit or outage stops the job
  instead of guessing.

**Retry** when the code is fine and publishing failed (outage, expired credentials, flaky runner).
**Release a new patch version** when the fix needs a change to code, tests or `pom.xml`: a retry
rebuilds exactly what the tag contains.

If the next-snapshot push to `main` is refused (for example once `main` is protected), the
Release run opens a `chore/start-<version>-SNAPSHOT` pull request instead; merge it.

### Known edge case

Maven Central can take up to about 30 minutes to show a newly published version on
`repo1.maven.org`, which is where the workflow checks. A retry started in that window tries to
publish again; Central rejects the duplicate and the job fails without changing anything. Wait
and retry, and check <https://central.sonatype.com/publishing/deployments> if in doubt.

## Required setup

The `io.github.ismailza` namespace must be verified on <https://central.sonatype.com>.

| Secret | Purpose |
|---|---|
| `GPG_SIGNING_KEY`, `GPG_SIGNING_KEY_PASSWORD` | Sign the artifacts (required by Maven Central). The public key must be on a key server Central reads, such as `keys.openpgp.org`. |
| `CENTRAL_TOKEN_USERNAME`, `CENTRAL_TOKEN_PASSWORD` | Central Portal user token. |
| `RELEASE_TOKEN` (optional) | PAT with `contents` and `pull-requests` write. GitHub does not run CI on PRs opened with the default `GITHUB_TOKEN`; this only matters if `main` requires status checks. |

Everything else uses the workflow's `GITHUB_TOKEN`. Each job asks only for the permissions it
needs, and only one publish run per release runs at a time.

## Building like the release

The release uses the Maven Wrapper (`./mvnw`). Builds are reproducible
(`project.build.outputTimestamp` in `pom.xml`), so rebuilding a tag gives the same jar.

```sh
./mvnw -B verify -P release -Dgpg.skip
```
