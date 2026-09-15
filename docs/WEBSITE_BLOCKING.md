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
   - Localiza a barra por ids fortes usados por Chromium, Gecko/Firefox,
     Samsung Internet, Via e navegadores compactos. Para handlers HTTPS
     realmente confirmados pelo `PackageManager`, também aceita como capacidade
     de ação um único campo nativo do próprio pacote/janela que seja editável,
     declare `textUri` e anuncie explicitamente a ação solicitada. Ids fracos e
     descrições localizadas continuam servindo apenas para observação.
   - Sob uma cortina opaca e consumidora de toque, a neutralização substitui a
     URL na própria aba bloqueada e a envia somente quando `ACTION_SET_TEXT` e
     `ACTION_IME_ENTER` podem ser certificados no mesmo campo/janela. A cortina
     só é liberada depois de um evento posterior confirmar uma raiz segura do
     Google.
   - Se a primeira tentativa coincidir com uma animação/foco transitório do
     editor, o FocusGuard restaura a superfície bloqueada exata e tenta uma vez
     de novo com uma política de capacidade nova. A repetição nunca reutiliza
     handles de nós de acessibilidade antigos.
   - O fluxo de site não fecha guia, não fecha navegador, não abre um segundo
     documento por `ACTION_VIEW` e não evacua para HOME. Em Android 8 a 10
     (API 26–29), onde `ACTION_IME_ENTER` ainda não existe, o FocusGuard mantém
     a detecção e a cortina de bloqueio, mas não finge conseguir certificar uma
     submissão universal na mesma aba.

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
publiquem um editor certificável simplesmente não recebem automação destrutiva. O
Android não oferece uma API pública universal para fechar a guia atual, escolher a
aba exata ou remover de forma portátil a tarefa do navegador da tela de Recentes.
Por isso o FocusGuard prefere preservar navegador e abas, falha fechado diante de
campos ambíguos e só confirma o redirecionamento quando a própria superfície
acessível prova que a raiz segura foi carregada.

Cartões exibidos no seletor de abas e na tela de Recentes continuam sob controle do
navegador/sistema. O FocusGuard não tenta apagá-los por menus proprietários e não
usa fechamento de aba como substituto de redirecionamento; essa escolha evita
destruir uma aba sobrevivente quando a árvore de acessibilidade é incompleta.

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
- Android API 26, 29 e 30 ou superior, confirmando que APIs 26–29 não
  fecham o navegador nem usam HOME para simular uma submissão inexistente;
- várias abas abertas e duas abas com a mesma URL, confirmando que nenhuma aba
  nova é criada e nenhuma aba existente é fechada pelo redirecionamento;
- um navegador HTTPS adicional cujo editor use id nativo desconhecido, mas
  exponha `textUri`, `ACTION_SET_TEXT` e `ACTION_IME_ENTER`, além de um campo
  de página semelhante para validar que o fallback semântico não o toca;
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


## Navegadores sem URL observável (fail-closed)

Quando uma proteção de site ou um limite rígido exige conhecer a URL atual, o FocusGuard primeiro classifica a janela atual. Somente conteúdo web confirmado sem uma barra observável inicia a tolerância de 1,5 segundo. Ao terminar esse prazo, uma nova leitura precisa confirmar conteúdo web sem barra no mesmo pacote e janela antes de exibir a proteção. Uma URL identificada que corresponda a uma regra continua sendo bloqueada imediatamente, sem aguardar esse prazo.

Menus, configurações, seletor de abas, favoritos, histórico, downloads e nova aba são interfaces do navegador, não evidência de site bloqueado. A classificação usa estrutura nativa fora dos contêineres web e não reutiliza uma exceção global de outro menu, aba ou navegador. Janela de teclado, diálogo do sistema, árvore ausente ou incompleta são estados inconclusivos: isoladamente não autorizam bloquear o navegador inteiro. Eventos posteriores retomam a identificação.

A busca semântica não percorre documentos WebView/ContentView/GeckoView. Mesmo nas buscas diretas por id, um nó dentro de conteúdo web não pode ser usado como barra de endereço, editor ou botão de envio. Isso também evita consumir todo o limite de busca no conteúdo de uma página antes de encontrar a barra nativa.

Chrome, Samsung Internet, Via (pacotes `mark.via` e `mark.via.gp`) e variantes Yandex têm ativação por clique como primeira tentativa quando não existe preferência aprendida. Os ids de omnibox Yandex e rótulos de endereço em campos nativos URI também participam da seleção. O app continua exigindo ações anunciadas pelo editor e confirmação posterior do destino; reconhecer o pacote não comprova suporte universal a redirecionamento. A disponibilidade real depende da versão do navegador e da árvore que ele publica à acessibilidade.

O mesmo princípio vale para a neutralização de uma página já identificada como bloqueada: se a reescrita na mesma aba ou a confirmação do destino seguro não puder ser certificada, o FocusGuard mantém o fluxo fail-closed e mostra a superfície de bloqueio; ele não devolve a página bloqueada ao usuário.


## Compatibilidade com DuckDuckGo Android

O DuckDuckGo Android usa o pacote `com.duckduckgo.mobile.android` e, conforme a geração da interface, pode expor a barra como `omnibarTextInput` ou como o campo nativo `inputField`. O FocusGuard trata `omnibarTextInput` como uma barra que pode exigir `ACTION_CLICK` antes de aceitar `ACTION_SET_TEXT`. O id genérico `inputField` só é autorizado para automação no pacote oficial do DuckDuckGo e apenas quando o próprio nó se identifica semanticamente como campo de endereço (por exemplo, `Search or enter address` / `Pesquisar ou inserir endereço`); campos de Duck.ai com o mesmo id continuam rejeitados.

Se a neutralização na mesma aba ainda falhar, o handoff fail-closed reutiliza a geração da cortina já visível. Isso evita destacar/desanexar e recriar a cortina durante a falha, reduzindo o efeito de tela de bloqueio piscando enquanto a superfície genérica segura assume o primeiro plano.


## Registro desta correção

Revisão estática do código, sem execução de testes, build ou validação em aparelho, conforme solicitado. A classificação distingue evidência nativa, conteúdo web e estado inconclusivo; ela não promete inspecionar superfícies que o navegador não expõe ao Android.
