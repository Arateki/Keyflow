/*
 * Orbe viva do KeyFlow, em Canvas 2D.
 *
 * Porta para a página o desenho Ateliê do app (app/src/main/java/com/keyflow/ui/orb/VoiceOrb.kt):
 * blobPath (110 pontos, raio modulado por três senoides), drawLayer (degradê radial deslocado para
 * cima e à esquerda), orbBaseParams e orbLevel por estado, e os três anéis que se expandem ao ouvir.
 * A diferença: aqui os estados se misturam com suavidade, e a fase é integrada no tempo para que a
 * troca de velocidade entre estados não dê saltos no desenho.
 *
 * Uso: var orbe = KFOrbe.criar(canvas, { escala: 0.24 });
 *      orbe.estado('ouvindo');  orbe.mistura({ ouvindo: 0.6, repouso: 0.4 });  orbe.deslocar(8, -4);
 */
(function () {
  'use strict';

  var TAU = Math.PI * 2;

  // Amplitude e velocidade base por estado (orbBaseParams).
  var BASE = {
    repouso: { amp: 0.05, sp: 0.55 },
    ouvindo: { amp: 0.20, sp: 1.7 },
    transcrevendo: { amp: 0.11, sp: 1.15 }
  };

  // Nível (tamanho pulsante) por estado (orbLevel).
  function nivel(estado, t) {
    if (estado === 'repouso') return 0.55 + 0.08 * Math.sin(t * 0.002);
    if (estado === 'transcrevendo') {
      return 0.78 + 0.05 * Math.sin(t * 0.014) + 0.04 * Math.sin(t * 0.033 + 0.9);
    }
    return 0.60 + 0.40 * Math.abs(0.7 * Math.sin(t * 0.0024) + 0.3 * Math.sin(t * 0.0061 + 1.3));
  }

  function tracarBlob(ctx, cx, cy, r, amp, fase, seed) {
    var n = 110;
    ctx.beginPath();
    for (var i = 0; i <= n; i++) {
      var a = (i / n) * TAU;
      var d = 1 + amp * (
        Math.sin(a * 3 + fase * 0.0016 + seed) +
        0.5 * Math.sin(a * 5 - fase * 0.0021 + seed * 1.7) +
        0.3 * Math.sin(a * 2 + fase * 0.0027)
      );
      var x = cx + Math.cos(a) * r * d;
      var y = cy + Math.sin(a) * r * d;
      if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
    }
    ctx.closePath();
  }

  function camada(ctx, cx, cy, r, amp, fase, seed, c0, c1, alfa) {
    tracarBlob(ctx, cx, cy, r, amp, fase, seed);
    var gx = cx - r * 0.25;
    var gy = cy - r * 0.30;
    var g = ctx.createRadialGradient(gx, gy, 0, gx, gy, r * 1.25);
    g.addColorStop(0, c0);
    g.addColorStop(1, c1);
    ctx.globalAlpha = alfa;
    ctx.fillStyle = g;
    ctx.fill();
  }

  function criar(canvas, opcoes) {
    var op = opcoes || {};
    var ctx = canvas.getContext('2d');
    if (!ctx) return null;

    var escala = op.escala || 0.24;
    var realceAneis = op.realceAneis || 1;
    var meiaVida = op.suavidade || 280;
    var pesos = { repouso: 1, ouvindo: 0, transcrevendo: 0 };
    var alvo = { repouso: 1, ouvindo: 0, transcrevendo: 0 };
    var desloc = { x: 0, y: 0 };
    var alvoDesloc = { x: 0, y: 0 };
    var t = 0;
    var fase = 0;
    var ultimo = 0;
    var raf = 0;
    var naTela = true;
    var fixo = false;
    var dpr = 1;
    var w = 0;
    var h = 0;

    function medir() {
      dpr = Math.min(window.devicePixelRatio || 1, 2);
      var caixa = canvas.getBoundingClientRect();
      w = caixa.width;
      h = caixa.height;
      canvas.width = Math.max(1, Math.round(w * dpr));
      canvas.height = Math.max(1, Math.round(h * dpr));
    }

    function desenhar() {
      var amp = 0;
      var sp = 0;
      var lvl = 0;
      for (var e in pesos) {
        amp += pesos[e] * BASE[e].amp;
        sp += pesos[e] * BASE[e].sp;
        lvl += pesos[e] * nivel(e, t);
      }
      var ouvindo = pesos.ouvindo;

      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.clearRect(0, 0, w, h);
      var S = Math.min(w, h);
      var cx = w / 2 + desloc.x;
      var cy = h / 2 + desloc.y;
      var r = S * escala * (0.84 + 0.20 * lvl);

      // Anéis que se expandem: o visual de escuta (e de pausa) do app.
      if (ouvindo > 0.01) {
        ctx.strokeStyle = '#4E9683';
        ctx.lineWidth = 1.3;
        for (var k = 0; k < 3; k++) {
          var fr = ((t * 0.0006) + (k / 3)) % 1;
          ctx.globalAlpha = Math.min(1, 0.18 * realceAneis * (1 - fr) * ouvindo);
          ctx.beginPath();
          ctx.arc(cx, cy, r * (1.05 + fr * 0.95), 0, TAU);
          ctx.stroke();
        }
      }

      camada(ctx, cx, cy, r * 1.12, amp * 0.7, fase * 0.8, 5.0, 'rgba(126,184,168,0.4)', 'rgba(126,184,168,0)', 1);
      camada(ctx, cx, cy, r, amp * lvl, fase, 2.0, '#9AD0BF', '#4E9683', 0.96);
      camada(ctx, cx, cy, r * 0.66, amp * 0.7, fase * 1.35, 9.0, '#F4ECCF', '#BFE0D4', 0.95);

      ctx.globalAlpha = 0.30;
      ctx.fillStyle = '#fff';
      ctx.beginPath();
      ctx.arc(cx - r * 0.28, cy - r * 0.32, r * 0.16, 0, TAU);
      ctx.fill();
      ctx.globalAlpha = 1;
      return sp;
    }

    function quadro(agora) {
      var dt = ultimo ? Math.min(agora - ultimo, 64) : 16;
      ultimo = agora;
      t += dt;
      var k = 1 - Math.exp(-dt / meiaVida);
      for (var e in pesos) pesos[e] += (alvo[e] - pesos[e]) * k;
      var kd = 1 - Math.exp(-dt / 420);
      desloc.x += (alvoDesloc.x - desloc.x) * kd;
      desloc.y += (alvoDesloc.y - desloc.y) * kd;
      var sp = desenhar();
      fase += dt * sp;
      if (op.aoQuadro) op.aoQuadro(pesos, dt);
      raf = requestAnimationFrame(quadro);
    }

    function rodar() {
      if (raf || fixo || !naTela || document.hidden) return;
      ultimo = 0;
      raf = requestAnimationFrame(quadro);
    }

    function parar() {
      if (raf) cancelAnimationFrame(raf);
      raf = 0;
    }

    medir();
    desenhar(); // primeiro quadro igual ao SVG estático (t = 0, repouso): a troca não pisca

    if ('ResizeObserver' in window) {
      new ResizeObserver(function () { medir(); desenhar(); }).observe(canvas);
    }
    if ('IntersectionObserver' in window) {
      new IntersectionObserver(function (entradas) {
        naTela = entradas[0].isIntersecting;
        if (naTela) rodar(); else parar();
      }).observe(canvas);
    }
    document.addEventListener('visibilitychange', function () {
      if (document.hidden) parar(); else rodar();
    });
    rodar();

    return {
      estado: function (nome) {
        for (var e in alvo) alvo[e] = e === nome ? 1 : 0;
      },
      mistura: function (m) {
        for (var e in alvo) alvo[e] = m[e] || 0;
      },
      deslocar: function (x, y) {
        alvoDesloc.x = x;
        alvoDesloc.y = y;
      },
      parar: parar,
      rodar: rodar,
      // Quadro fixo e determinístico (imagem de prévia, testes): para a animação e desenha o estado no instante dado.
      fixar: function (tempo, nome) {
        parar();
        fixo = true;
        for (var e in pesos) pesos[e] = alvo[e] = e === nome ? 1 : 0;
        t = tempo;
        fase = tempo * BASE[nome].sp;
        desloc.x = desloc.y = 0;
        desenhar();
      }
    };
  }

  window.KFOrbe = { criar: criar };
})();
