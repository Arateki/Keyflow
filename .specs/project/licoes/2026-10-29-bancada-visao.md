# 2026-10-29 — Bancada de Visão

- O executor recebe imagem tipada por referência ao corpus ou bytes efêmeros e entrega texto pelo reconhecedor, sem depender da tela. OCR local usa o serviço isolado; modelos de visão e provedores usam o motor selecionado.
- O corpus v1 inclui dez imagens de texto e dez de objetos geradas com semente fixa, hash e licença no manifesto. OCR usa CER; objetos usa rótulos canônicos com sinônimos em português e inglês, precisão, revocação e F1.
- No emulador x86_64, PaddleOCR leu três imagens (ocr-pt-01 a 03) com CER 0,0 em cada. A prova opt-in do Gemma foi ignorada pela guarda de recursos: `/data` tinha 2.356.044 KiB livres, menos que os 3.988.147.712 bytes exigidos pelo modelo mais margem; não há medição real de objetos nessa execução.
- A faixa preserva o teste rápido e o consentimento antes de enviar imagens à nuvem. As capturas claro/escuro da Visão foram revistas; as demais telas conservam seus goldens.
