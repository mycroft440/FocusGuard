# Bloqueio de sites sem VPN

O FocusGuard não cria uma VPN local e não intercepta o tráfego de rede. O
bloqueio combina as duas camadas nativas disponíveis no Android:

1. **Política gerenciada do navegador (Device Owner)**
   - Aplica `URLBlocklist` ao Chrome e ao Microsoft Edge instalados.
   - Respeita o limite oficial de 1.000 filtros por navegador e mantém as
     regras originais antes de acrescentar aliases conhecidos.
   - No Edge para Android, a política está disponível a partir da versão 30 do
     navegador; não se trata do nível 30 da API do Android.
   - Desativa a navegação privada enquanto houver uma lista ativa.
   - O próprio navegador rejeita a navegação antes de renderizar a página.
   - A política é reaplicada quando um navegador é instalado ou atualizado.

2. **Serviço de acessibilidade**
   - É a camada comum para qualquer navegador instalado que declare suporte a
     links HTTPS. Um navegador desconhecido também pode ser reconhecido quando
     expõe um id forte de omnibox sob o próprio pacote; um campo URI sem essa
     prova só é aceito se o pacote já foi confirmado como handler HTTPS.
   - Observa alterações da janela, do conteúdo e do texto da barra de endereço.
   - Localiza a barra por ids estritos usados por Chromium, Gecko/Firefox,
     Samsung Internet, Via e navegadores compactos. Inputs URI, ids fracos e
     descrições localizadas ajudam apenas a observar a URL de um handler HTTPS
     confirmado: nunca autorizam foco, troca de texto, submissão ou fechamento.
   - Sob uma cortina opaca e consumidora de toque, tenta fechar a guia apenas
     quando a árvore atual publica exatamente `tab_switcher_button` e
     `close_tab` sob o pacote do navegador. Essa capacidade atende Chrome e
     forks Chromium compatíveis sem uma allowlist de marcas.
   - Um clique em `close_tab` só conta como fechamento depois de um evento
     posterior do navegador e do desaparecimento comprovado da superfície
     bloqueada. Após essa confirmação, abre um novo documento limpo do Google
     no mesmo navegador; a guia sobrevivente jamais é sobrescrita.
   - Se o fechamento não estiver disponível, substitui a própria guia bloqueada
     por uma raiz segura do Google somente quando há um único nó forte, visível,
     editável e com cada ação anunciada. Em Android 8 a 10 (API 26–29), ou quando
     não existe submissão certificável, a cortina permanece e o fluxo evacua para
     HOME. `ACTION_VIEW` só é permitido depois de um fechamento já confirmado.
   - Um clique aceito mas não confirmado nunca autoriza reescrever a guia que
     restou. O fluxo falha fechado em HOME; uma superfície que reapareça será
     avaliada como uma nova navegação.

## Regras de domínio

- `example.com` também bloqueia `www.example.com` e qualquer subdomínio, como
  `news.example.com`.
- Domínios parecidos, como `notexample.com` ou `example.com.evil.test`, não
  correspondem à regra.
- Esquema, credenciais, porta, caminho, query e fragmento são removidos antes
  da comparação de domínio. A categoria Pornografia tem uma verificação
  adicional e deliberada dos parâmetros de busca do Google.
- Domínios internacionais são convertidos para IDN ASCII (Punycode).
- Endereços IPv4 e IPv6 literais são aceitos.
- Limites de uso configurados para um domínio contabilizam seus subdomínios.

## Categoria Pornografia

- O seletor mostra uma única opção, **Pornografia**, persistida internamente
  como `category:pornography`.
- Durante a fiscalização, essa categoria ativa em conjunto as palavras de
  domínio `porn`, `xxx`, `sex` e `xvideos` e a lista local de domínios adultos.
- As mesmas palavras são verificadas na consulta `q=` do Google e Google
  Imagens, inclusive em domínios regionais, parâmetros fora de ordem e texto
  percentualmente codificado. Variações iniciadas pelo termo, como
  `pornografia` e `sexual`, também correspondem; palavras como `Essex` não.
- No modo estrito da categoria, o **Google Imagens inteiro fica bloqueado**,
  mesmo para uma consulta segura. A cobertura inclui `images.google.*`, Lens,
  `/imghp`, `/imgres`, busca reversa e os modos `udm=2` e `tbm=isch`. A busca
  web comum do Google continua disponível quando não contém um termo proibido.
- Em qualquer navegador que exponha sua interface à acessibilidade, a consulta
  é interrompida enquanto ainda está sendo digitada na barra de endereço. Em
  uma página do Google confirmada pela URL, o campo de busca editável também é
  fiscalizado sem varrer texto comum da página.
- A categoria continua aparecendo como um único item em sessões, limites e
  telas de detalhes; as regras internas não são gravadas separadamente.
- Em Device Owner, a lista local também é enviada à `URLBlocklist` do Chrome e
  Edge, junto de filtros preventivos para todas as superfícies do Google
  Imagens e para consultas que começam com cada palavra da categoria. Enquanto
  a categoria estiver efetivamente bloqueando, o FocusGuard usa o CleanBrowsing
  Family Filter, bloqueia alterações de DNS/VPN e desativa o DNS-over-HTTPS
  próprio desses navegadores. Ao fim do bloqueio, a configuração de Private DNS
  que existia antes é restaurada.
