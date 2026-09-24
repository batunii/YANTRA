/* The pinned hero.
 *
 * One idea, as a picture: the list you tick is a Markdown file, and the file is in a git repository
 * you own. The scene says that in three states, and scroll moves between them.
 *
 *   t = 0.00   a page of tasks, the way the app draws them
 *   t = 0.50   the same page as its raw Markdown — the same lines, with their syntax showing
 *   t = 1.00   the page joins a row of commits stretching back, each one a version you keep
 *
 * Rules this obeys:
 *   - The first frame already shows the idea. At t = 0 the page is upright, legible and full.
 *   - Scroll sets a *target*; the scene eases toward it on a time basis, so it reads the same on a
 *     trackpad flick and a slow drag, and it runs backwards as happily as forwards.
 *   - Rendering stops when it settles, and starts again on the next scroll. No idle animation loop.
 *   - Nothing on the page waits for this. If three.js does not load, or WebGL is unavailable, the
 *     markup underneath is already a static version of the same three states and simply stays.
 */

const CDN = "https://cdn.jsdelivr.net/npm/three@0.169.0/build/three.module.js";

const stage = document.querySelector("#scene");
const caption = document.querySelector("#hero-caption");
const hero = document.querySelector(".hero");

const CAPTIONS = [
  "<b>A list you tick.</b> Ordinary tasks, ordinary app.",
  "<b>It was always a Markdown file.</b> The same lines, with their syntax showing.",
  "<b>In a git repository you own.</b> Every change is a commit you keep.",
];

const still = window.matchMedia("(prefers-reduced-motion: reduce)");

/** Scroll position through the pin, 0..1. Without pinning there is only the first frame. */
function scrolled() {
  if (still.matches || !hero) return 0;
  const box = hero.getBoundingClientRect();
  const travel = box.height - window.innerHeight;
  if (travel <= 0) return 0;
  return Math.min(Math.max(-box.top / travel, 0), 1);
}

function setCaption(t) {
  if (!caption) return;
  const which = t < 0.34 ? 0 : t < 0.72 ? 1 : 2;
  if (caption.dataset.which === String(which)) return;
  caption.dataset.which = String(which);
  caption.innerHTML = CAPTIONS[which];
}

setCaption(0);
window.addEventListener("scroll", () => setCaption(scrolled()), { passive: true });

/* ------------------------------------------------------------------ the page, drawn to a texture */

const LINES = [
  { done: true, text: "Write up what the Thursday outage was" },
  { done: false, text: "Draft the chapter on conflict arbitration" },
  { done: false, text: "Read the JGit rebase source properly" },
  { done: true, text: "Order more coffee" },
  { done: false, text: "Book the chimney sweep" },
];

function ink() {
  const dark = matchMedia("(prefers-color-scheme: dark)").matches;
  return dark
    ? { bg: "#2d2b28", rule: "rgba(242,240,236,0.10)", text: "#f2f0ec", dim: "#a6a29a", accent: "#E8865F" }
    : { bg: "#fdfcfa", rule: "rgba(48,44,38,0.10)", text: "#35322c", dim: "#78736a", accent: "#D85A30" };
}

/** Draws the page in either state onto a canvas: 0 = as the app shows it, 1 = as the file reads. */
function pageTexture(raw) {
  const w = 1024, h = 1330;
  const c = document.createElement("canvas");
  c.width = w; c.height = h;
  const g = c.getContext("2d");
  const col = ink();

  g.fillStyle = col.bg;
  g.fillRect(0, 0, w, h);

  const pad = 82;
  let y = 150;

  g.fillStyle = col.text;
  g.font = "700 58px 'Bricolage Grotesque', system-ui, sans-serif";
  g.fillText(raw ? "deep-work.md" : "Deep work", pad, y);
  y += 34;

  g.fillStyle = col.dim;
  g.font = "400 30px 'Space Mono', monospace";
  g.fillText(raw ? "plain text, on your disk" : "2 of 5 done", pad, y + 34);
  y += 120;

  g.textBaseline = "middle";
  for (const line of LINES) {
    g.strokeStyle = col.rule;
    g.lineWidth = 2;
    g.beginPath(); g.moveTo(pad, y + 84); g.lineTo(w - pad, y + 84); g.stroke();

    if (raw) {
      g.font = "400 30px 'Space Mono', monospace";
      g.fillStyle = col.dim;
      const marker = line.done ? "- [x] " : "- [ ] ";
      g.fillText(marker, pad, y + 42);
      const offset = g.measureText(marker).width;
      g.fillStyle = line.done ? col.dim : col.text;
      g.fillText(clip(g, line.text, w - pad * 2 - offset), pad + offset, y + 42);
    } else {
      // The box, drawn rather than typed, because that is the difference being shown.
      const box = 34;
      g.lineWidth = 3;
      g.strokeStyle = line.done ? col.accent : col.dim;
      roundRect(g, pad, y + 42 - box / 2, box, box, 9);
      g.stroke();
      if (line.done) {
        g.strokeStyle = col.accent;
        g.lineWidth = 5;
        g.lineCap = "round";
        g.beginPath();
        g.moveTo(pad + 9, y + 42);
        g.lineTo(pad + 15, y + 49);
        g.lineTo(pad + 25, y + 35);
        g.stroke();
      }
      g.font = "400 32px 'Space Grotesk', system-ui, sans-serif";
      g.fillStyle = line.done ? col.dim : col.text;
      g.fillText(clip(g, line.text, w - pad * 2 - 62), pad + 62, y + 42);
    }
    y += 168;
  }
  return c;
}

