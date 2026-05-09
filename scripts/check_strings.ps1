# FocusGuard - String Scanner
# Descrição: Procura por strings fixas (hardcoded) no código Kotlin que deveriam estar no strings.xml.

Write-Host "Iniciando varredura de strings fixas..." -ForegroundColor Cyan

$sourcePath = "app/src/main/java/com/focusguard"
$files = Get-ChildItem -Path $sourcePath -Filter "*.kt" -Recurse

$hardcodedCount = 0

foreach ($file in $files) {
    $lines = Get-Content $file.FullName
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        # Regex para encontrar strings entre aspas, ignorando imports, tags de log, chaves de SharedPreferences e IDs.
        # Captura strings com mais de 3 caracteres que parecem texto visível.
        if ($line -match '"([^"]{4,})"' -and $line -notmatch "import " -and $line -notmatch "Log\." -and $line -notmatch "TAG" -and $line -notmatch "R\.string") {
            $stringVal = $Matches[1]
            # Ignorar se for um ID de pacote, cor hexadecimal ou chave de banco de dados
            if ($stringVal -notmatch "^[a-z0-9_.]+$" -and $stringVal -notmatch "^#[0-9A-F]{6}$") {
                Write-Host "String detectada em $($file.Name):$($i+1) -> `"$stringVal`"" -ForegroundColor Yellow
                $hardcodedCount++
            }
        }
    }
}

if ($hardcodedCount -eq 0) {
    Write-Host "Nenhuma string fixa suspeita encontrada. Ótimo trabalho!" -ForegroundColor Green
} else {
    Write-Host "Total de strings fixas encontradas: $hardcodedCount" -ForegroundColor Red
    Write-Host "Recomendação: Mova-as para o arquivo strings.xml." -ForegroundColor Yellow
}
