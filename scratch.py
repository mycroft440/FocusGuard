import re

def get_matches_robust(text: str, needle: str, is_fuzzy: bool):
    if not needle: return []
    
    if is_fuzzy:
        tokens = [t for t in re.split(r'\s+', needle.strip()) if t]
        if not tokens: return []
        pattern = r'\s+'.join(re.escape(t) for t in tokens)
    else:
        pattern = re.escape(needle).replace(r'\r\n', r'\n').replace(r'\n', r'\r?\n')
    
    print("Pattern:", pattern)
    return list(re.finditer(pattern, text, flags=re.MULTILINE))

with open('app/src/main/java/com/focusguard/ui/PomodoroLockActivity.kt', 'r', encoding='utf-8') as f:
    text = f.read()

needle = "import android.view.KeyEvent\r\nimport android.view.WindowManager"
matches = get_matches_robust(text, needle, True)
print("Matches:", matches)
