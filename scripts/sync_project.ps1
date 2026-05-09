# FocusGuard - Smart Sync Script
# Versão: 1.0.0
# Descrição: Automatiza o fluxo Git -> Build -> Push diretamente na main.

param (
    [string]$CommitMessage = "Agent: Update and synchronization"
)

function Write-Header ($msg) {
    Write-Host "`n=== $msg ===" -ForegroundColor Cyan
}

function Write-Success ($msg) {
    Write-Host "[OK] $msg" -ForegroundColor Green
}

function Write-Error ($msg) {
    Write-Host "[ERROR] $msg" -ForegroundColor Red
    exit 1
}

Write-Header "Iniciando Sincronização Inteligente"

# 1. Pull de segurança
Write-Host "Sincronizando com o repositório remoto..."
git pull origin main
if ($LASTEXITCODE -ne 0) { Write-Error "Falha ao realizar pull do GitHub." }
Write-Success "Local atualizado."

# 2. Build Local
Write-Header "Validando Código (Build Local)"
./gradlew assembleDebug
if ($LASTEXITCODE -ne 0) { Write-Error "O build local falhou. Corrija os erros antes de enviar." }
Write-Success "Código compilado com sucesso."

# 3. Commit e Push
Write-Header "Enviando Mudanças para Main"
git add .
$status = git status --porcelain
if (-not $status) {
    Write-Host "Nenhuma alteração para commitar." -ForegroundColor Yellow
} else {
    git commit -m "$CommitMessage"
    git push origin main
    if ($LASTEXITCODE -ne 0) { Write-Error "Falha ao enviar para o GitHub." }
    Write-Success "Repositório atualizado com sucesso!"
}

Write-Header "Processo Concluído"
