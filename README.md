# AI Agent

[![CI](https://github.com/jenkinsci/ai-agent-plugin/actions/workflows/ci.yml/badge.svg)](https://github.com/jenkinsci/ai-agent-plugin/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
[![Jenkins Plugin](https://img.shields.io/badge/Jenkins-2.528.3+-blue.svg)](https://www.jenkins.io/)

A Jenkins plugin that adds a reusable **Run AI Agent** build step for running autonomous coding
agents (Claude Code, Codex CLI, Cursor Agent, OpenCode, Antigravity CLI, Grok Build, Pi, Oh My Pi,
and Kiro CLI) in Jenkins jobs and pipelines.

Plugin ID (artifactId): `ai-agent`

## Features

- **Reusable build step** — add `Run AI Agent` to Freestyle jobs or Pipeline via `aiAgent(...)`.
- **Multiple agent support:** Claude Code, Codex CLI, Cursor Agent, OpenCode, Antigravity CLI,
  Grok Build, Pi Coding Agent, Oh My Pi (OMP), and Kiro CLI.
- **Inline conversation view** — live-streaming conversation on the build page with structured display of assistant messages, tool calls with inputs/outputs, and thinking blocks. Multiple invocations in the same build are shown as separate cards (latest expanded, older collapsible).
- **Markdown rendering** — assistant and result messages are rendered as formatted HTML.
- **Approval gates** — optionally pause builds for human review before tool execution.
- **Usage statistics** — token counts, cost, and duration extracted from agent logs and displayed per build.
- **Codex controls** — job-scoped `~/.codex/config.toml`, global Codex args, and `codex exec` args for Codex runs.
- **Standard Jenkins integrations** — SCM checkout, build triggers, credentials injection, post-build shell steps, and publishers.

## Supported Agents

| Agent | Output Format | Cost Tracking |
|-------|--------------|---------------|
| [Claude Code](https://docs.anthropic.com/en/docs/claude-code) | stream-json | Full (tokens + cost) |
| [Codex CLI](https://github.com/openai/codex) | JSON | Tokens only |
| [Cursor Agent](https://www.cursor.com/) | stream-json | Tokens only |
| [OpenCode](https://opencode.ai/docs/) | JSON | Full (tokens + cost) |
| [Antigravity CLI](https://antigravity.google/docs/cli/overview) | stream-json | Tokens only |
| [Grok Build](https://docs.x.ai/build/overview) | streaming-json / ACP | Full (tokens + cost) |
| [Pi Coding Agent](https://github.com/earendil-works/pi) | JSON event stream | Full (tokens + cost) |
| [Oh My Pi (OMP)](https://github.com/can1357/oh-my-pi) | JSON event stream | Full (tokens + cost) |
| [Kiro CLI](https://kiro.dev/docs/cli/) | stream-json | Tokens only |

## Screenshot

Build page showing a Cursor Agent conversation with tool calls, markdown-rendered responses, and usage statistics:

![Build page with AI Agent conversation](static/cursor_screenshot.png)

## Installation

### Option 1: Install from the Jenkins Plugin Center (Recommended)

1. In Jenkins, go to **Manage Jenkins > Plugins**.
2. Open the **Available plugins** tab.
3. Search for `AI Agent` (plugin ID: `ai-agent`).
4. Install the plugin and restart Jenkins if prompted.

### Option 2: Offline/Manual Installation with an `.hpi` File

1. Build the plugin (see [Building](#building)) or download a release `.hpi`.
2. Go to **Manage Jenkins > Plugins > Advanced settings**.
3. Upload the `.hpi` file under **Deploy Plugin**.
4. Restart Jenkins.

## Quick Start

1. Create or open a Jenkins job (Freestyle or Pipeline).
2. Add/configure the **Run AI Agent** build step:
   - **Agent Type** — select the coding agent to run.
   - **Prompt** — the task to send to the agent.
   - **Model** — optional model override. Codex, Claude Code, and OpenCode accept `model:effort` shorthand such as `gpt-5.6-sol:xhigh`; Antigravity CLI and Grok Build accept `low`, `medium`, or `high`, such as `gemini-3.6-flash:high` or `grok-4.5:high`.
   - **Reasoning effort** — optional effort override for supported agents (e.g., `high`, `xhigh`); takes precedence over the model suffix.
   - **YOLO mode** — skip confirmation prompts in the agent.
   - **Approvals** — require human approval for tool calls.
   - **Setup script** — shell commands to run before the agent (install tools, source dotfiles, configure runtime variables).
   - **Custom Codex config.toml** — optional, shown only for Codex runs to override settings/MCP per job.
   - **Additional Codex args** — optional, shown only for Codex runs to pass global flags like `--search` or exec flags like `--ignore-user-config`.
   - **Environment variables** — inject additional env vars (`KEY=VALUE`, one per line).
   - **Command override** — replace the default command template entirely.
   - **Extra CLI args** — append flags to the generated command.
   - **Save raw log to workspace path** — optional workspace-relative file that receives a copy of the raw agent log after the agent exits.
3. Optionally add SCM, build triggers, post-build steps, and publishers as with any Jenkins job.
4. Build the job. The conversation streams live on the build page.

### Pipeline Syntax

The step symbol is `aiAgent`, and agent handlers are referenced by their symbols such as
`claudeCode()`, `codex()`, `cursor()`, `openCode()`, `antigravity()`, `grok()`, `pi()`, `omp()`,
and `kiro()`.

Minimal invocation (uses default Claude Code handler):

```groovy
aiAgent(
  prompt: 'Summarize this repository and propose 3 cleanup PRs'
)
```

Antigravity CLI with a pinned model and reasoning effort:

```groovy
aiAgent(
  agent: antigravity(),
  prompt: 'Review this project and fix the failing tests',
  model: 'gemini-3.6-flash-high',
  reasoningEffort: 'high',
  yoloMode: true
)
```

### Migrating saved Gemini CLI jobs to Antigravity CLI

Google is [transitioning Gemini CLI users to Antigravity CLI](https://developers.googleblog.com/an-important-update-transitioning-gemini-cli-to-antigravity-cli/).
This plugin has removed the Gemini CLI handler and the `geminiCli()` Pipeline symbol. Saved
Freestyle jobs can still load their legacy handler so they can be edited, but builds now fail with
an explicit migration message. Select **Antigravity CLI** (`agy`) in each affected job. Review and
remove Gemini-specific executable paths, credentials, setup commands, and environment settings;
Antigravity uses the Jenkins node OS account's Google authentication by default. Keep unrelated
job setup intact. Pipeline jobs must replace `geminiCli()` with `antigravity()` and review their
model, executable path, setup, and authentication settings manually.

Install `agy` on each Jenkins node with the
[official Antigravity installer](https://github.com/google-antigravity/antigravity-cli#installation),
ensure its directory is on the Jenkins service account's `PATH`, and authenticate once as that OS
account. Use `agy` 1.1.7 or newer; plugin support relies on headless JSON output and usage fields
documented in the [Antigravity CLI changelog](https://github.com/google-antigravity/antigravity-cli/blob/main/CHANGELOG.md).
The default command uses headless `--print` mode with `--output-format stream-json`.
Antigravity's `--model`, `--effort`, and `--dangerously-skip-permissions` flags map to the plugin's
Model, Reasoning effort, and YOLO fields. When a model slug includes an effort suffix, keep both
values aligned (for example, `gemini-3.6-flash-high` with `high`).

OpenCode with manual tool-call approvals:

```groovy
aiAgent(
  agent: openCode(),
  prompt: 'Refactor the parser and add tests',
  requireApprovals: true,
  approvalTimeoutSeconds: 300
)
```

Grok Build with API-key authentication, model/effort selection, and manual tool-call approvals:

```groovy
aiAgent(
  agent: grok(),
  prompt: 'Review the repository and fix the failing tests',
  model: 'grok-4.5',
  reasoningEffort: 'high',
  apiCredentialsId: 'xai-api-key',
  requireApprovals: true,
  approvalTimeoutSeconds: 300
)
```

Codex with job-scoped `config.toml`:

```groovy
aiAgent(
  agent: codex(
    customConfigEnabled: true,
    customConfigToml: 'model = \"gpt-5.5\"',
    additionalGlobalArgs: '--search',
    additionalExecArgs: '--color never'
  ),
  prompt: 'Summarize this project',
  reasoningEffort: 'xhigh'
)
```

Kiro CLI with API-key authentication and trusted tools:

```groovy
aiAgent(
  agent: kiro(),
  prompt: 'Review the repository and fix the failing tests',
  reasoningEffort: 'high',
  apiCredentialsId: 'kiro-api-key',
  yoloMode: true
)
```

Kiro jobs use `kiro-cli chat --no-interactive --output-format stream-json`.
The command flags are verified against Kiro CLI 2.27.1.
YOLO adds `--trust-all-tools`, as required by this example's edits and test runs.
With YOLO off, the plugin leaves Kiro's configured tool permissions in effect.
For selected tool access in headless reviews, use Extra args such as
`--trust-tools=fs_read`. A tool request that needs an interactive approval cannot
be approved through Jenkins; Kiro jobs reject Jenkins manual approvals before launch.
See [Kiro headless mode](https://kiro.dev/docs/cli/headless/) for the trust options.

Jenkins injects the selected credential as `KIRO_API_KEY`. Kiro's
[authentication precedence](https://kiro.dev/docs/cli/authentication/) gives an active
browser session priority over an API key, so use a Jenkins node account without a
cached Kiro login when the job must use that credential.

### Pi Coding Agent

Install [`@earendil-works/pi-coding-agent`](https://github.com/earendil-works/pi/tree/main/packages/coding-agent)
on each Jenkins node that runs Pi jobs, with Node.js 22.19.0 or newer. The command
and event integration is verified against Pi 0.82.1 and uses
`pi --print --mode json --no-session` for ephemeral, non-interactive runs.

```groovy
aiAgent(
  agent: pi(),
  prompt: 'Review the repository and report potential bugs',
  model: 'openai/gpt-5.5:xhigh',
  apiCredentialsId: 'openai-api-key',
  apiEnvVarName: 'OPENAI_API_KEY'
)
```

Use a provider-qualified model or pass `--provider` through Extra args. The separate
`reasoningEffort` field overrides the `model:effort` suffix and maps to `--thinking`.
Supported levels are `off`, `minimal`, `low`, `medium`, `high`, `xhigh`, and `max`;
model support varies. Jenkins credentials require an explicit provider environment
variable, such as `OPENAI_API_KEY` or `ANTHROPIC_API_KEY`. Preconfigured node-level
authentication also works without a Jenkins credential.

With YOLO off, the command ignores project-local configuration (`--no-approve`)
and enables only `read,grep,find,ls`. YOLO enables Pi's default tools and trusts
project-local configuration (`--approve`). Extension discovery is disabled by
default in both modes; trusted job authors can explicitly load extensions with
`-e` in Extra args. Extra args can also override the tool selection.

These controls are **not an OS sandbox**. Run Pi on an isolated Jenkins node with
only the files and credentials the job needs. Pi does not provide the plugin's
bidirectional approval channel, so Jenkins manual approvals are rejected. The
plugin captures assistant text, thinking, tool inputs/outputs, tokens, and cost,
and fails the build for a final assistant error or abort even if Pi exits zero.

### Oh My Pi (OMP)

Install [`@oh-my-pi/pi-coding-agent`](https://github.com/can1357/oh-my-pi) on every Jenkins node
that runs OMP jobs. The official Bun package requires Bun 1.3.14 or newer:

```bash
bun install -g @oh-my-pi/pi-coding-agent
omp --version
```

Make sure `omp` is on the Jenkins service account's `PATH`, or set the executable path in the job.
The built-in CLI flags and JSON event parsing are verified against OMP 18.3.2. Provider
authentication still depends on a valid Jenkins credential or an OMP login on the node.

```groovy
aiAgent(
  agent: omp(),
  prompt: 'Review the repository and suggest a focused fix',
  model: 'openai/gpt-5.5',
  reasoningEffort: 'high',
  apiCredentialsId: 'openai-api-key',
  apiEnvVarName: 'OPENAI_API_KEY',
  requireApprovals: true,
  approvalTimeoutSeconds: 300
)
```

OMP accepts provider-qualified model identifiers. The **Reasoning effort** field maps to
`--thinking`; supported values include `off`, `minimal`, `low`, `medium`, `high`, `xhigh`,
`max`, and `auto`. When a Jenkins credential is selected, set **API Key env var override** to
the environment variable expected by the configured provider, as in the example. Existing OMP
authentication on the Jenkins node can be used without a Jenkins credential.

With YOLO off, the built-in command enables only OMP's `read`, `grep`, and `glob` tools
and sets `--approval-mode write`. With YOLO on, OMP's default tools are enabled and its approval
mode is set to `yolo`. Extension discovery is disabled by the built-in command in both modes.
These controls are not an OS sandbox. Use an isolated Jenkins node with only the files and
credentials the job needs. With manual approvals enabled, the plugin uses OMP's ACP command with
`--approval-mode always-ask`. Jenkins handles standard ACP permission requests and OMP's native
tool Approve/Deny form. Arbitrary extension dialogs and plan forms fail closed.

Jenkins reserves OMP's execution, output, and session flags, plus CLI help/version/export/alias
flags and `--acp-terminal-auth` in every mode. Read-only and ACP approval modes also reject
approval-mode and extension-loading flags such as `--plugin-dir`. Read-only mode rejects `--tools`,
and ACP mode rejects `--config`. YOLO allows OMP-specific tool, extension, and config options.

### Pinning a Node.js Version

Claude Code is installed via `npx` and requires Node.js on the build agent.
To lock a specific Node.js version across builds, use the [NodeJS Plugin](https://plugins.jenkins.io/nodejs/).
Configure a NodeJS installation in **Manage Jenkins > Tools**, then select it in the job's build environment
so that `node` and `npx` resolve to the pinned version.

### Grok Build CLI

Install [Grok Build](https://docs.x.ai/build/overview) on each Jenkins node that runs Grok jobs:

```bash
curl -fsSL https://x.ai/cli/install.sh | bash
grok models
```

For CI, store an xAI API key as a Jenkins Secret Text credential and select it in
**API key credential**. Grok Build receives it as `XAI_API_KEY`. A cached `grok login` session on
the node is also supported, but API-key credentials are more portable for ephemeral agents. Keys
exported by the setup script are detected after shell initialization without exposing their value.

Normal jobs use `--permission-mode auto` for guarded automation without unrestricted YOLO mode:

```bash
grok --no-auto-update -p '<prompt>' --output-format streaming-json --permission-mode auto
```

Manual approval jobs use `grok agent stdio` over ACP, authenticate with the injected API key or
cached token, and stream tool inputs and outputs into the build page.

## Configuration Reference

### Environment Variables

The plugin injects these variables into every build:

| Variable | Description |
|----------|-------------|
| `AI_AGENT_PROMPT` | The configured prompt text |
| `AI_AGENT_MODEL` | The field-derived model name, without a recognized reasoning-effort suffix |
| `AI_AGENT_REASONING_EFFORT` | The field-derived reasoning effort |

### Executable Path

Jenkins services and agents do not load interactive shell startup files, so their `PATH` often
differs from an interactive terminal. Set **Executable path** to the agent launcher on the build
node, such as `$HOME/.local/bin/codex`. The plugin keeps the generated arguments and replaces only
the executable. For Claude Code, setting a path selects the native `claude` command shape instead
of the default `npx` launcher.

### Setup Script

The **Setup script** field accepts shell commands that run before the agent process starts on
Unix agents.
Use it to prepare the build environment — install dependencies, source dotfiles, configure PATH,
or map Jenkins-bound credentials into variables that the agent needs at runtime.

```bash
# Example: add local binaries to PATH, load nvm, install a CLI tool
export PATH="$HOME/.local/bin:$PATH"
. "$HOME/.nvm/nvm.sh"
nvm use 22
npm install -g @anthropic-ai/claude-code
```

The setup script and agent command run in the **same shell session**, so any `export`ed
variables, PATH changes, or sourced dotfiles are available to the agent. Supports shebang
lines (e.g. `#!/bin/zsh`) — if no shebang is present, `/bin/sh -e` is used and the script must use
POSIX syntax such as `. file`. Add a Bash or Zsh shebang before using `source`; add `set -e` when
that interpreter should stop on the first error. If the script exits with a non-zero code the build
fails immediately without launching the agent. Shell tracing is
disabled for setup and generated agent commands so expanded values, prompts, and arguments are not
written to the build log. On Windows nodes, use **Command override** instead.

### Command Override

**Command override** runs a single shell command or shell snippet instead of the built-in
agent command. Use **Executable path** for a custom binary location while retaining generated
arguments; use **Command override** when you need full control over the launched process.

### Codex Job-Scoped config.toml

For **Codex CLI** jobs, you can enable a custom config and paste TOML content equivalent to
`~/.codex/config.toml`. At runtime, the plugin creates a temporary home directory for the build,
writes `.codex/config.toml` there, and launches Codex with that run-scoped home so settings/MCP
overrides apply only to that job run.

By default, Codex runs as:

```bash
codex --sandbox workspace-write --ask-for-approval never exec --ephemeral --json --skip-git-repo-check '<prompt>'
```

Use **Additional Codex global args** for flags that must appear before `exec`, such as
`--search`, `--profile ci`, `-c key=value`, `--enable feature`, or `--image path.png`.
Use **Additional Codex exec args** for flags after `exec`, such as `--ignore-user-config`,
`--ignore-rules`, `--add-dir path`, `--output-schema schema.json`,
or `--color never`. Keep secrets in Jenkins credentials or config, not in CLI args.

Built-in Claude Code and Codex commands disable session persistence because Jenkins runs already
retain their conversation artifacts on the build. Use **Command override** when resumable CLI
session state is required outside Jenkins.

### Reasoning Effort

The **Reasoning effort** field is passed only to agents with verified CLI support:

- Codex CLI: `-c model_reasoning_effort="<value>"`
- Claude Code: `--effort <value>`
- OpenCode: `--variant <value>`
- Oh My Pi: `--thinking <value>`
- Antigravity CLI: `--effort <value>` (`low`, `medium`, or `high`)
- Grok Build: `--reasoning-effort <value>` (`low`, `medium`, or `high`)

Cursor Agent currently ignores the field in the built-in command template.

For supported agents, the **Model** field also accepts `model:effort` shorthand. For example,
`gpt-5.6-sol:xhigh` resolves to model `gpt-5.6-sol` and reasoning effort `xhigh`. Recognized
suffixes depend on the selected agent: Codex accepts `low`, `medium`, `high`, `xhigh`, `max`,
and `ultra`; Claude Code accepts `low`, `medium`, `high`, `xhigh`, and `max`; OpenCode also
accepts `minimal`; Oh My Pi accepts `off`, `minimal`, `low`, `medium`, `high`, `xhigh`, `max`,
and `auto`; and Antigravity CLI and Grok Build accept `low`, `medium`, and `high`.
Provider-defined support still determines whether a variant is available.
Other suffixes remain part of the model identifier. Use a double colon to keep a recognized
suffix literal, such as `provider/model::high` for model `provider/model:high`. A value in
**Reasoning effort** overrides the shorthand suffix.

**Extra CLI args** can override generated model or effort options. The `AI_AGENT_*` variables
and build metadata continue to describe the fields after shorthand resolution, before those
later command-line overrides.

### Credential Injection

If the selected agent type has an associated credential ID (e.g., API key), the plugin resolves it from Jenkins credentials and injects it as an environment variable. The credential is masked in the build log and captured raw agent log.

Antigravity CLI uses the Jenkins node OS account's Google Sign-In and secure credential store by
default, so leave **API key credential** empty. For a custom enterprise auth flow, configure its
environment explicitly and set **API Key env var override** before selecting a Jenkins credential.

Prompt and command-line values are not retained in build action metadata because Pipeline and environment expansion may place credentials in either value.

### Approval Gates

Manual approvals are supported for OpenCode, Grok Build, and Oh My Pi through their bidirectional
Agent Client Protocol servers. Jenkins pauses whenever the agent requests tool permission and waits
for approval or denial from the build page. Denied or timed-out requests fail the build.

OpenCode approval jobs use `opencode acp`. Grok approval jobs use `grok agent stdio`, force the
interactive permission mode even when the node defaults to always-approve, and remove bypass flags
from extra arguments. Grok ACP authentication prefers an injected `XAI_API_KEY` and falls back to a
cached node login.
Oh My Pi approval jobs use `omp acp --approval-mode always-ask` and handle standard ACP permission
requests plus OMP's native tool Approve/Deny form. Arbitrary extension dialogs and plan forms are
unsupported and fail closed. Manual-approval jobs reset inherited OMP per-tool allow, deny, and
prompt rules and apply its `always-ask` policy. An inherited `deny` therefore becomes a Jenkins
approval decision for calls gated by OMP, while provider settings remain in effect.

Claude Code, Codex CLI, Cursor Agent, Antigravity CLI, Pi Coding Agent, and Kiro CLI do
not expose a supported bidirectional approval channel to this plugin. Jobs reject those combinations
before launching instead of showing an approval that cannot affect tool execution. Command overrides
also reject manual approval because they bypass the built-in protocol handler.

### Usage Statistics

After a build completes, a statistics bar shows token usage, cost (when available), and duration.
Data is extracted from the agent's own reporting in the JSONL log. The level of detail depends on
the agent: Claude Code, OpenCode, Grok Build, Pi Coding Agent, and Oh My Pi report full cost, while
others report token counts.

### Saving the Raw Log to the Workspace

The raw agent log is always stored with the build on the controller. To collect agent runs in an
external system, set **Save raw log to workspace path** (`rawLogPath`) to a file path relative to
the workspace root. After the agent process exits, the plugin copies the raw log to that path on the
build node, where later steps can archive or upload it.

```groovy
aiAgent(
  agent: claudeCode(),
  prompt: 'Review the merge request diff in ci/context.md',
  rawLogPath: "ai-logs/${env.BUILD_NUMBER}-review.jsonl"
)
archiveArtifacts artifacts: 'ai-logs/*.jsonl'
```

- The file has the same lines as the build-page raw log: agent stdout and stderr, one entry per
  line. The injected API key credential is masked; other secrets the agent prints are not.
- The path is resolved against the workspace root, not **Working directory**. Environment variables
  are expanded, and `/` and `\` both separate directories. An existing file at the path is replaced,
  so give each invocation its own path.
- Any existing file at the path is removed before the agent starts, and the copy is made after the
  agent exits, including after a non-zero exit, a timeout, or an abort. The agent cannot read a
  previous export at that path, or read or change the new one while it runs. Exports at other paths
  stay in a reused workspace and are visible to later agents; clean them up after archiving.
- If the copy fails, the build fails, even when the agent exited with a non-zero code. If the step
  already failed with an error, such as an abort or a launch failure, the copy failure is logged and
  attached to that error instead.
- Absolute paths and `..` segments are rejected before the agent starts. A parent directory that
  resolves outside the workspace through a symlink is rejected. A symlink, FIFO, or file at the
  target path is removed and the log is written to a newly created file, so the target is never
  followed.

## Building

Requires Java 17+ and Maven 3.9+.

```bash
mvn clean verify
```

The plugin artifact is generated at `target/ai-agent.hpi`.

To package without running tests:

```bash
mvn clean package -DskipTests
```

## Official Jenkins Distribution Plan

The step-by-step migration plan to move this plugin into the `jenkinsci` GitHub organization and
publish through official Jenkins CD is tracked in:
`docs/jenkins-official-publishing-checklist.md`

## Development

```bash
# Format code (Google Java Format, AOSP style)
mvn com.spotify.fmt:fmt-maven-plugin:format

# Run with a local Jenkins instance
mvn hpi:run
```

The project uses:
- [Google Java Format](https://github.com/google/google-java-format) (AOSP variant) via `fmt-maven-plugin`
- [JaCoCo](https://www.jacoco.org/) for test coverage
- [SpotBugs](https://spotbugs.github.io/) for static analysis
- [Jenkins Test Harness](https://github.com/jenkinsci/jenkins-test-harness) for integration tests

See [CONTRIBUTING.md](CONTRIBUTING.md) for full contribution guidelines.

## Architecture

```
src/main/java/io/jenkins/plugins/aiagentjob/
├── AiAgentBuilder.java             # SimpleBuildStep: configuration UI and build execution
├── AiAgentConfiguration.java       # Shared execution settings contract (interface)
├── AiAgentTypeHandler.java         # Describable extension point for agent implementations
├── AiAgentRunAction.java           # Per-build action: conversation UI, streaming, approvals
├── AiAgentLogParser.java           # JSONL log parser for all agent formats
├── AiAgentLogFormat.java           # Format-specific classification interface
├── AgentUsageStats.java            # Token/cost/duration stats normalization
├── AiAgentStatsExtractor.java      # Per-agent usage-stats extraction interface
├── AiAgentCommandFactory.java      # Command-line construction per selected handler
├── AiAgentExecutor.java            # Subprocess lifecycle, env wiring, approval gates
├── AiAgentExecutionCustomization.java # Agent-specific env vars and cleanup hooks
├── AiAgentTempFiles.java           # Temp directory management for build workspaces
├── ExecutionRegistry.java          # In-memory registry for live execution state
├── AcpLogFormat.java               # Shared Agent Client Protocol event parser
├── LogFormatUtils.java             # Shared JSON field extraction helpers
├── antigravity/                    # Antigravity CLI implementation
├── claudecode/                     # Claude Code agent implementation
├── codex/                          # Codex CLI implementation (+ optional config.toml)
├── cursor/                         # Cursor Agent implementation
├── geminicli/                      # Legacy Gemini CLI deserialization shim
├── grokbuild/                      # Grok Build implementation
├── kiro/                           # Kiro CLI implementation
└── opencode/                       # OpenCode implementation
```

### Adding a New Agent

Each agent lives in its own sub-package with up to three files. Use the `cursor/` package as a
minimal reference:

1. **Handler** (`ExampleAgentHandler extends AiAgentTypeHandler`) — annotate with `@Extension`
   and `@Symbol("example")`. Implement `getId()`, `getDefaultApiKeyEnvVar()` (return an empty value
   for node-level authentication), `buildDefaultCommand()`, `getLogFormat()`, and
   `getStatsExtractor()`.
2. **Log format** (`ExampleLogFormat implements AiAgentLogFormat`) — classify agent-specific
   JSONL events into `ParsedLine` types. Return `null` for unrecognised lines so the shared
   parser handles them. If the agent emits stream-json compatible with Claude Code, reuse
   `ClaudeCodeLogFormat.INSTANCE` for compatible stream-json formats.
3. **Stats extractor** (`ExampleStatsExtractor implements AiAgentStatsExtractor`) — extract
   token/cost data from JSONL. Return `true` if handled, `false` for fallback.
4. **Test fixtures** — add `.jsonl` conversation and stats fixtures under
   `src/test/resources/.../fixtures/`, with tests in `AiAgentRecordedConversationTest` and
   `AgentUsageStatsTest`.

Optional: override `prepareExecution()` in the handler for custom env vars or cleanup hooks
(see `CodexAgentHandler`), and add a `config.jelly` + help HTML files for agent-specific UI
fields.

## License

MIT License. See [LICENSE](LICENSE) for details.
