/* ===== API 客户端 ===== */
class ApiError extends Error {}

const Api = {
  base: '/api',

  async request(method, url, body) {
    const opts = { method, headers: {} };
    if (body !== undefined && body !== null) {
      if (body instanceof FormData) {
        opts.body = body;
      } else {
        opts.headers['Content-Type'] = 'application/json';
        opts.body = JSON.stringify(body);
      }
    }
    let resp;
    try {
      resp = await fetch(this.base + url, opts);
    } catch (e) {
      throw new ApiError('网络请求失败，请检查服务是否运行');
    }
    let data = null;
    try { data = await resp.json(); } catch (e) { /* 非 JSON */ }
    if (!resp.ok || (data && data.code !== undefined && data.code !== 0)) {
      throw new ApiError((data && data.message) || ('请求失败（HTTP ' + resp.status + '）'));
    }
    return data ? data.data : null;
  },

  get(url) { return this.request('GET', url); },
  post(url, body) { return this.request('POST', url, body); },
  put(url, body) { return this.request('PUT', url, body); },
  del(url) { return this.request('DELETE', url); },

  upload(kbId, files) {
    const fd = new FormData();
    for (const f of files) fd.append('files', f);
    return this.request('POST', `/kbs/${kbId}/documents`, fd);
  },

  /** 详细上传：返回 {ok:[文档], failed:[{fileName,reason}]} */
  uploadDetailed(kbId, files) {
    const fd = new FormData();
    for (const f of files) fd.append('files', f);
    return this.request('POST', `/kbs/${kbId}/documents/upload`, fd);
  },

  /**
   * 流式对话：SSE over fetch
   * handlers: { onDelta(text), onDone(payload), onError(message) }
   */
  async streamChat(payload, handlers) {
    try {
      const resp = await fetch(this.base + '/chat/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      });
      if (!resp.ok || !resp.body) {
        let msg = '请求失败（HTTP ' + resp.status + '）';
        try {
          const j = await resp.json();
          if (j && j.message) msg = j.message;
        } catch (e) { /* ignore */ }
        handlers.onError(msg);
        return;
      }
      const reader = resp.body.getReader();
      const decoder = new TextDecoder('utf-8');
      let buf = '';
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buf += decoder.decode(value, { stream: true });
        let idx;
        while ((idx = buf.indexOf('\n\n')) >= 0) {
          const block = buf.slice(0, idx);
          buf = buf.slice(idx + 2);
          const line = block.split('\n').find(l => l.startsWith('data:'));
          if (line) {
            try {
              const evt = JSON.parse(line.slice(5).trim());
              if (evt.type === 'delta') handlers.onDelta(evt.content || '');
              else if (evt.type === 'done') handlers.onDone(evt);
              else if (evt.type === 'error') handlers.onError(evt.message || '生成失败');
            } catch (e) { /* 忽略非 JSON 块 */ }
          }
        }
      }
    } catch (e) {
      handlers.onError('网络中断：' + (e.message || ''));
    }
  }
};
