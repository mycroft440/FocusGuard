from pathlib import Path

service_path = Path("app/src/main/java/com/focusguard/service/BlockingAccessibilityService.kt")
service = service_path.read_text()

replacements = [
    (
        "import com.focusguard.accessibility.website.redirection.ClipboardPasteFallback\n",
        "import com.focusguard.accessibility.website.redirection.ClipboardPasteFallback\n"
        "import com.focusguard.accessibility.website.redirection.WebsiteRedirectionPlan\n",
    ),
    (
        "                if (!redirectRequested &&\n"
        "                    transitionOwnsCurtain(transition) &&\n",
        "                if (!redirectRequested &&\n"
        "                    WebsiteRedirectionPlan.ALLOW_EXTERNAL_BROWSER_INTENT_FALLBACK &&\n"
        "                    transitionOwnsCurtain(transition) &&\n",
    ),
    (
        "        private const val WEBSITE_ADDRESS_BAR_FOCUS_SETTLE_MILLIS = 48L\n",
        "        private const val WEBSITE_ADDRESS_BAR_FOCUS_SETTLE_MILLIS = 32L\n",
    ),
    (
        "        private const val WEBSITE_ADDRESS_BAR_ACTION_RETRY_MILLIS = 32L\n",
        "        private const val WEBSITE_ADDRESS_BAR_ACTION_RETRY_MILLIS = 16L\n",
    ),
    (
        "        private const val WEBSITE_GOOGLE_SURFACE_SETTLE_MILLIS = 120L\n",
        "        private const val WEBSITE_GOOGLE_SURFACE_SETTLE_MILLIS = 80L\n",
    ),
    (
        "        internal const val WEBSITE_MIN_BLOCK_NOTICE_MILLIS = 1_000L\n",
        "        internal const val WEBSITE_MIN_BLOCK_NOTICE_MILLIS = 250L\n",
    ),
]

for old, new in replacements:
    count = service.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one service replacement, got {count}: {old!r}")
    service = service.replace(old, new, 1)

service_path.write_text(service)

test_path = Path("app/src/test/java/com/focusguard/service/WebsiteBlockNavigationTest.kt")
test = test_path.read_text()
old_test = (
    "    fun `website curtain remains visible for one second while redirect starts immediately`() {\n"
    "        assertThat(BlockingAccessibilityService.WEBSITE_MIN_BLOCK_NOTICE_MILLIS)\n"
    "            .isEqualTo(1_000L)\n"
    "    }\n"
)
new_test = (
    "    fun `website curtain remains briefly visible while redirect starts immediately`() {\n"
    "        assertThat(BlockingAccessibilityService.WEBSITE_MIN_BLOCK_NOTICE_MILLIS)\n"
    "            .isEqualTo(250L)\n"
    "    }\n"
)
count = test.count(old_test)
if count != 1:
    raise SystemExit(f"Expected exactly one navigation test replacement, got {count}")
test_path.write_text(test.replace(old_test, new_test, 1))

print("Applied same-tab redirect policy and latency tuning")
