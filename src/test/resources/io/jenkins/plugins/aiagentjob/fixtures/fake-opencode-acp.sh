#!/bin/sh
set -eu

test "${1:-}" = "acp"
printf '%s\n' "$*" > acp-command.txt

IFS= read -r initialize_request
initialize_id=$(printf '%s\n' "$initialize_request" | sed -n 's/.*"id":\([^,}]*\).*/\1/p')
printf '{"jsonrpc":"2.0","id":%s,"result":{"protocolVersion":1,"agentCapabilities":{},"authMethods":[]}}\n' "$initialize_id"

IFS= read -r session_request
session_id=$(printf '%s\n' "$session_request" | sed -n 's/.*"id":\([^,}]*\).*/\1/p')
config_options='[{"id":"preferred-model","name":"Model","category":"model","type":"select","currentValue":"test/provider:high","options":[{"value":"test/provider:high","name":"Test model"}]},{"id":"thinking-depth","name":"Reasoning effort","category":"thought_level","type":"select","currentValue":"high","options":[{"value":"high","name":"High"}]}]'
printf '{"jsonrpc":"2.0","id":%s,"result":{"sessionId":"session-1","configOptions":%s}}\n' "$session_id" "$config_options"

while IFS= read -r request; do
  case "$request" in
    *'"method":"session/set_config_option"'*)
      printf '%s\n' "$request" >> config-requests.jsonl
      request_id=$(printf '%s\n' "$request" | sed -n 's/.*"id":\([^,}]*\).*/\1/p')
      printf '{"jsonrpc":"2.0","id":%s,"result":{"configOptions":%s}}\n' "$request_id" "$config_options"
      ;;
    *'"method":"session/prompt"'*)
      prompt_id=$(printf '%s\n' "$request" | sed -n 's/.*"id":\([^,}]*\).*/\1/p')
      break
      ;;
    *) exit 8 ;;
  esac
done
printf '%s\n' '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"session-1","update":{"sessionUpdate":"available_commands_update","availableCommands":[{"name":"private-test-command","description":"control metadata"}]}}}'
printf '%s\n' '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"session-1","update":{"sessionUpdate":"tool_call","toolCallId":"call-1","title":"touch approved.txt","kind":"execute","status":"pending","rawInput":{"command":"touch approved.txt"}}}}'
approval_input=${FAKE_ACP_SECRET_INPUT:-touch approved.txt}
printf '%s\n' "{\"jsonrpc\":\"2.0\",\"id\":\"permission-1\",\"method\":\"session/request_permission\",\"params\":{\"sessionId\":\"session-1\",\"toolCall\":{\"toolCallId\":\"call-1\",\"title\":\"touch approved.txt\",\"kind\":\"execute\",\"status\":\"pending\",\"rawInput\":{\"command\":\"$approval_input\"}},\"options\":[{\"optionId\":\"once\",\"name\":\"Allow once\",\"kind\":\"allow_once\"},{\"optionId\":\"reject\",\"name\":\"Reject\",\"kind\":\"reject_once\"}]}}"

if test "${FAKE_ACP_EXIT_AFTER_PERMISSION:-}" = "1"; then
  exit 23
fi

IFS= read -r approval_response
printf '%s\n' "$approval_response" > approval-response.json
case "$approval_response" in
  *'"outcome":"selected"'*'"optionId":"once"'*) ;;
  *) exit 9 ;;
esac

printf '%s\n' '{"jsonrpc":"2.0","id":"fs-1","method":"fs/write_text_file","params":{"sessionId":"session-1","path":"approved.txt","content":"private-test-content"}}'
IFS= read -r filesystem_response
case "$filesystem_response" in
  *'"id":"fs-1"'*'"code":-32601'*) ;;
  *) exit 10 ;;
esac

touch approved.txt
printf '%s\n' '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"session-1","update":{"sessionUpdate":"tool_call_update","toolCallId":"call-1","status":"completed","content":[{"type":"content","content":{"type":"text","text":"created approved.txt"}}],"rawOutput":{"output":"created approved.txt"}}}}'
printf '%s\n' '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"session-1","update":{"sessionUpdate":"usage_update","used":42,"size":1000,"cost":{"amount":0.01,"currency":"USD"}}}}'
printf '%s\n' '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"session-1","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"Created approved.txt"}}}}'
printf '{"jsonrpc":"2.0","id":%s,"result":{"stopReason":"end_turn"}}\n' "$prompt_id"
