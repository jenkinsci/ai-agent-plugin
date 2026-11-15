const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const { parseHTML } = require('linkedom');

const { document, window } = parseHTML(
  '<!doctype html><html><body><div data-ai-agent-root="true"><div class="ai-md-src" data-md="not rendered"></div></div></body></html>',
  { location: { href: 'https://jenkins.example/job/test/1/' } }
);

global.document = document;
global.window = window;

const {
  appendDelta,
  buildApprovalUrl,
  isSafeUrl,
  renderEvent,
  sanitizeHtml
} = require('../../main/resources/io/jenkins/plugins/aiagentjob/AiAgentRunAction/summary_resources.js');

test('CommonJS export does not initialize page rendering', function () {
  assert.equal(document.querySelector('.ai-md-src').innerHTML, '');
});

test('approval URL preserves invocation and encodes approval parameters', function () {
  const result = new URL(buildApprovalUrl(
    'https://jenkins.example/job/test/1/ai-agent/deny?invocation=7',
    'approval id',
    'needs review & retry'
  ));

  assert.equal(result.searchParams.get('invocation'), '7');
  assert.equal(result.searchParams.get('id'), 'approval id');
  assert.equal(result.searchParams.get('reason'), 'needs review & retry');
});

test('rendered links accept only safe protocols', function () {
  const baseUrl = 'https://jenkins.example/job/test/1/';

  assert.equal(isSafeUrl('/artifact/report.html', true, baseUrl), true);
  assert.equal(isSafeUrl('https://example.com/report', true, baseUrl), true);
  assert.equal(isSafeUrl('mailto:owner@example.com', true, baseUrl), true);
  assert.equal(isSafeUrl('javascript:alert(1)', true, baseUrl), false);
  assert.equal(isSafeUrl('data:text/html,unsafe', true, baseUrl), false);
});

test('rendered images reject mailto and non-web protocols', function () {
  const baseUrl = 'https://jenkins.example/job/test/1/';

  assert.equal(isSafeUrl('https://example.com/image.png', false, baseUrl), true);
  assert.equal(isSafeUrl('mailto:owner@example.com', false, baseUrl), false);
  assert.equal(isSafeUrl('file:///etc/passwd', false, baseUrl), false);
});

test('sanitizer removes unsafe markup and preserves permitted content', function () {
  const output = document.createElement('div');
  output.innerHTML = sanitizeHtml(
    '<p onclick="alert(1)">Safe <a href="javascript:alert(1)" title="blocked">link</a>'
      + '<a href="https://example.com" onclick="alert(1)">allowed</a>'
      + '<img src="data:image/svg+xml,unsafe" alt="unsafe" onerror="alert(1)">'
      + '<script>alert(1)</script></p>'
  );

  const links = output.querySelectorAll('a');
  assert.equal(output.querySelector('p').hasAttribute('onclick'), false);
  assert.equal(links[0].hasAttribute('href'), false);
  assert.equal(links[0].getAttribute('title'), 'blocked');
  assert.equal(links[1].getAttribute('href'), 'https://example.com');
  assert.equal(links[1].hasAttribute('onclick'), false);
  assert.equal(output.querySelector('img').hasAttribute('src'), false);
  assert.equal(output.querySelector('img').getAttribute('alt'), 'unsafe');
  assert.equal(output.querySelector('script'), null);
  assert.match(output.textContent, /Safe linkallowedalert\(1\)/);
});

test('assistant delta rerenders accumulated markdown in latest assistant event', function () {
  const container = document.createElement('div');
  container.insertAdjacentHTML('beforeend', renderEvent({
    category: 'assistant',
    label: 'Assistant',
    content: 'Hello'
  }));

  assert.equal(appendDelta(container, {
    category: 'assistant',
    delta: true,
    content: ' **world**'
  }), true);

  const assistant = container.querySelector('.ai-msg-content-assistant');
  assert.equal(assistant.getAttribute('data-md'), 'Hello **world**');
  assert.equal(assistant.textContent, 'Hello world');
  assert.match(assistant.innerHTML, /<strong>world<\/strong>/);
});

test('thinking delta appends text only to latest thinking event', function () {
  const container = document.createElement('div');
  container.insertAdjacentHTML('beforeend', renderEvent({
    category: 'assistant',
    label: 'Assistant',
    content: 'Answer'
  }));
  container.insertAdjacentHTML('beforeend', renderEvent({
    category: 'thinking',
    label: 'Thinking',
    content: 'First'
  }));

  assert.equal(appendDelta(container, {
    category: 'thinking',
    delta: true,
    content: ' second'
  }), true);

  const thinking = container.querySelector('.ai-thinking-text');
  assert.equal(thinking.getAttribute('data-content'), 'First second');
  assert.equal(thinking.textContent, 'First second');
  assert.equal(appendDelta(container, {
    category: 'assistant',
    delta: true,
    content: ' ignored'
  }), false);
});

test('completed tool event renders both input and output when expanded', function () {
  const container = document.createElement('div');
  container.insertAdjacentHTML('beforeend', renderEvent({
    category: 'tool_result',
    label: 'bash',
    toolInput: 'check-project --mode focused',
    toolOutput: '8 checks passed'
  }));

  const sections = container.querySelectorAll('.ai-tool-section-content');
  assert.equal(sections.length, 2);
  assert.equal(sections[0].textContent, 'check-project --mode focused');
  assert.equal(sections[1].textContent, '8 checks passed');
  assert.match(container.querySelector('summary').textContent, /check-project --mode focused/);
});

test('unpacked Marked bundle renders GFM and sanitizes unsafe links', function (t) {
  const bundlePath = path.resolve(__dirname, '../../main/webapp/js/bundles/marked.umd.js');
  if (!fs.existsSync(bundlePath)) {
    t.skip('marked bundle not unpacked; run mvn process-resources');
    return;
  }

  const browserGlobal = {};
  vm.runInNewContext(fs.readFileSync(bundlePath, 'utf8'), browserGlobal);
  window.marked = browserGlobal.marked;

  try {
    assert.equal(typeof window.marked.parse, 'function');

    const container = document.createElement('div');
    container.insertAdjacentHTML('beforeend', renderEvent({
      category: 'assistant',
      label: 'Assistant',
      content: [
        '# Summary',
        '',
        '| Check | Result |',
        '| --- | --- |',
        '| Build | `pass` |',
        '',
        '[docs](https://example.com/docs) [unsafe](javascript:alert(1)) <script>alert(1)</script>'
      ].join('\n')
    }));

    const assistant = container.querySelector('.ai-msg-content-assistant');
    assert.equal(assistant.querySelector('h1').textContent, 'Summary');
    assert.equal(assistant.querySelectorAll('th').length, 2);
    assert.equal(assistant.querySelector('td code').textContent, 'pass');

    const links = assistant.querySelectorAll('a');
    assert.equal(links.length, 2);
    assert.equal(links[0].getAttribute('href'), 'https://example.com/docs');
    assert.equal(links[1].hasAttribute('href'), false);
    assert.equal(assistant.querySelector('script'), null);

    assert.equal(appendDelta(container, {
      category: 'assistant',
      delta: true,
      content: '\n\nStatus: **done**'
    }), true);
    assert.match(assistant.innerHTML, /<strong>done<\/strong>/);
  } finally {
    delete window.marked;
  }
});
