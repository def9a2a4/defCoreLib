// A single shared hover tooltip for any element carrying a `data-tip` attribute.
// Replaces the native `title=` tooltips (which are slow and ignore the Monocraft
// theme) with an instant, themed one. Imported for its side effect by render.js,
// so it installs once on whichever page (catalog / item) pulls in render.js.
//
// Design notes:
//  - Delegated listeners on `document`: the catalog rebuilds its innerHTML on every
//    keystroke and hydrateHeads swaps in fresh <img> elements, so per-element
//    listeners would need constant re-attaching. closest('[data-tip]') survives it.
//  - Anchor-positioned (not cursor-following): a document-level `mousemove` would
//    fire every frame and contend with the OrbitControls 3D canvases (placed3d.js).
//    We position once per hover from the anchor's bounding box instead.
//  - Gated to hover-capable pointers so it never sticks on touch taps.

if (!window.__hovertip && window.matchMedia?.('(hover: hover)').matches) {
  window.__hovertip = true;

  const tip = document.createElement('div');
  tip.className = 'hovertip';
  document.body.appendChild(tip);

  let anchor = null;

  const hide = () => { tip.style.display = 'none'; anchor = null; };

  const place = () => {
    if (!anchor) return;
    const r = anchor.getBoundingClientRect();
    tip.style.display = 'block';
    const tw = tip.offsetWidth;
    const th = tip.offsetHeight;
    const pad = 6;
    let left = r.left + r.width / 2 - tw / 2;
    let top = r.top - th - pad;                 // above the anchor by default
    if (top < pad) top = r.bottom + pad;        // flip below if it would clip the top
    left = Math.max(pad, Math.min(left, window.innerWidth - tw - pad));
    tip.style.left = `${left}px`;
    tip.style.top = `${top}px`;
  };

  document.addEventListener('mouseover', (e) => {
    const el = e.target.closest?.('[data-tip]');
    if (!el || el === anchor) return;
    anchor = el;
    tip.textContent = el.dataset.tip;
    place();
  });

  document.addEventListener('mouseout', (e) => {
    if (anchor && !anchor.contains(e.relatedTarget)) hide();
  });

  // A fixed-position tooltip would detach from its anchor on scroll/resize — just hide.
  window.addEventListener('scroll', hide, true);
  window.addEventListener('resize', hide);
}
