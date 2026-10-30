# termlib — origem e alterações do KeyFlow

- Repositório: https://github.com/connectbot/termlib
- Tag: `0.3.14`; commit fixado: `0bd42f06e3ae88e5fc9e2f505a77b443cea26865`.
- `UPSTREAM_SHA256SUMS` registra cada arquivo original copiado, antes dos patches.
- Incluídos: `lib/src/main/`, `lib/consumer-rules.pro` e `LICENSE`; os plugins de publicação,
  documentação, benchmark e cobertura do projeto upstream não fazem parte deste módulo.
- Código Kotlin e ponte C++: Apache-2.0, Copyright 2025 Kenny Root; `LICENSE` preservado.
- libvterm: MIT, Copyright 2008 Paul Evans; `src/main/cpp/libvterm/LICENSE` preservado.
- wcwidth incorporado ao `libvterm/src/unicode.c`: permissão expressa de Markus Kuhn, 2007,
  preservada em `licenses/Permissiva_wcwidth.txt`.
- Runtime C++ do NDK: libc++, libc++abi, libunwind e compiler-rt, Apache-2.0 com exceção LLVM
  (avisos completos em `licenses/`). As bibliotecas de sistema Android não são redistribuídas.

## Patches

1. `maxScrollbackLines` configurável na fábrica, intervalo 0..100.000, padrão upstream de 1.000;
   contador público de linhas retidas para medir orçamento sem refletir estado privado.
2. Modo de mouse publicado como `StateFlow` a partir de `VTERM_PROP_MOUSE`; métodos de movimento
   e botão usam `vterm_mouse_move/button` no mesmo contrato de locks/callbacks do teclado.
   Não se duplicam o parser de modos nem a codificação de eventos no Kotlin.
3. Nome nativo `libtermlib.so`. O CMake só roda no CI; o módulo Android consome os binários
   de três ABIs, com SHA-256 fixado, preparados pelo build do app.
4. Build do módulo alinhado ao toolchain do app, sem NDK, plugins ou testes de desktop do upstream.

Sixel, DA1, política de imagens e callbacks de entrada não foram alterados nestes patches.
O roteamento de gestos da tela para os métodos de mouse fica na integração da CMP-T13.

## Atualização

Confira a licença de cada parte, compare os arquivos com o manifesto upstream, aplique apenas os
patches necessários, execute `termlib-jni.yml`, confira o release imutável e só então atualize o hash
fixado no app e os testes. Nunca substitua um asset publicado mantendo a mesma versão.

Os textos dos quatro runtimes vieram de `llvm/llvm-project`, tag `llvmorg-20.1.8`,
`{libcxx,libcxxabi,libunwind,compiler-rt}/LICENSE.TXT`; os textos completos preservam também
os avisos históricos UIUC/MIT. O NDK 29 usa clang-r563880c (fonte oficial: changelog r29);
o CI guarda `clang_source_info.md` do NDK realmente usado. A referência a GPLv2 na exceção LLVM
é uma cláusula de compatibilidade, não muda a licença desses runtimes para GPL.
Fontes: https://developer.android.com/ndk/guides/cpp-support e
https://github.com/android/ndk/wiki/Changelog-r29.
