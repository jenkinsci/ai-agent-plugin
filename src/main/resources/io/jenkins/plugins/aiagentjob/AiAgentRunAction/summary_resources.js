(function () {
  function esc(text) {
    var div = document.createElement('div');
    div.appendChild(document.createTextNode(text || ''));
    return div.innerHTML;
  }

  function inlineMd(text) {
    var html = esc(text);
    html = html.replace(/`([^`]+)`/g, '<code>$1</code>');
    html = html.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
    html = html.replace(/__([^_]+)__/g, '<strong>$1</strong>');
    html = html.replace(/\*([^*]+)\*/g, '<em>$1</em>');
    html = html.replace(/_([^_]+)_/g, '<em>$1</em>');
    return html;
  }

  function mdToHtml(text) {
    if (!text) {
      return '';
    }
    var lines = text.split('\n');
    var out = '';
    var inCode = false;
    var inList = false;

    for (var i = 0; i < lines.length; i++) {
      var line = lines[i];
      if (line.indexOf('```') === 0) {
        if (inList) {
          out += '</ul>';
          inList = false;
        }
        if (inCode) {
          out += '</code></pre>';
          inCode = false;
        } else {
          out += '<pre><code>';
          inCode = true;
        }
        continue;
      }
      if (inCode) {
        out += esc(line) + '\n';
        continue;
      }
      var trimmed = line.trim();
      if (trimmed.indexOf('- ') === 0 || trimmed.indexOf('* ') === 0) {
        if (!inList) {
          out += '<ul>';
          inList = true;
        }
        out += '<li>' + inlineMd(trimmed.substring(2)) + '</li>';
        continue;
      }
      if (inList) {
        out += '</ul>';
        inList = false;
      }
      if (trimmed.indexOf('### ') === 0) {
        out += '<strong>' + inlineMd(trimmed.substring(4)) + '</strong><br/>';
      } else if (trimmed.indexOf('## ') === 0) {
        out += '<strong>' + inlineMd(trimmed.substring(3)) + '</strong><br/>';
      } else if (trimmed.indexOf('# ') === 0) {
        out += '<strong>' + inlineMd(trimmed.substring(2)) + '</strong><br/>';
      } else if (trimmed === '---') {
        out += '<hr/>';
      } else if (trimmed === '') {
        out += '<br/>';
      } else {
        out += inlineMd(line) + '<br/>';
      }
    }

    if (inCode) {
      out += '</code></pre>';
    }
    if (inList) {
      out += '</ul>';
    }
    while (out.length >= 5 && out.substring(out.length - 5) === '<br/>') {
      out = out.substring(0, out.length - 5);
    }
    return out;
  }

  function renderMarkdownNodes(root) {
    var elems = root.querySelectorAll('.ai-md-src');
    for (var i = 0; i < elems.length; i++) {
      elems[i].innerHTML = mdToHtml(elems[i].getAttribute('data-md') || '');
    }
  }

  function excerpt(text, len) {
    if (!text) {
      return '';
    }
    if (text.length <= len) {
      return esc(text);
    }
    return esc(text.substring(0, len)) + '...';
  }

  function renderEvent(ev) {
    var cat = ev.category;
    var html = '<div class="ai-ev">';

    if (cat === 'assistant' || cat === 'user' || cat === 'result' || cat === 'error') {
      html += '<span class="ai-badge ai-badge-' + cat + '">' + esc(ev.label) + '</span>';
      html += '<div class="ai-msg-content">' + mdToHtml(ev.content) + '</div>';
    } else if (cat === 'tool_call') {
      html += '<details>';
      html += '<summary class="ai-tool-header ai-details-summary">';
      html += '<span class="ai-badge ai-badge-tool_call">TOOL</span>';
      html += '<span class="ai-tool-label">' + esc(ev.label) + '</span>';
      if (ev.toolInput) {
        html += '<span class="ai-tool-input-preview">' + excerpt(ev.toolInput, 120) + '</span>';
      }
      html += '</summary>';
      html += '<div class="ai-tool-body">';
      if (ev.toolInput) {
        html += '<div class="ai-tool-section-label">Input</div>';
        html += '<div class="ai-tool-section-content">' + esc(ev.toolInput) + '</div>';
      }
      html += '</div></details>';
    } else if (cat === 'tool_result') {
      html += '<details>';
      html += '<summary class="ai-tool-header ai-details-summary">';
      html += '<span class="ai-badge ai-badge-tool_result">OUTPUT</span>';
      html += '<span class="ai-tool-label">' + esc(ev.label) + '</span>';
      if (ev.toolOutput) {
        html += '<span class="ai-tool-input-preview ai-tool-output-preview">' + excerpt(ev.toolOutput, 80) + '</span>';
      }
      html += '</summary>';
      html += '<div class="ai-tool-body">';
      if (ev.toolOutput) {
        html += '<div class="ai-tool-section-label">Output</div>';
        html += '<div class="ai-tool-section-content">' + esc(ev.toolOutput) + '</div>';
      }
      html += '</div></details>';
    } else if (cat === 'thinking') {
      html += '<details>';
      html += '<summary class="ai-details-summary">';
      html += '<span class="ai-badge ai-badge-thinking">Thinking</span>';
      html += '</summary>';
      html += '<div class="ai-thinking-text">' + esc(ev.content) + '</div>';
      html += '</details>';
    } else {
      html += '<details>';
      html += '<summary class="ai-details-summary">';
      html += '<span class="ai-badge ai-badge-' + cat + '">' + esc(ev.label) + '</span>';
      html += '<span class="ai-system-text">' + excerpt(ev.content, 100) + '</span>';
      html += '</summary>';
      html += '<div class="ai-system-text ai-system-detail">' + esc(ev.content) + '</div>';
      html += '</details>';
    }

    html += '</div>';
    return html;
  }

  function renderApprovals(container, approveUrl, denyUrl, approvals) {
    if (!container) {
      return;
    }
    if (!approvals || approvals.length === 0) {
      container.innerHTML = '';
      return;
    }
    var html = '';
    for (var i = 0; i < approvals.length; i++) {
      var approval = approvals[i];
      html += '<div class="ai-approval-card">';
      html += '<strong>Approval required:</strong> ' + esc(approval.toolName);
      html += ' <span class="ai-approval-summary">- ' + esc(approval.inputSummary) + '</span>';
      html += '<div class="actions">';
      html += '<form method="post" action="' + esc(approveUrl) + '" class="ai-inline-form">';
      html += '<input type="hidden" name="id" value="' + esc(approval.id) + '" />';
      html += '<button type="submit">Approve</button></form>';
      html += '<form method="post" action="' + esc(denyUrl) + '" class="ai-inline-form">';
      html += '<input type="hidden" name="id" value="' + esc(approval.id) + '" />';
      html += '<input type="text" name="reason" placeholder="reason (optional)" class="ai-approval-reason" />';
      html += '<button type="submit">Deny</button></form>';
      html += '</div></div>';
    }
    container.innerHTML = html;
  }

  function renderStats(container, stats) {
    if (!container || !stats) {
      return;
    }
    var html = '<div class="ai-stats">';
    if (stats.costDisplay) {
      html += '<div class="ai-stats-item"><span class="ai-stats-label">Cost:</span><span class="ai-stats-value ai-stats-cost">' + esc(stats.costDisplay) + '</span></div>';
    }
    if (stats.durationDisplay) {
      html += '<div class="ai-stats-item"><span class="ai-stats-label">Duration:</span><span class="ai-stats-value ai-stats-duration">' + esc(stats.durationDisplay) + '</span></div>';
    }
    if (stats.inputTokens > 0) {
      html += '<div class="ai-stats-item"><span class="ai-stats-label">Input:</span><span class="ai-stats-value">' + stats.inputTokens.toLocaleString() + ' tokens</span></div>';
    }
    if (stats.outputTokens > 0) {
      html += '<div class="ai-stats-item"><span class="ai-stats-label">Output:</span><span class="ai-stats-value">' + stats.outputTokens.toLocaleString() + ' tokens</span></div>';
    }
    if (stats.cacheReadTokens > 0) {
      html += '<div class="ai-stats-item"><span class="ai-stats-label">Cache Read:</span><span class="ai-stats-value">' + stats.cacheReadTokens.toLocaleString() + ' tokens</span></div>';
    }
    if (stats.cacheWriteTokens > 0) {
      html += '<div class="ai-stats-item"><span class="ai-stats-label">Cache Write:</span><span class="ai-stats-value">' + stats.cacheWriteTokens.toLocaleString() + ' tokens</span></div>';
    }
    if (stats.reasoningTokens > 0) {
      html += '<div class="ai-stats-item"><span class="ai-stats-label">Reasoning:</span><span class="ai-stats-value">' + stats.reasoningTokens.toLocaleString() + ' tokens</span></div>';
    }
    if (stats.numTurns > 0) {
      html += '<div class="ai-stats-item"><span class="ai-stats-label">Turns:</span><span class="ai-stats-value">' + stats.numTurns + '</span></div>';
    }
    if (stats.toolCalls > 0) {
      html += '<div class="ai-stats-item"><span class="ai-stats-label">Tool Calls:</span><span class="ai-stats-value">' + stats.toolCalls + '</span></div>';
    }
    html += '</div>';
    container.innerHTML = html;
  }

  function updateExitBadge(container, exitCode) {
    if (!container) {
      return;
    }
    container.innerHTML = '';
    if (exitCode === null || exitCode === undefined) {
      return;
    }
    var badge = document.createElement('span');
    badge.className = 'ai-meta-badge ' + (exitCode === 0 ? 'ai-meta-badge-exit-success' : 'ai-meta-badge-exit-failure');
    badge.textContent = 'Exit: ' + exitCode;
    container.appendChild(badge);
  }

  function initLiveView(root) {
    var progressiveEventsUrl = root.dataset.progressiveEventsUrl;
    var approveUrl = root.dataset.approveUrl;
    var denyUrl = root.dataset.denyUrl;
    var container = root.querySelector('#ai-agent-events-container');
    var emptyMsg = root.querySelector('#ai-agent-empty');
    var liveBanner = root.querySelector('#ai-agent-live-banner');
    var approvalsContainer = root.querySelector('#ai-agent-approvals-container');
    var exitBadge = root.querySelector('#ai-agent-exit-badge');
    var statsContainer = root.querySelector('#ai-agent-stats-container');
    var nextStart = 0;
    var isLive = true;
    var eventCount = 0;
    var pollInterval = 2000;

    function schedulePoll() {
      if (isLive) {
        window.setTimeout(poll, pollInterval);
      } else if (liveBanner) {
        liveBanner.hidden = true;
      }
    }

    function poll() {
      var xhr = new XMLHttpRequest();
      xhr.open('GET', progressiveEventsUrl + '?start=' + nextStart, true);
      xhr.onreadystatechange = function () {
        if (xhr.readyState !== 4) {
          return;
        }
        if (xhr.status !== 200) {
          schedulePoll();
          return;
        }
        try {
          var data = JSON.parse(xhr.responseText);
          var events = data.events || [];
          var shouldScroll = container.scrollHeight - container.scrollTop - container.clientHeight < 50;
          if (events.length > 0) {
            var html = '';
            for (var i = 0; i < events.length; i++) {
              html += renderEvent(events[i]);
              eventCount++;
            }
            container.insertAdjacentHTML('beforeend', html);
            if (shouldScroll) {
              container.scrollTop = container.scrollHeight;
            }
          }
          nextStart = data.nextStart || nextStart;
          isLive = data.live;
          if (emptyMsg) {
            emptyMsg.hidden = eventCount > 0;
            if (!isLive && eventCount === 0) {
              emptyMsg.textContent = 'No conversation events captured.';
            }
          }
          if (liveBanner) {
            liveBanner.hidden = !isLive;
          }
          renderApprovals(approvalsContainer, approveUrl, denyUrl, data.pendingApprovals);
          updateExitBadge(exitBadge, data.exitCode);
          if (data.usageStats) {
            renderStats(statsContainer, data.usageStats);
          }
        } catch (ignored) {
        }
        schedulePoll();
      };
      xhr.send();
    }

    poll();
  }

  function init() {
    var root = document.getElementById('ai-agent-root');
    if (!root) {
      return;
    }
    renderMarkdownNodes(root);
    if (root.dataset.live === 'true') {
      initLiveView(root);
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
