# LiteRT-LM: métricas e repetição na CPU x86_64

Em 2026-10-29, `pool instrumentar` executou no emulador API 34 x86_64 com KVM, CPU de 2 threads e Qwen3 0.6B (arquivo de 613722264 bytes, SHA-256 `c5f39b4191926786c3e6668b62af4d6eb15248dfc7a5348252a9b406f33f2108`). O teste é opt-in, baixa o peso oficial e apaga a cópia depois.

Sem `ExperimentalFlags.enableBenchmark`, após gerar, `getBenchmarkInfo()` lançou `LiteRtLmJniException`; com a flag ligada antes de inicializar o motor, devolveu métricas. Três conversas novas com temperatura 0, top-k 40, top-p 1, semente 42 e teto 32 responderam `oi` nas três execuções; cada uma informou 19 tokens de prefill e 2 de decode. Isso prova apenas este prompt, peso e ambiente, não determinismo universal. A suite instrumentada que incluiu a prova passou 71/71.

`SamplerConfig` exige os quatro valores: quando a bancada pede só temperatura ou semente, o app completa os demais com os valores iniciais definidos em model-params e registra também esses valores aplicados. Nulo em todos mantém o sampler padrão do motor, cujo valor numérico não é inventado. RAM é medida no processo local por `VmHWM` quando o kernel permite zerá-lo, ou por amostras de `VmRSS` a cada 100 ms; a amostragem pode perder picos mais curtos.
