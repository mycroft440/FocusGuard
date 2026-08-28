from pathlib import Path

p = Path("app/src/main/java/com/focusguard/ui/compose/screens/UsageLimitsScreen.kt")
s = p.read_text()
old = "                        appName = predefined.name,\n"
new = "                        appName = predefined.appName,\n"
count = s.count(old)
if count != 1:
    raise RuntimeError(f"preventive app label: expected 1 match, found {count}")
p.write_text(s.replace(old, new, 1))
print("Follow-up blocking hardening fix applied")
