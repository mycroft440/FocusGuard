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
     links HTTPS, além de uma lista de compatibilidade para os navegadores mais
     usados (Firefox, Brave, Samsung Internet, Opera, Vivaldi e DuckDuckGo).
   - Observa alterações da janela, do conteúdo e do texto da barra de endereço.
   - Localiza a barra por ids do pacote, descrição acessível ou input do tipo
     URI; texto comum da página não é interpretado como endereço.
   - Ao detectar um domínio bloqueado, sai imediatamente do navegador e mostra
     a tela de bloqueio.

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
- [Política URLBlocklist do Microsoft Edge](https://learn.microsoft.com/pt-br/deployedge/microsoft-edge-policies/urlblocklist)
- [Política InPrivate do Microsoft Edge](https://learn.microsoft.com/pt-br/deployedge/microsoft-edge-policies/inprivatemodeavailability)
- [Filtros DNS gratuitos do CleanBrowsing](https://cleanbrowsing.org/filters)
