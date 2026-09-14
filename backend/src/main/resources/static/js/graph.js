/* ===== 知识图谱画布（力导向图，无外部依赖） ===== */
class GraphView {
  constructor(canvas, opts = {}) {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d');
    this.nodes = [];
    this.links = [];
    this.onNodeClick = opts.onNodeClick || null;
    this.typeColors = {
      '人物': '#0ea5e9', '组织': '#8b5cf6', '产品': '#f59e0b', '技术': '#10b981',
      '概念': '#f43f5e', '地点': '#14b8a6', '事件': '#ef4444', '行业': '#6366f1',
      '文献': '#84cc16', '未分类': '#94a3b8'
    };
    this.view = { x: 0, y: 0, k: 1 };
    this.hover = null;
    this.raf = null;
    this.alpha = 0;
    this._bind();
    this._resize();
    window.addEventListener('resize', () => this._resize());
  }

  _resize() {
    const rect = this.canvas.parentElement.getBoundingClientRect();
    const dpr = window.devicePixelRatio || 1;
    this.canvas.width = rect.width * dpr;
    this.canvas.height = rect.height * dpr;
    this.canvas.style.width = rect.width + 'px';
    this.canvas.style.height = rect.height + 'px';
    this.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    this.w = rect.width;
    this.h = rect.height;
  }

  _bind() {
    const c = this.canvas;
    let dragging = null, moved = false;
    c.addEventListener('wheel', e => {
      e.preventDefault();
      const factor = e.deltaY > 0 ? 0.9 : 1.1;
      const rect = c.getBoundingClientRect();
      const mx = e.clientX - rect.left, my = e.clientY - rect.top;
      this.view.k = Math.min(3, Math.max(0.1, this.view.k * factor));
      this.view.x = mx - (mx - this.view.x) * factor;
      this.view.y = my - (my - this.view.y) * factor;
    }, { passive: false });

    c.addEventListener('mousedown', e => {
      dragging = { x: e.clientX, y: e.clientY, vx: this.view.x, vy: this.view.y };
      moved = false;
    });
    window.addEventListener('mousemove', e => {
      if (dragging) {
        const dx = e.clientX - dragging.x, dy = e.clientY - dragging.y;
        if (Math.abs(dx) + Math.abs(dy) > 3) moved = true;
        this.view.x = dragging.vx + dx;
        this.view.y = dragging.vy + dy;
      }
      const rect = c.getBoundingClientRect();
      const p = this.toWorld(e.clientX - rect.left, e.clientY - rect.top);
      this.hover = this.nodeAt(p.x, p.y);
      c.style.cursor = this.hover ? 'pointer' : 'grab';
    });
    window.addEventListener('mouseup', e => {
      if (dragging && !moved) {
        const rect = c.getBoundingClientRect();
        const p = this.toWorld(e.clientX - rect.left, e.clientY - rect.top);
        const n = this.nodeAt(p.x, p.y);
        if (n && this.onNodeClick) this.onNodeClick(n);
      }
      dragging = null;
    });
  }

  setData(nodes, links) {
    const N = nodes.length;
    const side = Math.sqrt(this.w * this.h / Math.max(1, N));
    const cx = 0, cy = 0;
    nodes.forEach((nd, i) => {
      const ang = (i / Math.max(1, N)) * Math.PI * 2 + Math.random() * 0.5;
      const r = side * 0.45 * (0.6 + Math.random() * 0.4);
      nd.x = cx + Math.cos(ang) * r;
      nd.y = cy + Math.sin(ang) * r;
      nd.vx = 0; nd.vy = 0;
      nd.degree = nd.degree || 0;
    });
    this.nodes = nodes;
    this.links = links;
    this.alpha = 1;
    this.start();
  }

  start() {
    if (!this.raf) {
      const step = () => {
        if (this.alpha > 0.005) {
          this._sim();
          this.alpha *= 0.985;
        }
        this.draw();
        this.raf = requestAnimationFrame(step);
      };
      this.raf = requestAnimationFrame(step);
    }
  }
  stop() {
    if (this.raf) { cancelAnimationFrame(this.raf); this.raf = null; }
  }

