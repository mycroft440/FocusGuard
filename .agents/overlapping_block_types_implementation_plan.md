# Seleção de alvos com proteções simultâneas

## Diagnóstico

- `ConfiguredBlockedTargets` marcava um alvo como indisponível globalmente quando ele tinha senha, limite e tempo. O seletor de sessões e o assistente unificado barravam a escolha antes de conhecer a nova proteção.
- O modo TIME persiste sessões independentes e aceita vários períodos para o mesmo app/site; a lista de complementos ainda removia alvos que tinham qualquer outra proteção.
- Senha e limite diário já conferem duplicatas no momento de escolher o modo; essa distinção deve continuar.

## Checklist

- [x] Inspecionar seletores, criação de sessões, hierarquia de proteção e testes existentes.
- [x] Remover a indisponibilidade global e permitir TIME adicional, preservando duplicatas de senha e limite diário.
- [x] Permitir complementos já protegidos por outro tipo de bloqueio.
- [x] Cobrir app e site com várias camadas e verificar seleção em cada modo.
- [ ] Revisar diff e executar os gates Android disponíveis.
