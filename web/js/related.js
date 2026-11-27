// "함께 즐기면 좋은 게임": KH Company's shared sibling-app list (kwanghyeon-cpu.github.io/related-apps.json).
// Same rules as app/.../related/RelatedApps.kt. Fetched at most once a day; when that fails, the last copy
// kept in this browser, then the copy shipped next to index.html. Only a public file is requested.
const RELATED_SELF_ID = 'lanedash';
const RELATED_LIST_URL = 'https://kwanghyeon-cpu.github.io/related-apps.json';
const RELATED_BUNDLED_URL = 'related-apps.json';
const RELATED_CACHE_KEY = 'lane_dash_related_apps';
const RELATED_REFRESH_MS = 24 * 60 * 60 * 1000;

/** Other apps from every group this app is in, in order, without repeats or apps that have nowhere to go. */
function relatedSiblings(catalog, selfId = RELATED_SELF_ID) {
  const ids = [];
  for (const g of catalog.groups || []) {
    if (!(g.apps || []).includes(selfId)) continue;
    for (const id of g.apps) if (id !== selfId && !ids.includes(id)) ids.push(id);
  }
  return ids
    .map((id) => catalog.apps && catalog.apps[id] && Object.assign({ id }, catalog.apps[id]))
    .filter((app) => app && relatedLink(app));
}

/** Public apps go to the Play Store page; unreleased ones to their web build; neither → not shown. */
function relatedLink(app) {
  if (app.public && app.package) return `https://play.google.com/store/apps/details?id=${app.package}`;
  return app.web || null;
}

function relatedShouldRefresh(fetchedAt, now) {
  return !fetchedAt || now < fetchedAt || now - fetchedAt >= RELATED_REFRESH_MS;
}

function readRelatedCache() {
  try { return JSON.parse(localStorage.getItem(RELATED_CACHE_KEY)) || null; } catch (e) { return null; }
}

async function fetchCatalog(url) {
  const res = await fetch(url, { cache: 'no-cache' });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const catalog = await res.json();
  if (!catalog || !catalog.apps || !catalog.groups) throw new Error('bad list');
  return catalog;
}

let relatedAppsPromise = null;
function loadRelatedApps() {
  if (relatedAppsPromise) return relatedAppsPromise;
  relatedAppsPromise = (async () => {
    const cached = readRelatedCache();
    let catalog = cached && cached.catalog;
    if (!catalog || relatedShouldRefresh(cached.fetchedAt, Date.now())) {
      try {
        catalog = await fetchCatalog(RELATED_LIST_URL);
        try { localStorage.setItem(RELATED_CACHE_KEY, JSON.stringify({ catalog, fetchedAt: Date.now() })); } catch (e) { /* storage off */ }
      } catch (e) {
        if (!catalog) catalog = await fetchCatalog(RELATED_BUNDLED_URL).catch(() => null);
      }
    }
    return catalog ? relatedSiblings(catalog) : [];
  })();
  return relatedAppsPromise;
}

function relatedAnchor(app, className) {
  const a = document.createElement('a');
  a.className = className;
  a.href = relatedLink(app);
  a.target = '_blank';
  a.rel = 'noopener';
  return a;
}

/** Fills `container` with the sibling card, or hides it when there's nothing to show. */
function renderRelatedCard(container, apps) {
  container.innerHTML = '';
  container.classList.toggle('hidden', apps.length === 0);
  if (!apps.length) return;
  const title = document.createElement('p');
  title.className = 'related-title';
  title.textContent = '함께 즐기면 좋은 게임';
  container.appendChild(title);
  for (const app of apps) {
    const row = relatedAnchor(app, 'related-row');
    const icon = document.createElement('span');
    icon.className = 'related-icon';
    if (app.icon) {
      const img = document.createElement('img');
      img.src = app.icon;
      img.alt = '';
      img.onerror = () => { icon.innerHTML = iconSvg('sneaker'); };
      icon.appendChild(img);
    } else {
      icon.innerHTML = iconSvg('sneaker');
    }
    const text = document.createElement('span');
    text.className = 'related-text';
    const name = document.createElement('strong');
    name.textContent = app.name;
    const tagline = document.createElement('span');
    tagline.textContent = app.tagline || '';
    text.append(name, tagline);
    const go = document.createElement('span');
    go.className = 'related-go';
    go.innerHTML = iconSvg('openExternal');
    row.append(icon, text, go);
    container.appendChild(row);
  }
}

/** One quiet link to the first sibling, e.g. "요리조리 런도 달려 보기"; hidden when there's none. */
function renderRelatedLine(container, apps) {
  container.innerHTML = '';
  container.classList.toggle('hidden', apps.length === 0);
  if (!apps.length) return;
  const a = relatedAnchor(apps[0], 'related-line');
  a.innerHTML = iconSvg('sneaker');
  a.appendChild(document.createTextNode(`${apps[0].name}도 달려 보기`));
  container.appendChild(a);
}
