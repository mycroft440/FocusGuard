# setup_antigravity.ps1
# Este script configura o ambiente otimizado para agentes de IA em qualquer repositório.

$agentsPath = Join-Path (Get-Location) ".agents"
$workflowsPath = Join-Path $agentsPath "workflows"

if (-not (Test-Path $agentsPath)) {
    New-Item -ItemType Directory -Path $agentsPath | Out-Null
    Write-Host "Pasta .agents criada." -ForegroundColor Cyan
}

if (-not (Test-Path $workflowsPath)) {
    New-Item -ItemType Directory -Path $workflowsPath | Out-Null
}

# 1. Gerar Project Map Inicial
$projectMapContent = @"
# Project Map (Gerado Automaticamente)

## Estrutura de Diretórios
$(Get-ChildItem -Directory -Depth 1 | Select-Object -ExpandProperty Name | ForEach-Object { "- **$_**" })

## Padrões de Design
- Definir padrões aqui...

---
*Dica para Agentes: Este mapa foi gerado automaticamente. Atualize-o com responsabilidades específicas.*
"@

Set-Content -Path (Join-Path $agentsPath "project_map.md") -Value $projectMapContent
Write-Host "Project Map inicial gerado." -ForegroundColor Green

# 2. Copiar Token Savings Guide (Universal)
$tokenSavingsContent = @"
# Guia de Economia de Tokens (Universal)

1. **Pesquisa**: Use grep com filtros de extensão.
2. **Leitura**: Use line ranges (StartLine/EndLine).
3. **Edição**: Batch de edições com multi_replace_file_content.
4. **Contexto**: Consulte sempre o .agents/project_map.md.
"@

Set-Content -Path (Join-Path $agentsPath "token_savings.md") -Value $tokenSavingsContent
Write-Host "Guia de economia de tokens adicionado." -ForegroundColor Green

# 3. Criar EditorConfig básico se não existir
if (-not (Test-Path ".editorconfig")) {
    $editorConfigContent = @"
root = true

[*]
indent_style = space
indent_size = 4
charset = utf-8
trim_trailing_whitespace = true
insert_final_newline = true
"@
    Set-Content -Path ".editorconfig" -Value $editorConfigContent
    Write-Host ".editorconfig básico criado." -ForegroundColor Yellow
}

Write-Host "`nAmbiente Antigravity configurado com sucesso! 🚀" -ForegroundColor White -BackgroundColor DarkBlue
