// Aplica el tema antes del primer pintado (archivo externo por la CSP: script-src 'self').
(function () {
  try {
    var t = localStorage.getItem('kairo-theme');
    var dark = t === 'dark' || (t !== 'light' && window.matchMedia('(prefers-color-scheme: dark)').matches);
    var r = document.documentElement;
    if (dark) r.classList.add('dark');
    r.style.colorScheme = dark ? 'dark' : 'light';
  } catch (e) {}
})();
