# Plano de execução — 27/08/2026

## Implementado nesta rodada

- Modo foco: botão **Iniciar** fixo na parte inferior e conteúdo superior rolável.
- Modo foco: sugestões/atalhos de duração removidos; duração centralizada no slider.
- Modo foco: **Tela cinza** reduzida a título + chave.
- Modo foco: **Como funciona** abre um card sobre a tela e qualquer toque fecha o card.
- Limites de uso: fluxo antigo de horas passou a aceitar **horas + minutos** e persiste o total em minutos para apps e sites.
- Bloqueio de sites: usa o pacote já resolvido do evento, inclusive em `TYPE_WINDOWS_CHANGED`.
- Bloqueio de sites: todos os handlers HTTPS são detectados e navegadores desconhecidos podem ser promovidos pela barra de URL acessível.
- Bloqueio de sites: debounce removido e tentativas repetidas deixaram de ser ignoradas por cooldown.
- Limites de sites: pulso de contabilização reduzido de 5 s para 1 s.

## Limite técnico documentado

Sem VPN/proxy/extensão, Android não permite que um app comum leia HTTPS de um navegador que esconda completamente a URL da acessibilidade. Chrome/Edge em Device Owner continuam cobertos adicionalmente por política nativa `URLBlocklist`.

## Próximo bloco

- Revisar atualização contínua e dimensões do widget Pomodoro.
- Validar manualmente Chrome, Edge, Firefox, Brave, Samsung Internet, Opera, Vivaldi, DuckDuckGo e pelo menos um navegador não listado.

## Widget Pomodoro — revisão final

- Relógio do widget passa a atualizar quando o minuto visível muda e nas trocas de fase.
- A atualização é acionada pelo serviço foreground já existente, sem depender do `updatePeriodMillis` de 30 minutos do Android.
- Ao encerrar o plano, o widget é atualizado imediatamente para o estado inativo.
- Altura mínima do widget foi alinhada ao conteúdo real para evitar corte em launchers redimensionáveis.
- Controles do widget ficaram explicitamente focáveis/clicáveis.
- O espaçamento de letras do rótulo do relógio agora é realmente desenhado, em vez de usar um no-op.
