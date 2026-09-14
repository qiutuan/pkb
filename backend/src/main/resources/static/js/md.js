/* ===== 轻量 Markdown 渲染 ===== */
const Md = (() => {
  function esc(s) {
    return String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  }
  function inline(text) {
    let t = esc(text);
    // 代码
    t = t.replace(/`([^`]+)`/g, '<code>$1</code>');
    // 链接
    t = t.replace(/\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)/g, '<a href="$2" target="_blank" rel="noopener">$1</a>');
    // 加粗/斜体
    t = t.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
    t = t.replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<em>$2</em>');
    return t;
  }
  function render(src) {
    if (!src) return '<p class="md-empty"></p>';
    const lines = String(src).replace(/\r\n/g, '\n').split('\n');
    const blocks = [];
    let i = 0;
    const n = lines.length;
    const push = b => { if (b && b.trim()) blocks.push(b.trim()); };
    while (i < n) {
      const line = lines[i];
      // 代码块
      const fence = line.match(/^```(\w*)/);
      if (fence) {
        const lang = fence[1];
        const code = [];
        i++;
        while (i < n && !/^```/.test(lines[i])) { code.push(lines[i]); i++; }
        i++; // 跳过结束围栏
        blocks.push(`<pre class="code-block"><code>${esc(code.join('\n'))}</code></pre>`);
        continue;
      }
      // 表格
      if (line.trim().startsWith('|') && i + 1 < n && /^\s*\|[\s:|-]+\|\s*$/.test(lines[i + 1])) {
        const head = line.trim().split('|').map(s => s.trim()).filter((s, idx, arr) => idx > 0 && idx < arr.length - 1);
        i += 2;
        const rows = [];
        while (i < n && lines[i].trim().startsWith('|')) {
          const cells = lines[i].trim().split('|').map(s => s.trim()).filter((s, idx, arr) => idx > 0 && idx < arr.length - 1);
          rows.push(`<tr>${cells.map(c => `<td>${inline(c)}</td>`).join('')}</tr>`);
          i++;
        }
        blocks.push(`<div class="md-table-wrap"><table class="md-table"><thead><tr>${head.map(h => `<th>${inline(h)}</th>`).join('')}</tr></thead><tbody>${rows.join('')}</tbody></table></div>`);
        continue;
      }
      // 标题
      const h = line.match(/^(#{1,6})\s+(.*)/);
      if (h) {
        const lv = h[1].length;
        blocks.push(`<h${lv} class="md-h${lv}">${inline(h[2])}</h${lv}>`);
        i++;
        continue;
      }
      // 引用
      if (/^>\s?/.test(line)) {
        const quote = [];
        while (i < n && /^>\s?/.test(lines[i])) { quote.push(lines[i].replace(/^>\s?/, '')); i++; }
        blocks.push(`<blockquote class="md-quote">${inline(quote.join('\n'))}</blockquote>`);
        continue;
      }
      // 无序列表
      if (/^\s*[-*+]\s+/.test(line)) {
        const items = [];
        while (i < n && /^\s*[-*+]\s+/.test(lines[i])) { items.push(`<li>${inline(lines[i].replace(/^\s*[-*+]\s+/, ''))}</li>`); i++; }
        blocks.push(`<ul class="md-list">${items.join('')}</ul>`);
        continue;
      }
      // 有序列表
      if (/^\s*\d+\.\s+/.test(line)) {
        const items = [];
        while (i < n && /^\s*\d+\.\s+/.test(lines[i])) { items.push(`<li>${inline(lines[i].replace(/^\s*\d+\.\s+/, ''))}</li>`); i++; }
        blocks.push(`<ol class="md-list">${items.join('')}</ol>`);
        continue;
      }
      // 分隔线
      if (/^\s*([-*_])\1{2,}\s*$/.test(line)) { blocks.push('<hr class="md-hr">'); i++; continue; }
      // 普通段落（收集到空行）
      const para = [];
      while (i < n && lines[i].trim() !== '' && !/^(#{1,6})\s/.test(lines[i]) && !/^```/.test(lines[i])) {
        para.push(lines[i]);
        i++;
      }
      push(`<p>${inline(para.join('<br>'))}</p>`);
      while (i < n && lines[i].trim() === '') i++;
    }
    return blocks.join('\n');
  }
  return { render };
})();
