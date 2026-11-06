# Release publishing

The `4.x` release workflow publishes the same production JAR to GitHub Releases
and CurseForge project **1740768** (`re-etched`). It runs on annotated `v*` tags
whose version matches `gradle.properties` and whose commit belongs to the
corresponding release line. This configuration must be carried into another
development line before tags on that line can use it.

## One-time setup

1. Sign in to the [CurseForge author dashboard](https://authors.curseforge.com/)
   using an account with permission to upload files to Re-Etched. Generate an
   upload API token under **API Tokens**; see the
   [official upload API documentation](https://support.curseforge.com/support/solutions/articles/9000197321-curseforge-upload-api).
2. In the repository's
   [Actions secrets settings](https://github.com/oogabooga-dev/re-etched/settings/secrets/actions),
   select **New repository secret**. Set its name to `CURSEFORGE_TOKEN` and its
   value to the token. Do not commit the token or paste it into issues or chats.

The project ID is public and already configured in the workflow. No additional
GitHub token or separate codec dependency is required.

## Each release

Update the version, `CHANGELOG.md`, `RELEASE_NOTES.md`, and any affected user
documentation. Merge the release changes into `4.x`, then push the matching
annotated tag. The workflow:

1. Verifies the tag, runs a clean test/build, and selects the production JAR.
   Extracts exactly the matching `## [version]` section from `CHANGELOG.md` for
   CurseForge. A missing, empty, or duplicate section fails the build job before
   either platform is published.
2. Creates the GitHub Release with the JAR, SHA-256, and `RELEASE_NOTES.md`.
3. Checks the same JAR's checksum and uploads it to CurseForge with the extracted
   `CHANGELOG.md` section, Minecraft **1.20.1**, **Forge**, **Java 17**, and
   client/server support. `RELEASE_NOTES.md` is used only for GitHub Releases.

Versions containing `-alpha` upload as alpha; other versions with a prerelease
suffix upload as beta; versions without a suffix upload as release. Successful
upload records the CurseForge file ID and link in the workflow summary. Public
availability still depends on CurseForge processing and moderation.

The upload action is pinned to the reviewed mc-publish v3.3.2 commit. The token
is passed only to the credential check and CurseForge upload steps, not to the
Gradle build. Missing credentials or upload errors fail the CurseForge job;
an already-created GitHub Release remains available.

## Recovery and existing releases

After fixing a missing or invalid token, use **Re-run failed jobs**, not
**Re-run all jobs**, to avoid recreating a successful GitHub Release or repeating
a successful CurseForge upload. After a timeout or lost response, check the
CurseForge files page first: the upload may already have been accepted. Automatic
upload retries are disabled for this reason. If the file exists, do not upload
it again; retain its ID and investigate the failed run separately.

If the run's `release-inputs` artifact has expired, recover the JAR and checksum
from its GitHub Release and upload manually after confirming it is absent on
CurseForge. Do not move or recreate a published release tag.

Adding this workflow does not change the workflow stored in the existing
`v4.3.0` tag or automatically re-upload that release. The maintainer already
uploaded 4.3.0 manually. This configuration applies to subsequent tags
containing the change.

The project summary and main description are separate from file changelogs.
Update them in the CurseForge dashboard when features change, using
`CURSEFORGE_DESCRIPTION.md` as the maintained description source. This workflow
uploads files and release notes; it does not edit the project description.