- O Family Filter acrescenta classificação atualizada de conteúdo adulto,
  bloqueio de sites mistos e de proxies/VPN e SafeSearch em mecanismos de busca
  e YouTube. A lista local permanece como fallback quando o DNS gerenciado não
  está disponível.

## Limitações reais do Android

Sem VPN, proxy, extensão do navegador ou filtro DNS externo, um aplicativo não
tem uma API pública para inspecionar todo o tráfego HTTPS de todos os apps. A
camada de acessibilidade depende de o navegador expor a barra de endereço. Um
WebView embutido que esconda completamente a URL não pode ser identificado com
garantia. Por isso, “todos os navegadores” significa todos os navegadores HTTPS
detectados que publiquem a URL ou seus campos à acessibilidade; não há garantia
de zero requisição de rede em um navegador que esconda esses dados. Em aparelhos
Device Owner, Chrome e Edge recebem a camada preventiva adicional por
`URLBlocklist`.

A neutralização rápida também é adaptativa: interfaces proprietárias que não
publiquem os ids e as ações esperados falham de modo fechado e são evacuadas para
HOME. O Android não oferece uma API pública universal para fechar a guia atual
ou remover de forma portátil a tarefa do navegador da tela de Recentes. Assim,
o FocusGuard não promete manipular menus proprietários nem apagar a tarefa de
Recentes; promete não tocar uma guia ambígua e não liberar a cortina sobre uma
superfície ainda não confirmada.

Isso também explica cartões como os exibidos no seletor de abas e na tela de
Recentes: quando o navegador publica a ação exata, o FocusGuard confirma que a
guia bloqueada desapareceu antes de prosseguir. Se a interface não publicar essa
capacidade, o Android não permite apagá-la de maneira universal; nesse caso a
página fica coberta durante a evacuação, mas uma miniatura antiga ainda pode ser
mantida pelo próprio navegador ou pelo sistema.

O DNS familiar impede a resolução dos domínios adultos classificados em todos
os navegadores, mas DNS enxerga apenas o host. Ele não consegue ler a consulta
`q=` dentro de HTTPS sem interceptar e descriptografar o tráfego. A consulta do
Google é, portanto, coberta pela interface acessível e, onde disponível, pela
política nativa do navegador.

O filtro DNS também pode ser habilitado como blindagem global 24/7 em um
aparelho Device Owner. Nesse modo, o FocusGuard reaplica o host familiar após
reinícios e impede alterações manuais do Private DNS fora da janela de
manutenção. Chrome e Edge gerenciados também são obrigados a usar o resolvedor
do sistema. O filtro global só pode ser desativado durante manutenção
autenticada e não transforma o FocusGuard em VPN.

## Verificação manual recomendada

Com uma sessão ativa bloqueando `example.com`, validar:

- `https://example.com` e `https://a.example.com`;
- uma URL com letras maiúsculas, porta e caminho;
- navegação por link, digitação direta, recarregamento e troca de aba;
- modo privado;
- Chrome/Edge com e sem Device Owner;
- Google Imagens com `q=porn`, `q=xxx`, `q=sex` e `q=xvideos`, digitado pela
  barra e pelo campo da página;
- Google Imagens com uma consulta segura, que também deve ser bloqueada;
- Chrome, Firefox, Brave, Samsung Internet e ao menos outro navegador instalado;
- Via e qualquer navegador adicional configurado como handler HTTPS;
- Android API 26, 29 e 30 ou superior, incluindo o fallback HOME nas APIs 26–29;
- várias abas abertas, duas abas com a mesma URL e menu de abas já visível;
- confirmação de que um clique de fechar recusado ou inconclusivo não altera a
  aba sobrevivente;
- uma busca web comum como `Essex Inglaterra`, que deve permanecer liberada;
- fim da sessão e remoção imediata da política;
- reinício do aparelho durante uma sessão ativa.

## Referências oficiais

- [Serviços de acessibilidade no Android](https://developer.android.com/guide/topics/ui/accessibility/service)
- [DevicePolicyManager](https://developer.android.com/reference/android/app/admin/DevicePolicyManager)
- [IDNA/UTS #46 no Android](https://developer.android.com/reference/android/icu/text/IDNA)
- [Padrão WHATWG de parsing de hosts e endereços IP](https://url.spec.whatwg.org/#host-parsing)
- [Política URLBlocklist do Chrome](https://chromeenterprise.google/policies/url-blocklist/)
- [Política de modo anônimo do Chrome](https://chromeenterprise.google/policies/incognito-mode-availability/)
- [Formato dos filtros de URL do Chrome](https://support.google.com/chrome/a/answer/9942583?hl=pt-BR)
- [Google Imagens](https://images.google.com/)
- [Domínios regionais oficiais do Google](https://www.google.com/supported_domains)
- [Política URLBlocklist do Microsoft Edge](https://learn.microsoft.com/pt-br/deployedge/microsoft-edge-policies/urlblocklist)
- [Política InPrivate do Microsoft Edge](https://learn.microsoft.com/pt-br/deployedge/microsoft-edge-policies/inprivatemodeavailability)
- [Filtros DNS gratuitos do CleanBrowsing](https://cleanbrowsing.org/filters)
