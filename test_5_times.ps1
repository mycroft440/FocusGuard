Write-Host "Iniciando a bateria de 5 testes rigorosos (Opção Nuclear)..."
for ($i=1; $i -le 5; $i++) {
    Write-Host "====== TESTE $i DE 5 ======"
    . .\gradlew.bat clean assembleDebug --stacktrace
    Write-Host "Código de saída da tentativa $i : $LASTEXITCODE"
}
