/* The parts of the page that are not the 3D scene.
 *
 * Nothing here is required for the page to be readable: every screenshot is in the markup, the
 * first one is already showing, and all the words are plain HTML. This only swaps which screenshot
 * is on as you pass each moment, and fills in the static hero that stands in for the scene.
 */

(function () {
  "use strict";

  /* ------------------------------------------------- the sticky device follows the moment beside it */

  var moments = Array.prototype.slice.call(document.querySelectorAll(".moment[data-shot]"));
  var shots = {};
  Array.prototype.forEach.call(document.querySelectorAll(".device img[data-shot]"), function (img) {
    shots[img.getAttribute("data-shot")] = img;
  });

  function show(name) {
    var img = shots[name];
    if (!img || img.classList.contains("on")) return;
    Object.keys(shots).forEach(function (key) { shots[key].classList.remove("on"); });
    img.classList.add("on");
  }

  if (moments.length && "IntersectionObserver" in window) {
    // The moment nearest the middle of the screen is the one the device should be showing. A plain
    // "first one intersecting" rule flickers between two when both are partly on screen.
    var seen = new Map();
    var watcher = new IntersectionObserver(function (entries) {
      entries.forEach(function (e) { seen.set(e.target, e); });
      var best = null;
      var bestDistance = Infinity;
      seen.forEach(function (e) {
        if (!e.isIntersecting) return;
        var box = e.target.getBoundingClientRect();
        var distance = Math.abs((box.top + box.bottom) / 2 - window.innerHeight / 2);
        if (distance < bestDistance) { bestDistance = distance; best = e.target; }
      });
      if (best) show(best.getAttribute("data-shot"));
    }, { threshold: [0, 0.25, 0.5, 0.75, 1], rootMargin: "-10% 0px -10% 0px" });
    moments.forEach(function (m) { watcher.observe(m); });
  }

  /* ---------------------------------------------------------------------- the hero without WebGL */

  // Drawn in markup rather than left blank, because "the first frame already shows the idea" has to
  // hold when there is no frame at all. hero.js hides this once the scene is genuinely running.
  var fallback = document.querySelector(".hero-fallback");
  if (fallback) {
    fallback.innerHTML = [
      '<div class="still">',
      '  <div class="still-page">',
      '    <p class="still-title">Deep work</p>',
      '    <p class="still-sub">2 of 5 done</p>',
      '    <ul>',
      '      <li class="done"><span class="box">✓</span>Write up what the Thursday outage was</li>',
      '      <li><span class="box"></span>Draft the chapter on conflict arbitration</li>',
      '      <li><span class="box"></span>Read the JGit rebase source properly</li>',
      '      <li class="done"><span class="box">✓</span>Order more coffee</li>',
      '      <li><span class="box"></span>Book the chimney sweep</li>',
      '    </ul>',
      '  </div>',
      '  <div class="still-page raw">',
      '    <p class="still-title mono">deep-work.md</p>',
      '    <pre>- [x] Write up what the Thursday outage was\n',
      '- [ ] Draft the chapter on conflict arbitration\n',
      '- [ ] Read the JGit rebase source properly\n',
      '- [x] Order more coffee\n',
      '- [ ] Book the chimney sweep</pre>',
      '  </div>',
      '</div>',
    ].join("");
  }
})();
