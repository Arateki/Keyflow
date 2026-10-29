# 2026-10-29 — Galeria da Voz com os motores locais (FAL-T7)

As rotas da aba Voz usam `ModelCatalog.VOICE`: VITS com o nome português e Supertonic 3. Instalação, seleção e progresso são instantâneos em memória; a galeria não baixa pesos nem liga o processo de síntese. O aceite pendente desenha o próprio `VoiceLicenseDialog`, com a licença integral do asset, sem registrar aceite nem iniciar download. As conexões de Google e ElevenLabs usam credenciais fictícias do adaptador da galeria.

`config-fala-testar` enquadra o fim do painel após a medição do scroll, mantendo a frase padrão, o botão Testar e os tempos vazios. O parâmetro de enquadramento é desligado por padrão. O teste instrumentado confere que abrir esse estado não pede motor, não sintetiza e não reproduz áudio. FUX-08 exclui o estado tocando dos goldens.

A aprovação visual fica restrita a `config-fala*.png`. O restante da galeria é capturado e comparado, e seus goldens são conferidos por SHA-256 para preservar os bytes das outras abas. Resultados e contagens estão no relatório da equipe.
