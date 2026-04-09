# AI Agent

[![CI](https://github.com/jenkinsci/ai-agent-plugin/actions/workflows/ci.yml/badge.svg)](https://github.com/jenkinsci/ai-agent-plugin/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
[![Jenkins Plugin](https://img.shields.io/badge/Jenkins-2.528.3+-blue.svg)](https://www.jenkins.io/)

A Jenkins plugin that adds a reusable **Run AI Agent** build step for running autonomous coding
agents (Claude Code, Codex CLI, Cursor Agent, OpenCode, Gemini CLI) in Jenkins jobs and pipelines.

Plugin ID (artifactId): `ai-agent`

## Features

- **Reusable build step** — add `Run AI Agent` to Freestyle jobs or Pipeline via `aiAgent(...)`.
- **Multiple agent support** — Claude Code, Codex CLI, Cursor Agent, OpenCode, and Gemini CLI.
- **Inline conversation view** — live-streaming conversation on the build page with structured display of assistant messages, tool calls with inputs/outputs, and thinking blocks. Multiple invocations in the same build are shown as separate cards (latest expanded, older collapsible).
- **Markdown rendering** — assistant and result messages are rendered as formatted HTML.
- **Approval gates** — optionally pause builds for human review before tool execution.
- **Usage statistics** — token counts, cost, and duration extracted from agent logs and displayed per build.
- **Codex per-job config** — optionally provide a job-scoped `~/.codex/config.toml` to override settings/MCP for Codex runs.
- **Standard Jenkins integrations** — SCM checkout, build triggers, credentials injection, post-build shell steps, and publishers.

## Supported Agents

| Agent | Output Format | Cost Tracking |
|-------|--------------|---------------|
| [Claude Code](https://docs.anthropic.com/en/docs/claude-code) | stream-json | Full (tokens + cost) |
| [Codex CLI](https://github.com/openai/codex) | JSON | Tokens only |
| [Cursor Agent](https://www.cursor.com/) | stream-json | Tokens only |
| [OpenCode](https://github.com/opencode-ai/opencode) | JSON | Full (tokens + cost) |
| [Gemini CLI](https://github.com/google-gemini/gemini-cli) | stream-json | Tokens only |

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
   - **Model** — optional model override (e.g., `claude-sonnet-4`).
   - **YOLO mode** — skip confirmation prompts in the agent.
   - **Approvals** — require human approval for tool calls.
   - **Setup script** — shell commands to run before the agent (install tools, source dotfiles, export secrets).
   - **Custom Codex config.toml** — optional, shown only for Codex runs to override settings/MCP per job.
   - **Environment variables** — inject additional env vars (`KEY=VALUE`, one per line).
   - **Command override** — replace the default command template entirely.
   - **Extra CLI args** — append flags to the generated command.
3. Optionally add SCM, build triggers, post-build steps, and publishers as with any Jenkins job.
4. Build the job. The conversation streams live on the build page.

### Pipeline Syntax

The step symbol is `aiAgent`, and agent handlers are referenced by their symbols such as
`claudeCode()`, `codex()`, `geminiCli()`, `cursor()`, and `openCode()`.

Minimal invocation (uses default Claude Code handler):

```groovy
aiAgent(
  prompt: 'Summarize this repository and propose 3 cleanup PRs'
)
```

Gemini with manual tool-call approvals:

```groovy
aiAgent(
  agent: geminiCli(),
  prompt: 'Refactor the parser and add tests',
  requireApprovals: true,
  approvalTimeoutSeconds: 300
)
```

Codex with job-scoped `config.toml`:

```groovy
aiAgent(
  agent: codex(
    customConfigEnabled: true,
    customConfigToml: '[model]\\nname = \"gpt-5\"'
  ),
  prompt: 'Summarize this project',
  approvalTimeoutSeconds: 60
)
```

### Pinning a Node.js Version

Some agents (Claude Code, Gemini CLI) are installed via `npx` and require Node.js on the build agent.
To lock a specific Node.js version across builds, use the [NodeJS Plugin](https://plugins.jenkins.io/nodejs/).
Configure a NodeJS installation in **Manage Jenkins > Tools**, then select it in the job's build environment
so that `node` and `npx` resolve to the pinned version.

## Configuration Reference

### Environment Variables

The plugin injects these variables into every build:

| Variable | Description |
|----------|-------------|
| `AI_AGENT_PROMPT` | The configured prompt text |
| `AI_AGENT_MODEL` | The configured model name |

### Setup Script

The **Setup script** field accepts shell commands that run before the agent process starts on
Unix agents.
Use it to prepare the build environment — install dependencies, source dotfiles, configure PATH,
or export secrets that the agent needs at runtime.

```bash
# Example: add local binaries to PATH, source nvm, install a CLI tool
export PATH="$HOME/.local/bin:$PATH"
source "$HOME/.nvm/nvm.sh"
nvm use 22
npm install -g @anthropic-ai/claude-code
```

The setup script and agent command run in the **same shell session**, so any `export`ed
variables, PATH changes, or sourced dotfiles are available to the agent. Supports shebang
lines (e.g. `#!/bin/zsh`) just like the Jenkins Shell build step — if no shebang is present,
`/bin/sh -xe` is used. If the script exits with a non-zero code the build fails immediately
without launching the agent. On Windows nodes, use **Command override** instead.

### Command Override

**Command override** runs a single shell command or shell snippet instead of the built-in
agent command. Use this when you need full control over the launched process or want to invoke
the agent from a custom path.

### Codex Job-Scoped config.toml

For **Codex CLI** jobs, you can enable a custom config and paste TOML content equivalent to
`~/.codex/config.toml`. At runtime, the plugin creates a temporary home directory for the build,
writes `.codex/config.toml` there, and launches Codex with that run-scoped home so settings/MCP
overrides apply only to that job run.

### Credential Injection

If the selected agent type has an associated credential ID (e.g., API key), the plugin resolves it from Jenkins credentials and injects it as an environment variable. The credential is masked in the build log.

### Approval Gates

When approvals are enabled and YOLO mode is off, tool calls detected in the agent's output trigger a blocking approval request. The build pauses until a user approves or denies from the build page. Denied or timed-out requests fail the build.

### Usage Statistics

After a build completes, a statistics bar shows token usage, cost (when available), and duration. Data is extracted from the agent's own reporting in the JSONL log. The level of detail depends on the agent — Claude Code and OpenCode report full cost, while others report only token counts.

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
├── LogFormatUtils.java             # Shared JSON field extraction helpers
├── claudecode/                     # Claude Code agent implementation
├── codex/                          # Codex CLI implementation (+ optional config.toml)
├── cursor/                         # Cursor Agent implementation
├── geminicli/                      # Gemini CLI implementation
└── opencode/                       # OpenCode implementation
```

### Adding a New Agent

Each agent lives in its own sub-package with up to three files. Use the `cursor/` package as a
minimal reference:

1. **Handler** (`ExampleAgentHandler extends AiAgentTypeHandler`) — annotate with `@Extension`
   and `@Symbol("example")`. Implement `getId()`, `getDefaultApiKeyEnvVar()`,
   `buildDefaultCommand()`, `getLogFormat()`, and `getStatsExtractor()`.
2. **Log format** (`ExampleLogFormat implements AiAgentLogFormat`) — classify agent-specific
   JSONL events into `ParsedLine` types. Return `null` for unrecognised lines so the shared
   parser handles them. If the agent emits stream-json compatible with Claude Code, reuse
   `ClaudeCodeLogFormat.INSTANCE` (see `GeminiCliAgentHandler`).
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
