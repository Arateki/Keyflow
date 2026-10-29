# Bancada: integração com visão e foco no teste

A main passou a ter pedidos com imagem e cancelamento por ficha durante o desenvolvimento da bancada. A integração preserva o cancelamento, o cache privado de imagem e o bloqueio de redirecionamento das imagens; as opções seguem junto no AIDL e as métricas voltam antes do término.

No CI inicial da integração, `VisaoPanelTest#aChegadaTardiaDasSettingsConfirmadasPreservaOTesteEmAndamento` falhou duas vezes antes de testar a chegada tardia: a seleção ainda sem foco foi perdida e digitar `b` produziu `bhttps://a.test/v1`. O helper passa a focar o campo antes de selecionar o trecho. Isso mantém o teste exercitando edição pendente sem Done nem desfoque; não muda a interface.