  _sim() {
    const ns = this.nodes, ls = this.links, N = ns.length;
    const kRep = 900 / Math.max(1, Math.sqrt(N));
    const l0 = Math.sqrt(this.w * this.h / Math.max(1, N)) * 0.55;
    const kSpr = 0.04;
    for (let i = 0; i < N; i++) {
      for (let j = i + 1; j < N; j++) {
        const a = ns[i], b = ns[j];
        let dx = a.x - b.x, dy = a.y - b.y;
        let d2 = dx * dx + dy * dy;
        if (d2 < 1) { dx = Math.random() - 0.5; dy = Math.random() - 0.5; d2 = 1; }
        const d = Math.sqrt(d2);
        const f = kRep / d2;
        const fx = dx / d * f, fy = dy / d * f;
        a.vx += fx; a.vy += fy;
        b.vx -= fx; b.vy -= fy;
      }
    }
    // 解析链接端点（节点对象引用）
    const idx = new Map();
    ns.forEach((n, i) => idx.set(n.id, n));
    for (const l of ls) {
      const a = idx.get(l.source), b = idx.get(l.target);
      if (!a || !b) continue;
      let dx = b.x - a.x, dy = b.y - a.y;
      const d = Math.sqrt(dx * dx + dy * dy) || 1;
      const f = kSpr * (d - l0);
      dx /= d; dy /= d;
      a.vx += f * dx; a.vy += f * dy;
      b.vx -= f * dx; b.vy -= f * dy;
    }
    for (const n of ns) {
      n.vx += -n.x * 0.006;
      n.vy += -n.y * 0.006;
      n.vx *= 0.85; n.vy *= 0.85;
      n.x += n.vx; n.y += n.vy;
    }
  }

  toWorld(sx, sy) {
    return { x: (sx - this.view.x) / this.view.k, y: (sy - this.view.y) / this.view.k };
  }

  nodeAt(wx, wy) {
    for (const n of this.nodes) {
      const r = this.radius(n);
      const dx = n.x - wx, dy = n.y - wy;
      if (dx * dx + dy * dy <= r * r) return n;
    }
    return null;
  }

  radius(n) {
    const base = 5 + Math.min(10, Math.log2((n.degree || 0) + 1) * 3);
    return base + (n.matched ? 2 : 0);
  }

  color(n) {
    return this.typeColors[n.type] || '#94a3b8';
  }

  draw() {
    const ctx = this.ctx;
    ctx.clearRect(0, 0, this.w, this.h);
    ctx.save();
    ctx.translate(this.view.x, this.view.y);
    ctx.scale(this.view.k, this.view.k);

    // 边
    const idx = new Map();
    this.nodes.forEach(n => idx.set(n.id, n));
    ctx.lineWidth = 1 / this.view.k;
    for (const l of this.links) {
      const a = idx.get(l.source), b = idx.get(l.target);
      if (!a || !b) continue;
      ctx.strokeStyle = 'rgba(148, 163, 184, 0.45)';
      ctx.beginPath();
      ctx.moveTo(a.x, a.y);
      ctx.lineTo(b.x, b.y);
      ctx.stroke();
    }
    // 节点
    for (const n of this.nodes) {
      const r = this.radius(n);
      ctx.beginPath();
      ctx.arc(n.x, n.y, r, 0, Math.PI * 2);
      ctx.fillStyle = this.color(n);
      ctx.globalAlpha = n.matched ? 1 : 0.85;
      ctx.fill();
      ctx.globalAlpha = 1;
      if (n.matched) {
        ctx.strokeStyle = '#f59e0b';
        ctx.lineWidth = 2 / this.view.k;
        ctx.stroke();
      }
      if (this.hover === n) {
        ctx.strokeStyle = '#0f172a';
        ctx.lineWidth = 1.5 / this.view.k;
        ctx.stroke();
      }
    }
    // 标签
    const labelNodes = this.nodes.filter(n => this.view.k > 0.7 || n.matched || this.nodes.length <= 40);
    ctx.font = `${11 / this.view.k}px -apple-system, "PingFang SC", sans-serif`;
    ctx.textAlign = 'center';
    for (const n of labelNodes.slice(0, 300)) {
      const r = this.radius(n);
      ctx.fillStyle = 'rgba(30, 41, 59, 0.92)';
      const label = n.name.length > 14 ? n.name.slice(0, 14) + '…' : n.name;
      ctx.fillText(label, n.x, n.y + r + 12 / this.view.k);
    }
    ctx.restore();
  }
}
