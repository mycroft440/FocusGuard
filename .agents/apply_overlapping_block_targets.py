from pathlib import Path

path = Path("app/src/main/java/com/focusguard/ui/CreateSessionActivity.kt")
text = path.read_text(encoding="utf-8")

old = '''            0 -> AppSelectionStep(
                kinds = kinds,
                // Voltar da segunda página reabre esta com a escolha intacta, em
'''
new = '''            0 -> AppSelectionStep(
                kinds = kinds,
                // Sessões são camadas independentes. Um alvo já protegido por
                // outro bloqueio continua selecionável para receber uma nova
                // camada (por exemplo, período diário + bloqueio TIME contínuo).
                allowCompatibleProtection = true,
                // Voltar da segunda página reabre esta com a escolha intacta, em
'''

if text.count(old) != 1:
    raise SystemExit(f"expected exactly one CreateSessionWizard AppSelectionStep anchor, found {text.count(old)}")

path.write_text(text.replace(old, new, 1), encoding="utf-8")
