# AI Agent Job

[![CI](https://github.com/bvolpato/jenkins-ai-agent-plugin/actions/workflows/ci.yml/badge.svg)](https://github.com/bvolpato/jenkins-ai-agent-plugin/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
[![Jenkins Plugin](https://img.shields.io/badge/Jenkins-2.528.3+-blue.svg)](https://www.jenkins.io/)

A Jenkins plugin that adds a reusable **Run AI Agent** build step for running autonomous coding
agents (Claude Code, Codex CLI, Cursor Agent, OpenCode, Gemini CLI) in Jenkins jobs and pipelines.

Plugin ID (artifactId): `ai-agent-job`

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

The step symbol is `aiAgent`.

Descriptor-based syntax (extensible, supports third-party agent plugins):

```groovy
aiAgent(
  agent: [$class: 'CodexAgentHandler', customConfigEnabled: true, customConfigToml: '[model]\\nname = \"gpt-5\"'],
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
| `AI_AGENT_JOB` | The Jenkins job name |
| `AI_AGENT_BUILD_NUMBER` | The build number |

### Setup Script

The **Setup script** field accepts shell commands that run before the agent process starts.
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
without launching the agent.

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

The plugin artifact is generated at `target/ai-agent-job.hpi`.

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
├── AiAgentBuilder.java             # Build step and shared execution settings
├── AiAgentConfiguration.java       # Shared execution settings contract
├── AiAgentTypeHandler.java         # Describable extension point for agent implementations
├── ClaudeCodeAgentHandler.java     # Claude Code agent implementation
├── CodexAgentHandler.java          # Codex implementation (+ optional config.toml settings)
├── CursorAgentHandler.java         # Cursor Agent implementation
├── OpenCodeAgentHandler.java       # OpenCode implementation (+ permission env behavior)
├── GeminiCliAgentHandler.java      # Gemini CLI implementation
├── AiAgentRunAction.java           # Per-build action: conversation UI, streaming, approvals
├── AiAgentLogParser.java           # JSONL log parser for all agent formats
├── AgentUsageStats.java            # Token/cost/duration stats normalization
├── AiAgentCommandFactory.java      # Command-line construction per selected handler
├── AiAgentExecutionCustomization.java # Agent-specific env vars and cleanup hooks
├── ExecutionRegistry.java          # In-memory registry for live execution state
└── package-info.java               # Package-level API documentation
```

## License

MIT License. See [LICENSE](LICENSE) for details.
