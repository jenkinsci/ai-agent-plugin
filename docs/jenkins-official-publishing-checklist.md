# Jenkins Official Publishing Checklist

This checklist is the execution plan to move this plugin from a personal repository to official Jenkins distribution.

## Locked Decisions

- Plugin ID (`artifactId`): `ai-agent` (final, must not change after official publication).
- Target `jenkinsci` repository name: `ai-agent-plugin`.
- Current source repository: `https://github.com/bvolpato/jenkins-ai-agent-plugin`.

## 1. Pre-Hosting Readiness (Already Done)

- [x] License is present and declared in `pom.xml`.
- [x] Jenkinsfile is present for Jenkins CI.
- [x] CI is green on Java 17 and Java 21.
- [x] Plugin metadata uses stable ID and Jenkins baseline.
- [x] Security policy and contribution docs are present.

## 2. Open Hosting Request (Next Action)

Open:
`https://github.com/jenkins-infra/repository-permissions-updater/issues/new/choose`
Template: `🏠 Hosting request`

Use these values:

- Repository URL:
  `https://github.com/bvolpato/jenkins-ai-agent-plugin`
- New Repository Name:
  `ai-agent-plugin`
- Description:
  `Jenkins plugin that provides a reusable AI Agent build step to run coding agents (Claude Code, Codex, Cursor Agent, OpenCode, Gemini CLI) with streamed JSON logs, approvals, and usage stats.`
- GitHub users to have commit permission:
  `@bvolpato`
  `@<add-any-co-maintainers>`
- Jenkins project users to have release permission:
  `<your Jenkins account ID(s), not GitHub handles>`
- Automated release via GitHub Actions:
  `Yes`

## 3. Repository Permissions Updater (RPU) PR

After hosting is accepted, ensure a file exists in:
`https://github.com/jenkins-infra/repository-permissions-updater/tree/master/permissions`

Expected file content shape:

```yaml
---
name: "ai-agent"
github: "jenkinsci/ai-agent-plugin"
paths:
  - "io/jenkins/plugins/ai-agent"
developers:
  - "<jenkins-account-id>"
cd:
  enabled: true
```

Checklist:

- [ ] `developers` contains at least one Jenkins account ID.
- [ ] `cd.enabled: true` is present.
- [ ] `github` is `jenkinsci/ai-agent-plugin`.

## 4. Post-Transfer Repository Update

Once the repository exists at `jenkinsci/ai-agent-plugin`:

- [ ] Update `pom.xml` `<url>` to `https://github.com/jenkinsci/ai-agent-plugin`.
- [ ] Update README badge/link URLs from `bvolpato/jenkins-ai-agent-plugin` to `jenkinsci/ai-agent-plugin`.
- [ ] Keep plugin ID as `ai-agent`.

## 5. Enable Jenkins CD Workflow

Use Jenkins official template:
`https://raw.githubusercontent.com/jenkinsci/.github/master/workflow-templates/cd.yaml`

Checklist:

- [ ] Add `.github/workflows/cd.yaml`.
- [ ] Initially prefer `workflow_dispatch` trigger only during cutover.
- [ ] Enable `check_run` trigger after first successful official release.
- [ ] Confirm repository secrets appear: `MAVEN_USERNAME`, `MAVEN_TOKEN`.

## 6. First Official Release

- [ ] Merge a PR with a release-triggering label (`bug`, `enhancement`, or `developer`) or run `cd.yaml` manually.
- [ ] Confirm GitHub Actions CD run is successful.
- [ ] Confirm release appears on `https://plugins.jenkins.io/ai-agent/`.
- [ ] Confirm update center metadata shows the released version.

## 7. Cleanup and Transition

- [x] Retire personal-repo `release.yml` flow to avoid multiple release paths.
- [ ] Add a short migration note in README that official releases now come from `jenkinsci/ai-agent-plugin`.
- [ ] Keep `main` on next `-SNAPSHOT` after each release.
