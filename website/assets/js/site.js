/*
 * Comportamento da página do KeyFlow: tema, cabeçalho, revelações de reserva, luz do cursor,
 * cartões com brilho, botões magnéticos, a orbe do topo, a demonstração do ditado e o índice da documentação.
 * Sem dependências, sem rede e sem rastreio. Com prefers-reduced-motion, nada se mexe.
 */
(function () {
  'use strict';

  var raiz = document.documentElement;
  var calmo = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  var ponteiroFino = window.matchMedia('(hover: hover) and (pointer: fine)').matches;

  function limitar(v, min, max) { return Math.min(max, Math.max(min, v)); }

  /* ── Tema: segue o aparelho por padrão, claro/escuro opcional; lembra no navegador. ── */
  var botaoTema = document.querySelector('.tema');
  var corTema = document.querySelector('meta[name="theme-color"]');
  var mediaClaro = window.matchMedia('(prefers-color-scheme: light)');

  function temaAtual() {
    var definido = raiz.getAttribute('data-theme');
    if (definido === 'claro' || definido === 'escuro') return definido;
    return mediaClaro.matches ? 'claro' : 'escuro';
  }

  function sincronizarTema() {
    var claro = temaAtual() === 'claro';
    if (botaoTema) botaoTema.setAttribute('aria-pressed', String(claro));
    if (corTema) corTema.setAttribute('content', claro ? '#f1e7c9' : '#12100d');
  }

  function aplicarTema(tema) {
    if (tema === 'claro') raiz.setAttribute('data-theme', 'claro');
    else if (tema === 'escuro') raiz.setAttribute('data-theme', 'escuro');
    try { localStorage.setItem('kf-tema', tema); } catch (e) { /* armazenamento bloqueado: segue sem lembrar */ }
    sincronizarTema();
  }

  if (mediaClaro && mediaClaro.addEventListener) {
    mediaClaro.addEventListener('change', function () {
      try { if (localStorage.getItem('kf-tema')) return; } catch (e) {}
      sincronizarTema();
    });
  }

  if (botaoTema) {
    botaoTema.addEventListener('click', function (ev) {
      var proximo = temaAtual() === 'claro' ? 'escuro' : 'claro';
      if (!document.startViewTransition || calmo) { aplicarTema(proximo); return; }
      var caixa = botaoTema.getBoundingClientRect();
      var x = ev.clientX || caixa.left + caixa.width / 2;
      var y = ev.clientY || caixa.top + caixa.height / 2;
      var raio = Math.hypot(Math.max(x, innerWidth - x), Math.max(y, innerHeight - y));
      raiz.classList.add('vt-tema');
      var transicao = document.startViewTransition(function () { aplicarTema(proximo); });
      transicao.ready.then(function () {
        raiz.animate(
          { clipPath: ['circle(0px at ' + x + 'px ' + y + 'px)', 'circle(' + raio + 'px at ' + x + 'px ' + y + 'px)'] },
          { duration: 720, easing: 'cubic-bezier(.2,.75,.2,1)', pseudoElement: '::view-transition-new(root)' }
        );
      }).catch(function () {});
      transicao.finished.then(function () { raiz.classList.remove('vt-tema'); }, function () { raiz.classList.remove('vt-tema'); });
    });
  }
  sincronizarTema();

  /* ── Cabeçalho: vira vidro depois do primeiro movimento de rolagem. ── */
  var topo = document.querySelector('.topo');
  function aoRolarTopo() { if (topo) topo.classList.toggle('rolou', window.scrollY > 24); }
  window.addEventListener('scroll', aoRolarTopo, { passive: true });
  aoRolarTopo();

  /* ── Revelações: o CSS usa animation-timeline; aqui fica a reserva para quem não tem. ── */
  var temLinhaDoTempo = window.CSS && CSS.supports && CSS.supports('animation-timeline: view()');
  if (!temLinhaDoTempo && !calmo && 'IntersectionObserver' in window) {
    var observador = new IntersectionObserver(function (entradas) {
      entradas.forEach(function (e) {
        if (e.isIntersecting) { e.target.classList.add('vista'); observador.unobserve(e.target); }
      });
    }, { rootMargin: '0px 0px -8% 0px' });
    document.querySelectorAll('.revela').forEach(function (el) { observador.observe(el); });
    raiz.classList.add('revelar');
  }

  /* ── Luz que segue o cursor, brilho nas bordas dos cartões e botões magnéticos. ── */
  if (ponteiroFino && !calmo) {
    var luz = document.querySelector('.luz');
    var lx = 0;
    var ly = 0;
    var luzPendente = false;
    window.addEventListener('pointermove', function (e) {
      lx = e.clientX;
      ly = e.clientY;
      raiz.classList.add('ponteiro');
      if (luz && !luzPendente) {
        luzPendente = true;
        requestAnimationFrame(function () {
          luzPendente = false;
          luz.style.transform = 'translate3d(' + lx + 'px,' + ly + 'px,0)';
        });
      }
    }, { passive: true });
    document.documentElement.addEventListener('pointerleave', function () { raiz.classList.remove('ponteiro'); });

    document.querySelectorAll('.cartao, .plano, .peca, .doc-cartao').forEach(function (el) {
      el.addEventListener('pointermove', function (e) {
        var c = el.getBoundingClientRect();
        el.style.setProperty('--x', (e.clientX - c.left).toFixed(1) + 'px');
        el.style.setProperty('--y', (e.clientY - c.top).toFixed(1) + 'px');
      });
    });

    document.querySelectorAll('.botao').forEach(function (b) {
      b.addEventListener('pointermove', function (e) {
        var c = b.getBoundingClientRect();
        b.style.setProperty('--bx', ((e.clientX - c.left - c.width / 2) * 0.16).toFixed(1) + 'px');
        b.style.setProperty('--by', ((e.clientY - c.top - c.height / 2) * 0.28).toFixed(1) + 'px');
      });
      b.addEventListener('pointerleave', function () {
        b.style.setProperty('--bx', '0px');
        b.style.setProperty('--by', '0px');
      });
    });
  }

  /* ── Documentação (/doc, /docs): o índice lateral destaca a seção que está na tela; o do celular fecha ao escolher. ── */
  var linksIndice = document.querySelectorAll('.doc-indice a[href^="#"]');
  if (linksIndice.length && 'IntersectionObserver' in window) {
    var lateral = document.querySelector('.doc-lateral');
    var linkDaSecao = {};
    linksIndice.forEach(function (a) { linkDaSecao[a.getAttribute('href').slice(1)] = a; });
    var secoesDoc = [].slice.call(document.querySelectorAll('.doc-secao'));
    var naFaixa = [];
    var marcar = function () {
      var atual = secoesDoc.filter(function (s) { return naFaixa.indexOf(s) !== -1; })[0];
      linksIndice.forEach(function (a) { a.removeAttribute('aria-current'); });
      var link = atual && linkDaSecao[atual.id];
      if (!link) return;
      link.setAttribute('aria-current', 'location');
      if (lateral && (link.offsetTop < lateral.scrollTop || link.offsetTop + link.offsetHeight > lateral.scrollTop + lateral.clientHeight)) {
        lateral.scrollTop = link.offsetTop - lateral.clientHeight / 2;
      }
    };
    // A faixa vai de logo abaixo do cabeçalho até perto da metade da tela: a seção que a toca é a que se está lendo.
    var observadorDoc = new IntersectionObserver(function (entradas) {
      entradas.forEach(function (e) {
        var i = naFaixa.indexOf(e.target);
        if (e.isIntersecting && i === -1) naFaixa.push(e.target);
        else if (!e.isIntersecting && i !== -1) naFaixa.splice(i, 1);
      });
      marcar();
    }, { rootMargin: '-96px 0px -55% 0px' });
    secoesDoc.forEach(function (s) { observadorDoc.observe(s); });
  }
  document.querySelectorAll('.doc-indice-movel a').forEach(function (a) {
    a.addEventListener('click', function () {
      var caixa = a.closest('details');
      if (caixa) caixa.removeAttribute('open');
    });
  });

  if (calmo || !window.KFOrbe) return;

  /* ── A orbe do topo: respira em repouso; o cursor perto e a rolagem a fazem "ouvir". ── */
  var caixaOrbe = document.querySelector('.heroi-orbe');
  // A orbe viva só assume depois da entrada da página; até lá fica o SVG estático, que é o mesmo primeiro quadro.
  function depoisDaEntrada(fn) {
    function agendar() { setTimeout(fn, 900); }
    if (document.readyState === 'complete') agendar(); else window.addEventListener('load', agendar, { once: true });
  }
  if (caixaOrbe) depoisDaEntrada(function () {
    var tela = caixaOrbe.querySelector('canvas');
    var brilho = caixaOrbe.querySelector('.orbe-brilho');
    var proximidade = 0;
    var agito = 0;
    var pulso = 0;
    var ultimoBrilho = -1;
    var orbe = KFOrbe.criar(tela, {
      escala: 0.24,
      realceAneis: 1.7,
      aoQuadro: function (pesos, dt) {
        agito *= Math.exp(-dt / 520);
        pulso *= Math.exp(-dt / 1900);
        var e = Math.max(proximidade, agito, pulso);
        orbe.mistura({ ouvindo: e, repouso: 1 - e });
        // Só mexe no estilo quando o brilho muda de fato: em repouso, nenhum recálculo por quadro.
        var b = Math.round(pesos.ouvindo * 200) / 200;
        if (brilho && b !== ultimoBrilho) {
          ultimoBrilho = b;
          brilho.style.opacity = (0.5 + 0.5 * b).toFixed(3);
          brilho.style.transform = 'scale(' + (1 + 0.14 * b).toFixed(3) + ')';
        }
      }
    });
    if (orbe) {
      caixaOrbe.classList.add('viva');
      window.addEventListener('pointermove', function (e) {
        if (e.pointerType !== 'mouse' && e.pointerType !== 'pen') return;
        var c = tela.getBoundingClientRect();
        var cx = c.left + c.width / 2;
        var cy = c.top + c.height / 2;
        var raio = c.width * 0.24;
        var d = Math.hypot(e.clientX - cx, e.clientY - cy);
        proximidade = 0.92 * limitar(1 - (d - raio) / (raio * 2.6), 0, 1);
        orbe.deslocar(limitar((e.clientX - cx) * 0.035, -18, 18), limitar((e.clientY - cy) * 0.035, -18, 18));
      }, { passive: true });
      document.documentElement.addEventListener('pointerleave', function () {
        proximidade = 0;
        orbe.deslocar(0, 0);
      });
      var ultimaRolagem = window.scrollY;
      var ultimoInstante = performance.now();
      window.addEventListener('scroll', function () {
        var agora = performance.now();
        var v = Math.abs(window.scrollY - ultimaRolagem) / Math.max(agora - ultimoInstante, 8);
        ultimaRolagem = window.scrollY;
        ultimoInstante = agora;
        agito = Math.max(agito, limitar(v / 2.4, 0, 0.85));
      }, { passive: true });
      caixaOrbe.addEventListener('pointerdown', function () { pulso = 1; });
    }
  });

  /* ── Demonstração: a orbe ouve, transcreve e o texto entra no campo, como no app. ── */
  var demo = document.querySelector('.demo');
  if (demo) {
    var campo = demo.querySelector('.demo-campo');
    var texto = demo.querySelector('.demo-texto');
    var tempo = demo.querySelector('.demo-tempo');
    var legenda = demo.querySelector('.demo-estado');
    var telaMini = demo.querySelector('.demo-orbe canvas');
    var palavras = texto.textContent.trim().split(/\s+/);
    texto.textContent = '';
    var spans = palavras.map(function (p, i) {
      var s = document.createElement('span');
      s.className = 'palavra';
      s.textContent = p + (i < palavras.length - 1 ? ' ' : '');
      s.style.transitionDelay = (i * 60) + 'ms';
      texto.appendChild(s);
      return s;
    });
    var mini = KFOrbe.criar(telaMini, { escala: 0.3, realceAneis: 1.6, suavidade: 220 });
    if (mini) demo.querySelector('.demo-orbe').classList.add('viva');

    var relogios = [];
    var ativo = false;
    function depois(ms, fn) { relogios.push(setTimeout(fn, ms)); }
    function limpar() { relogios.forEach(clearTimeout); relogios = []; }
    function rotulo(nome) {
      if (legenda) legenda.textContent = legenda.getAttribute('data-' + nome) || '';
      demo.setAttribute('data-fase', nome);
    }

    // Fora da tela a demonstração fica no estado final: o texto já no campo.
    function repousar() {
      campo.classList.remove('vazio');
      spans.forEach(function (sp) { sp.classList.add('mostra'); });
      if (mini) mini.estado('repouso');
      rotulo('pronto');
    }
    repousar();

    function ciclo() {
      if (!ativo) return;
      campo.classList.add('vazio');
      spans.forEach(function (s) { s.classList.remove('mostra'); });
      if (mini) mini.estado('repouso');
      rotulo('toque');
      depois(1500, function () {
        tempo.textContent = '0:00';
        if (mini) mini.estado('ouvindo');
        rotulo('ouvindo');
        for (var i = 1; i <= 3; i++) {
          (function (seg) { depois(seg * 1000, function () { tempo.textContent = '0:0' + seg; }); })(i);
        }
        depois(3400, function () {
          if (mini) mini.estado('transcrevendo');
          rotulo('transcrevendo');
          depois(1200, function () {
            if (mini) mini.estado('repouso');
            rotulo('pronto');
            campo.classList.remove('vazio');
            void texto.offsetWidth; // o texto volta ao layout antes de as palavras surgirem
            spans.forEach(function (sp) { sp.classList.add('mostra'); });
            depois(4200, ciclo);
          });
        });
      });
    }

    if ('IntersectionObserver' in window) {
      new IntersectionObserver(function (entradas) {
        var visivel = entradas[0].isIntersecting;
        if (visivel && !ativo) { ativo = true; ciclo(); }
        else if (!visivel && ativo) { ativo = false; limpar(); repousar(); }
      }, { threshold: 0.35 }).observe(demo);
    }
  }
})();
