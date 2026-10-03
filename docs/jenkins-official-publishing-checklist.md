# Jenkins Official Publishing Checklist

This checklist tracked the migration of this plugin from a personal repository to official
Jenkins distribution. The repository now lives at `jenkinsci/ai-agent-plugin`.

## Locked Decisions

- Plugin ID (`artifactId`): `ai-agent` (final, must not change after official publication).
- `jenkinsci` repository: `ai-agent-plugin`.
- Previous source repository (deleted): `https://github.com/bvolpato/jenkins-ai-agent-plugin`.

## 1. Pre-Hosting Readiness ✅

- [x] License is present and declared in `pom.xml`.
- [x] Jenkinsfile is present for Jenkins CI.
- [x] CI is green on Java 17 and Java 21.
- [x] Plugin metadata uses stable ID and Jenkins baseline.
- [x] Security policy and contribution docs are present.

## 2. Hosting Request ✅

- [x] Hosting request opened and accepted.

## 3. Repository Permissions Updater (RPU) ✅

- [x] RPU file exists with `github: "jenkinsci/ai-agent-plugin"`.
- [x] `developers` contains at least one Jenkins account ID.
- [x] `cd.enabled: true` is present.

## 4. Post-Transfer Repository Update ✅

- [x] `pom.xml` `<url>` points to `https://github.com/jenkinsci/ai-agent-plugin`.
- [x] README badge/link URLs updated from `bvolpato/jenkins-ai-agent-plugin` to `jenkinsci/ai-agent-plugin`.
- [x] Plugin ID remains `ai-agent`.

## 5. Enable Jenkins CD Workflow ✅

- [x] `.github/workflows/cd.yaml` is present.

## 6. First Official Release ✅

- [x] Merge a PR with a release-triggering label (`bug`, `enhancement`, or `developer`) or run `cd.yaml` manually.
- [x] Confirm GitHub Actions CD run is successful.
- [x] Confirm release appears on `https://plugins.jenkins.io/ai-agent/`.
- [x] Confirm update center metadata shows the released version.

Verified on October 3, 2026: release
[`162.vc9c61fc50262`](https://github.com/jenkinsci/ai-agent-plugin/releases/tag/162.vc9c61fc50262)
was published by the successful
[CD run](https://github.com/jenkinsci/ai-agent-plugin/actions/runs/36248877571).
The [plugin directory](https://plugins.jenkins.io/ai-agent/) and
[update center metadata](https://updates.jenkins.io/current/plugin-versions.json)
both list this version, requiring Jenkins 2.528.3.

## 7. Cleanup and Transition

- [x] Retire personal-repo `release.yml` flow to avoid multiple release paths.
- [x] Delete personal repository `bvolpato/jenkins-ai-agent-plugin`.
- [x] Update all references to point to `jenkinsci/ai-agent-plugin`.
- [x] Keep `main` on `${changelist}` with the default `999999-SNAPSHOT`; Jenkins CD computes the release version without a manual version bump.
