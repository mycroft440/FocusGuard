# Padrão Nuclear: Diretrizes Globais de Qualidade

Este documento define o nível de excelência exigido para qualquer projeto neste ambiente.

## 1. Segurança em Primeiro Lugar (Rigor Nuclear)
- **Bloqueio Inquebrável**: Se o app é de bloqueio, ele não deve ter brechas. Use permissões de administrador de dispositivo e acessibilidade.
- **Criptografia**: Dados sensíveis (senhas, configurações) devem usar `EncryptedSharedPreferences` ou similares.
- **Prevenção de Intrusos**: Implemente travas que detectem tentativas de burlar o sistema.

## 2. Interface e Experiência (UI Fluida)
- **Aesthetics Matter**: Use Material 3, cores harmônicas e Dark Mode por padrão.
- **Animações**: Use `AnimatedVisibility` e transições suaves. O app deve parecer "vivo".
- **Feedback Visual**: Toques, carregamentos e erros devem ter feedback visual claro.

## 3. Código e Arquitetura (Limpeza Absoluta)
- **Modularização**: Separe UI de Lógica de Negócio (Managers/ViewModels).
- **Sem Placeholders**: Use ferramentas de geração de imagem ou ativos reais. Nunca use "lorem ipsum" ou botões genéricos.
- **Documentação Local**: Cada módulo deve ser documentado na pasta `.agents/`.

## 4. Eficiência do Agente (Economia de Tokens)
- **Foco Cirúrgico**: Resolva um problema por vez.
- **Exploração Otimizada**: Não vasculhe pastas sem necessidade.
- **Planos Detalhados**: Sempre crie um plano antes de executar mudanças complexas.

---
*Assinado: O Usuário (Foco Máximo e Qualidade Extrema)*