function clip(g, text, max) {
  if (g.measureText(text).width <= max) return text;
  let cut = text;
  while (cut.length > 4 && g.measureText(cut + "…").width > max) cut = cut.slice(0, -1);
  return cut + "…";
}

function roundRect(g, x, y, w, h, r) {
  g.beginPath();
  g.moveTo(x + r, y);
  g.arcTo(x + w, y, x + w, y + h, r);
  g.arcTo(x + w, y + h, x, y + h, r);
  g.arcTo(x, y + h, x, y, r);
  g.arcTo(x, y, x + w, y, r);
  g.closePath();
}

/* ------------------------------------------------------------------------------------ the scene */

async function run() {
  if (!stage) return;

  // WebGL first: if the context cannot be had, there is nothing to load and the fallback stands.
  const probe = document.createElement("canvas");
  const ok = probe.getContext("webgl2") || probe.getContext("webgl");
  if (!ok) return;

  let THREE;
  try {
    THREE = await import(CDN);
  } catch {
    return;   // CDN blocked or offline. The static hero underneath is already correct.
  }

  const renderer = new THREE.WebGLRenderer({ canvas: stage, antialias: true, alpha: true });
  renderer.setPixelRatio(Math.min(devicePixelRatio, 2));

  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(38, 1, 0.1, 100);

  const shown = new THREE.CanvasTexture(pageTexture(false));
  const rawText = new THREE.CanvasTexture(pageTexture(true));
  for (const t of [shown, rawText]) {
    t.colorSpace = THREE.SRGBColorSpace;
    t.anisotropy = renderer.capabilities.getMaxAnisotropy();
  }

  const PAGE_W = 2.0, PAGE_H = 2.6;

  // The front page: two coplanar faces, crossfaded, so the list *becomes* the file rather than
  // being replaced by a different object.
  const front = new THREE.Group();
  const shownFace = new THREE.Mesh(
    new THREE.PlaneGeometry(PAGE_W, PAGE_H),
    new THREE.MeshBasicMaterial({ map: shown, transparent: true })
  );
  const rawFace = new THREE.Mesh(
    new THREE.PlaneGeometry(PAGE_W, PAGE_H),
    new THREE.MeshBasicMaterial({ map: rawText, transparent: true, opacity: 0 })
  );
  rawFace.position.z = 0.004;
  front.add(shownFace, rawFace);
  scene.add(front);

  // The history behind it: older versions of the same page, each a commit.
  const accent = new THREE.Color(getComputedStyle(document.documentElement)
    .getPropertyValue("--accent").trim() || "#D85A30");
  const past = [];
  const PAST = 4;
  for (let i = 0; i < PAST; i++) {
    const card = new THREE.Mesh(
      new THREE.PlaneGeometry(PAGE_W, PAGE_H),
      new THREE.MeshBasicMaterial({ map: rawText, transparent: true, opacity: 0 })
    );
    scene.add(card);
    const dot = new THREE.Mesh(
      new THREE.SphereGeometry(0.052, 20, 16),
      new THREE.MeshBasicMaterial({ color: accent, transparent: true, opacity: 0 })
    );
    scene.add(dot);
    past.push({ card, dot });
  }

  // The line the commits sit on.
  const railMaterial = new THREE.LineBasicMaterial({
    color: accent, transparent: true, opacity: 0,
  });
  const rail = new THREE.Line(new THREE.BufferGeometry(), railMaterial);
  scene.add(rail);

  // Where the page sits when nothing has been scrolled. On a wide screen it belongs in the right
  // half, because the words are in the left one and a headline across a picture of a list is two
  // things nobody can read. On a narrow screen there is only one column, so it sits behind the
  // words and the fallback's own layout rule applies instead.
  let restX = 0;
  // On a phone there is one column, so the page cannot sit beside the words — it sits under them,
  // lower down and smaller, where it is still the first thing the idea is read from but is not
  // behind the sentence explaining it.
  let restY = 0;
  let restScale = 1;
  let wide = true;
  let baseZ = 5.4;

  function size() {
    const w = stage.clientWidth || innerWidth;
    const h = stage.clientHeight || innerHeight;
    renderer.setSize(w, h, false);
    camera.aspect = w / h;
    wide = w >= 900;
    // Further back on a narrow screen, or the page runs off the sides.
    baseZ = wide ? 5.4 : 6.6;
    camera.position.set(0, 0, baseZ);
    camera.updateProjectionMatrix();
    restX = wide ? 1.42 : 0;
    restY = wide ? 0 : -1.38;
    restScale = wide ? 1 : 0.72;
  }
  size();

  const easeInOut = (x) => (x < 0.5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2);

  function place(t) {
    // 0 -> 0.5: the list turns into the file. 0.5 -> 1: the file joins its history.
    const toRaw = easeInOut(Math.min(t / 0.5, 1));
    const toHistory = easeInOut(Math.max((t - 0.5) / 0.5, 0));

    rawFace.material.opacity = toRaw;
    shownFace.material.opacity = 1 - toRaw;

    // Back off as the history appears, so the row has somewhere to be and the front page stops
    // running off the right edge.
    camera.position.z = baseZ + 1.5 * toHistory;

    front.rotation.y = -0.52 * toHistory;
    front.rotation.x = 0.06 * toHistory;
    front.position.x = restX;
    front.position.y = restY * (1 - 0.35 * toHistory);
    front.position.z = 0.55 * toHistory;
    front.scale.setScalar(restScale * (1 - 0.10 * toHistory));

    // The older versions recede *into* the picture rather than sliding across it: each one is
    // further back, so perspective does the shrinking. Their world x grows with depth, because
    // perspective otherwise drags anything distant toward the middle of the screen — which is where
    // the words are. Holding the screen column keeps the whole history in the half it belongs to.
    const footing = front.position.y - (PAGE_H * restScale) / 2 - 0.30;
    const points = [new THREE.Vector3(front.position.x, footing, front.position.z)];
    past.forEach(({ card, dot }, i) => {
      const step = i + 1;
      const z = front.position.z - step * 1.15 * toHistory;
      const depth = (camera.position.z - z) / (camera.position.z - front.position.z);
      const x = front.position.x * depth - step * 0.55 * toHistory;
      card.position.set(x, front.position.y, z);
      card.rotation.y = -0.52 * toHistory;
      card.rotation.x = 0.06 * toHistory;
      card.scale.setScalar(restScale * (1 - 0.10 * toHistory));
      card.material.opacity = Math.max(toHistory * 1.1 - step * 0.10, 0) * 0.42;

      dot.position.set(x, footing, z);
      dot.material.opacity = Math.max(toHistory * 1.3 - step * 0.12, 0);
      points.push(dot.position.clone());
    });
    rail.geometry.setFromPoints(points);
    railMaterial.opacity = toHistory * 0.55;
  }

  /* ---------------------------------------------------------- time-based easing, then a full stop */

  let current = scrolled();
  let target = current;
  let last = performance.now();
  let running = false;

  function frame(now) {
    const dt = Math.min((now - last) / 1000, 0.05);
    last = now;
    // Exponential approach: frame-rate independent, and the same shape in both directions.
    const k = 1 - Math.exp(-dt * 7.5);
    current += (target - current) * k;

    if (Math.abs(target - current) < 0.0004) {
      current = target;
      place(current);
      renderer.render(scene, camera);
      running = false;          // settled: stop rendering entirely until something moves
      return;
    }
    place(current);
    renderer.render(scene, camera);
    requestAnimationFrame(frame);
  }

  function wake() {
    if (running) return;
    running = true;
    last = performance.now();
    requestAnimationFrame(frame);
  }

  place(current);
  renderer.render(scene, camera);

  if (!still.matches) {
    addEventListener("scroll", () => { target = scrolled(); wake(); }, { passive: true });
  }
  addEventListener("resize", () => { size(); target = scrolled(); wake(); });
  matchMedia("(prefers-color-scheme: dark)").addEventListener("change", () => {
    shown.image = pageTexture(false); shown.needsUpdate = true;
    rawText.image = pageTexture(true); rawText.needsUpdate = true;
    wake();
  });

  // The scene is up, so the static stand-in can go.
  document.querySelector(".hero-fallback")?.setAttribute("hidden", "");
  stage.removeAttribute("aria-hidden");
}

run();
