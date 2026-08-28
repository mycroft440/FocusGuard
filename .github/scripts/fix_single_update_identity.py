from pathlib import Path
import re

CERT = "e8da4209d0012052b052b280fa64becb788bf6929563ce54a0707cb8c3385157"


def sub1(pattern: str, repl: str, text: str, label: str, flags: int = 0) -> str:
    updated, count = re.subn(pattern, repl, text, count=1, flags=flags)
    if count != 1:
        raise RuntimeError(f"{label}: expected one match, got {count}")
    return updated


# Gradle: Release can only use the canonical production identity.
path = Path("app/build.gradle.kts")
text = path.read_text()
text = sub1(
    r'\n    val ciSigningEnabled = System\.getenv\("FOCUSGUARD_CI_SIGNING"\)\n\s*\?\.equals\("true", ignoreCase = true\) == true\n',
    '\n', text, 'ciSigningEnabled'
)
text = sub1(
    r'''            when \{\n\s*releaseSigningAvailable -> \{\n\s*signingConfig = signingConfigs\.getByName\("release"\)\n\s*\}\n\s*ciSigningEnabled -> \{.*?signingConfig = signingConfigs\.getByName\("debug"\)\n\s*\}\n\s*\}\n''',
    '''            if (releaseSigningAvailable) {
                signingConfig = signingConfigs.getByName("release")
            }
''',
    text, 'release CI fallback', re.S
)
path.write_text(text)

# Android CI: main artifacts require the production key and canonical package.
path = Path(".github/workflows/android-ci.yml")
text = path.read_text()
text = sub1(
    r'  build-artifacts:\n    name: Build and verify installable Android artifacts\n',
    "  build-artifacts:\n    name: Build and verify canonical update artifacts\n    if: github.event_name != 'pull_request'\n",
    text, 'CI artifact job'
)
text = re.sub(
    r'      # This only activates the isolated `\.ci` signing fallback when a real\n      # production keystore was not provided through repository secrets\.\n      FOCUSGUARD_CI_SIGNING: "true"\n',
    '', text, count=1
)
text = sub1(
    r'      RELEASE_KEYSTORE_BASE64: .*\n',
    '      KEYSTORE_BASE64: ${{ secrets.KEYSTORE_BASE64 }}\n',
    text, 'CI keystore secret'
)
text = text.replace('RELEASE_KEYSTORE_BASE64', 'KEYSTORE_BASE64')
text = sub1(
    r'          else\n            echo "SIGNING_MODE=ci" >> "\$GITHUB_ENV"\n          fi\n',
    '''          else
            echo "::error::Production signing secrets are required for installable main artifacts."
            exit 1
          fi
''', text, 'CI signing fallback'
)
text = sub1(
    r'''          grep -Eq "package: name='com\\\.focusguard\\\.v2\(\\\.ci\)\?'" \\\n            ci-artifacts/release-package\.txt\n''',
    '          grep -q "package: name=\'com.focusguard.v2\'" ci-artifacts/release-package.txt\n'
    f'          grep -qi "{CERT}" ci-artifacts/release-apksigner.txt\n',
    text, 'CI package invariant'
)
text = re.sub(
    r'\n      - name: Upload installable CI Release APK\n.*?(?=\n      - name: Upload Debug APK)',
    '\n', text, count=1, flags=re.S
)
text = re.sub(
    r'\n      - name: Upload Debug APK\n.*?(?=\n      - name: Upload production Release AAB)',
    '\n', text, count=1, flags=re.S
)
text = re.sub(
    r'\n      - name: Upload CI Release AAB\n.*?(?=\n      - name: Upload checksums)',
    '\n', text, count=1, flags=re.S
)
path.write_text(text)

# Release: publish only the canonical production APK/AAB.
path = Path(".github/workflows/release.yml")
text = path.read_text()
text = text.replace(
    '      - name: Run release checks and build Release APK + Debug APK + AAB\n',
    '      - name: Run release checks and build canonical Release APK + AAB\n', 1
)
text = text.replace('            assembleDebug \\\n', '', 1)
text = re.sub(r'^          DEBUG_APK=.*\n', '', text, count=1, flags=re.M)
text = text.replace('          test -n "$DEBUG_APK" && test -s "$DEBUG_APK"\n', '', 1)
text = re.sub(r'^          RELEASE_DEBUG_APK=.*\n', '', text, count=1, flags=re.M)
text = text.replace('          cp "$DEBUG_APK" "$RELEASE_DEBUG_APK"\n', '', 1)
text = text.replace('          echo "debug_apk=$RELEASE_DEBUG_APK" >> "$GITHUB_OUTPUT"\n', '', 1)
text = re.sub(
    r'\n      - name: Upload Debug APK artifact\n.*?(?=\n      - name: Upload Play AAB artifact)',
    '\n', text, count=1, flags=re.S
)
old = '''          APKSIGNER="$(find "$ANDROID_HOME/build-tools" -type f -name apksigner | sort -V | tail -n 1)"
          test -x "$APKSIGNER"
          "$APKSIGNER" verify --verbose --print-certs "${{ steps.collect.outputs.apk }}"
          jarsigner -verify "${{ steps.collect.outputs.aab }}"
'''
if old not in text:
    raise RuntimeError('release verification block not found')
new = f'''          APKSIGNER="$(find "$ANDROID_HOME/build-tools" -type f -name apksigner | sort -V | tail -n 1)"
          AAPT="$ANDROID_HOME/build-tools/36.0.0/aapt"
          test -x "$APKSIGNER"
          test -x "$AAPT"
          "$APKSIGNER" verify --verbose --print-certs "${{{{ steps.collect.outputs.apk }}}}" | tee release/apksigner.txt
          "$AAPT" dump badging "${{{{ steps.collect.outputs.apk }}}}" | head -n 1 | tee release/package.txt
          grep -q "package: name='com.focusguard.v2'" release/package.txt
          grep -qi "{CERT}" release/apksigner.txt
          jarsigner -verify "${{{{ steps.collect.outputs.aab }}}}"
'''
text = text.replace(old, new, 1)
text = text.replace(
    'Includes an installable Release APK, a Google Play AAB, and SHA-256 checksums. A Debug APK is also available as a workflow artifact.',
    'Includes the canonical installable Release APK (com.focusguard.v2), a Google Play AAB, and SHA-256 checksums.'
)
text = text.replace(
    'Release APK, Debug APK, AAB and checksums were built, verified and uploaded as workflow artifacts.',
    'Canonical Release APK, AAB and checksums were built, verified and uploaded as workflow artifacts.'
)
path.write_text(text)

# Final invariants.
gradle = Path("app/build.gradle.kts").read_text()
ci = Path(".github/workflows/android-ci.yml").read_text()
release = Path(".github/workflows/release.yml").read_text()
assert 'applicationId = "com.focusguard.v2"' in gradle
assert 'applicationIdSuffix = ".ci"' not in gradle
assert 'FOCUSGUARD_CI_SIGNING' not in gradle
assert 'secrets.RELEASE_KEYSTORE_BASE64' not in ci
assert 'com.focusguard.v2(.ci)?' not in ci
assert CERT in ci and CERT in release
assert 'Upload Debug APK artifact' not in release
