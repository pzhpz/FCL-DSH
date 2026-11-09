import { init } from 'l2d';

const MODEL_ROOT = 'models/';
const canvas = document.getElementById('stage');
const hint = document.getElementById('hint');
const DBG = new URLSearchParams(location.search).has('dbg');

function showHint(text) {
  hint.textContent = text;
  hint.style.display = 'grid';
  hint.style.placeItems = 'center';
  hint.style.textAlign = 'center';
}

function dbg(text) {
  if (!DBG)
    return;
  hint.textContent = text;
  hint.style.display = 'grid';
  hint.style.placeItems = 'start';
  hint.style.textAlign = 'left';
  hint.style.font = '13px/1.4 monospace';
}

function syncCanvasSize() {
  const dpr = Math.min(window.devicePixelRatio || 1, 2);
  const w = Math.round(canvas.clientWidth * dpr);
  const h = Math.round(canvas.clientHeight * dpr);
  if (w > 0 && h > 0 && (canvas.width !== w || canvas.height !== h)) {
    canvas.width = w;
    canvas.height = h;
    return true;
  }
  return false;
}

/* ---------------- 运行时找模型 ---------------- */

async function fetchText(url) {
  try {
    const res = await fetch(url, { cache: 'no-store' });
    return res.ok ? await res.text() : null;
  }
  catch {
    return null;
  }
}

function parseLinks(html) {
  const out = [];
  for (const m of html.matchAll(/href\s*=\s*["']([^"']+)["']/gi)) {
    const href = m[1];
    if (!href || href.startsWith('?') || href.startsWith('#') || href.startsWith('..'))
      continue;
    out.push(href);
  }
  return out;
}

// 收集 models/ 下所有 .model3.json / .model.json（最多 3 层）
async function collectModels() {
  const rootUrl = new URL(MODEL_ROOT, location.href).href;
  const found = [];

  const walk = async (dirUrl, depth) => {
    if (depth > 3)
      return;
    const html = await fetchText(dirUrl);
    if (!html)
      return;
    const links = parseLinks(html);
    for (const l of links) {
      if (/\.(model3|model)\.json$/i.test(l))
        found.push(new URL(l, dirUrl).href);
    }
    for (const l of links) {
      if (l.endsWith('/'))
        await walk(new URL(l, dirUrl).href, depth + 1);
    }
  };

  await walk(rootUrl, 0);
  return found;
}

// 多个模型时取“最后修改时间最新”的那个，避免一直读到旧目录
async function pickNewest(urls) {
  let best = null;
  for (const url of urls) {
    let time = 0;
    try {
      const res = await fetch(url, { method: 'HEAD', cache: 'no-store' });
      const lm = res.headers.get('last-modified');
      if (lm)
        time = Date.parse(lm) || 0;
    }
    catch {}
    if (!best || time > best.time)
      best = { url, time };
  }
  return best;
}

async function findModelPath() {
  const rootUrl = new URL(MODEL_ROOT, location.href).href;

  // 1) 手动清单优先
  const manifest = await fetchText(`${rootUrl}manifest.json`);
  if (manifest) {
    try {
      const json = JSON.parse(manifest);
      if (json.model)
        return new URL(json.model, rootUrl).href;
    }
    catch {}
  }

  // 2) 自动扫描，多个时取最新的
  const urls = await collectModels();
  if (!urls.length)
    return '';
  const best = await pickNewest(urls);
  if (DBG)
    dbg(`候选 ${urls.length} 个\n选中：${best.url.split('/models/')[1] ?? best.url}\n修改时间：${best.time ? new Date(best.time).toLocaleString() : '未知'}`);
  return best.url;
}

/* ---------------- 触屏输入 ---------------- */

/**
 * 库内部只监听 document 上的 mousemove / mouseout / pointerdown。
 * 手机没有鼠标事件，所以拖动和视线跟随会失效，只剩 pointerdown 的点击。
 * 这里把触屏手势翻译成等价的鼠标事件，坐标换算仍由库自己完成。
 */
function bindTouchInput() {
  let touched = false;

  const emit = (type, x, y) => {
    document.dispatchEvent(new MouseEvent(type, {
      clientX: x,
      clientY: y,
      bubbles: true,
      cancelable: false,
    }));
  };

  const onMove = (e) => {
    const t = e.touches[0] ?? e.changedTouches[0];
    if (!t)
      return;
    touched = true;
    emit('mousemove', t.clientX, t.clientY);
    if (DBG)
      dbg(`${e.type}  ${Math.round(t.clientX)}, ${Math.round(t.clientY)}`);
  };

  const onEnd = (e) => {
    const t = e.changedTouches[0];
    if (touched && t)
      emit('mousemove', t.clientX, t.clientY);
    emit('mouseout', 0, 0);
    touched = false;
    if (DBG)
      dbg(`${e.type}  end`);
  };

  document.addEventListener('touchstart', onMove, { passive: true });
  document.addEventListener('touchmove', onMove, { passive: true });
  document.addEventListener('touchend', onEnd, { passive: true });
  document.addEventListener('touchcancel', onEnd, { passive: true });

  if (DBG) {
    document.addEventListener('pointerdown', e => dbg(`pointerdown ${Math.round(e.clientX)}, ${Math.round(e.clientY)}`), { passive: true });
  }
}

/* ---------------- 启动 ---------------- */

(async () => {
  syncCanvasSize();

  const modelUrl = await findModelPath();
  if (!modelUrl) {
    showHint('没有找到模型。\n把模型文件夹放进 models/ 后刷新即可。');
    return;
  }

  const l2d = init(canvas);
  bindTouchInput();

  l2d.on('loaded', () => {
    if (!DBG)
      hint.style.display = 'none';
    l2d.resize();
  });

  // 给 model3.json 带上时间戳，避免读到浏览器缓存的旧配置
  const sep = modelUrl.includes('?') ? '&' : '?';
  l2d.load({ path: `${modelUrl}${sep}v=${Date.now()}`, logLevel: 'error' }).catch((e) => {
    showHint(`模型加载失败：\n${e}`);
  });

  window.addEventListener('resize', () => {
    if (syncCanvasSize())
      l2d.resize();
  });
})();
