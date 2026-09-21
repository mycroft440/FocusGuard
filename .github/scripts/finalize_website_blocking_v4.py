from pathlib import Path
from xml.sax.saxutils import escape


def replace_exact(path: Path, old: str, new: str, expected: int = 1) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != expected:
        raise SystemExit(f"{path}: expected {expected} occurrence(s), found {count}")
    path.write_text(text.replace(old, new))


service = Path("app/src/main/java/com/focusguard/service/BlockingAccessibilityService.kt")

replace_exact(
    service,
    "import com.focusguard.accessibility.website.compatibility.BrowserCompatibilityStore\n"
    "import com.focusguard.accessibility.website.compatibility.BrowserActivationMethod\n",
    "import com.focusguard.accessibility.website.compatibility.BrowserCompatibilityStore\n"
    "import com.focusguard.accessibility.website.compatibility.BrowserDetector\n"
    "import com.focusguard.accessibility.website.compatibility.BrowserProfileRegistry\n"
    "import com.focusguard.accessibility.website.compatibility.BrowserActivationMethod\n",
)

replace_exact(
    service,
    """    private enum class CurtainMode {
        BLOCK_NOTICE,
        SELF_PROTECTION
    }
""",
    """    private enum class CurtainMode {
        BLOCK_NOTICE,
        WEBSITE_BLOCK_NOTICE,
        SELF_PROTECTION
    }
""",
)

replace_exact(
    service,
    """    @Volatile private var browserPackages: Set<String> = emptySet()
    private var verifiedHttpsHandlerPackages: Set<String> = emptySet()
    private data class BrowserDiscoveryMiss(
""",
    """    @Volatile private var browserPackages: Set<String> = emptySet()
    @Volatile private var verifiedHttpsHandlerPackages: Set<String> = emptySet()
    private var browserClassificationJob: Job? = null
    private data class BrowserDiscoveryMiss(
""",
)

replace_exact(
    service,
    """        "org.mozilla.fenix",
        "org.mozilla.fennec_aurora",
""",
    """        "org.mozilla.fenix",
        "org.mozilla.fenix.nightly",
        "org.mozilla.fennec_aurora",
""",
)

replace_exact(
    service,
    """    private fun calculateBrowserPackages() {
        browserDiscoveryMisses.clear()
        browserPackages = try {
            val browserIntent = Intent(
                Intent.ACTION_VIEW,
                android.net.Uri.parse("https://example.com")
            ).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
            }
            val dynamicBrowsers = packageManager.queryIntentActivities(
                browserIntent,
                PackageManagerCompat.MATCH_ALL
            ).mapNotNull { it.activityInfo?.packageName }.toSet()
            verifiedHttpsHandlerPackages = dynamicBrowsers
            knownBrowserPackages + dynamicBrowsers
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError(
                "A11y",
                "Falha ao identificar navegadores",
                error
            )
            verifiedHttpsHandlerPackages = emptySet()
            knownBrowserPackages
        }
    }
""",
    """    private fun calculateBrowserPackages() {
        browserDiscoveryMisses.clear()
        browserClassificationJob?.cancel()

        val browserIntent = Intent(
            Intent.ACTION_VIEW,
            android.net.Uri.parse("https://example.com")
        ).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        val dynamicCandidates = try {
            packageManager.queryIntentActivities(
                browserIntent,
                PackageManagerCompat.MATCH_ALL
            ).mapNotNull { it.activityInfo?.packageName }.toSet()
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError(
                "A11y",
                "Falha ao identificar candidatos a navegador",
                error
            )
            emptySet()
        }

        // Exact shipped profiles remain immediately available. Every other package
        // must pass BrowserDetector's structural contract off the callback thread
        // before it is allowed to enter any Accessibility tree inspection.
        val exactProfiles = knownBrowserPackages
            .filter(BrowserProfileRegistry::isKnownBrowserPackage)
            .toSet()
        browserPackages = exactProfiles
        verifiedHttpsHandlerPackages = emptySet()
        if (dynamicCandidates.isEmpty()) return

        browserClassificationJob = scope.launch {
            val confirmedDynamic = linkedSetOf<String>()
            for (candidate in dynamicCandidates) {
                if (!isActive) return@launch
                if (BrowserDetector.detect(candidate).classification.isBrowserLike) {
                    confirmedDynamic += candidate
                }
            }
            if (!isActive) return@launch
            verifiedHttpsHandlerPackages = confirmedDynamic
            browserPackages = exactProfiles + confirmedDynamic
        }
    }
""",
)

replace_exact(
    service,
    """                try {
                    if (current()) {
                        delay(WebsiteObservabilityPolicy.OPAQUE_BROWSER_GRACE_MILLIS)
                        if (current()) {
""",
    """                val genericRecovery =
                    !BrowserProfileRegistry.isKnownBrowserPackage(packageName)
                var genericCurtainGeneration = 0L
                try {
                    if (current()) {
                        if (genericRecovery) {
                            genericCurtainGeneration = withContext(Dispatchers.Main.immediate) {
                                if (current()) {
                                    showInstantBlockCurtain(
                                        mode = CurtainMode.WEBSITE_BLOCK_NOTICE
                                    )
                                } else {
                                    0L
                                }
                            }
                        } else {
                            delay(WebsiteObservabilityPolicy.OPAQUE_BROWSER_GRACE_MILLIS)
                        }
                        if (current()) {
""",
)

replace_exact(
    service,
    """                } finally {
                    next = browserRecoveryCoordinator.finish(token)
                }
""",
    """                } finally {
                    if (genericCurtainGeneration > 0L) {
                        mainHandler.post {
                            // Generation ownership prevents an old generic inspection from
                            // dismissing a newer HARD/PASSWORD/fail-closed presentation.
                            dismissInstantBlockCurtain(genericCurtainGeneration)
                        }
                    }
                    next = browserRecoveryCoordinator.finish(token)
                }
""",
)

replace_exact(
    service,
    """    private fun showWebsiteBlockPresentation(blockedCandidate: String?): Long {
        val generation = showInstantBlockCurtain(mode = CurtainMode.BLOCK_NOTICE)
""",
    """    private fun showWebsiteBlockPresentation(blockedCandidate: String?): Long {
        val generation = showInstantBlockCurtain(mode = CurtainMode.WEBSITE_BLOCK_NOTICE)
""",
)

replace_exact(
    service,
    """        instantBlockCurtain = curtain
        instantBlockCurtainLayoutParams = WindowManager.LayoutParams(
""",
    """        curtain.isClickable = true
        curtain.isFocusable = true
        curtain.isFocusableInTouchMode = true
        curtain.setOnTouchListener { _, _ -> instantBlockCurtainVisible }
        curtain.setOnKeyListener { _, keyCode, _ ->
            shouldConsumeWebsiteCurtainKey(
                curtainVisible = instantBlockCurtainVisible,
                websiteCurtain = instantBlockCurtainMode == CurtainMode.WEBSITE_BLOCK_NOTICE,
                keyCode = keyCode
            )
        }
        instantBlockCurtain = curtain
        instantBlockCurtainLayoutParams = WindowManager.LayoutParams(
""",
)

replace_exact(
    service,
    """        params.alpha = 1f
        params.flags = visibleOverlayFlags(params.flags)
        val curtain = instantBlockCurtain ?: return
        val manager = windowManager ?: return
        runCatching {
            manager.updateViewLayout(curtain, params)
            instantBlockCurtainVisible = true
""",
    """        val websiteCurtain = mode == CurtainMode.WEBSITE_BLOCK_NOTICE
        params.alpha = 1f
        params.flags = visibleOverlayFlags(params.flags, focusable = websiteCurtain)
        val curtain = instantBlockCurtain ?: return
        val manager = windowManager ?: return
        runCatching {
            manager.updateViewLayout(curtain, params)
            instantBlockCurtainVisible = true
            if (websiteCurtain) {
                curtain.requestFocus()
            } else {
                curtain.clearFocus()
            }
""",
)

replace_exact(
    service,
    """    override fun onKeyEvent(event: KeyEvent): Boolean {
        val isBackOrHomeKey = event.keyCode == KeyEvent.KEYCODE_BACK ||
""",
    """    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (shouldConsumeWebsiteCurtainKey(
                curtainVisible = instantBlockCurtainVisible,
                websiteCurtain = instantBlockCurtainMode == CurtainMode.WEBSITE_BLOCK_NOTICE,
                keyCode = event.keyCode
            )
        ) return true

        val isBackOrHomeKey = event.keyCode == KeyEvent.KEYCODE_BACK ||
""",
)

replace_exact(
    service,
    """        internal fun visibleOverlayFlags(flags: Int): Int =
            (flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()) or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

""",
    """        internal fun visibleOverlayFlags(
            flags: Int,
            focusable: Boolean = false
        ): Int {
            val touchable = flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            return if (focusable) {
                touchable and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            } else {
                touchable or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            }
        }

        internal fun shouldConsumeWebsiteCurtainKey(
            curtainVisible: Boolean,
            websiteCurtain: Boolean,
            keyCode: Int
        ): Boolean {
            if (!curtainVisible || !websiteCurtain) return false
            return keyCode !in setOf(
                KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_VOLUME_MUTE,
                KeyEvent.KEYCODE_POWER,
                KeyEvent.KEYCODE_CAMERA,
                KeyEvent.KEYCODE_HEADSETHOOK,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_MEDIA_STOP
            )
        }

""",
)

# Localized settings strings. Keep every supported locale in key parity because
# MissingTranslation/ExtraTranslation are release errors in this project.
translations = {
    "values": (
        "Redirect destination",
        "After a site block: %1$s",
        "Choose the root address HardBlock should open after blocking a website. HTTP and HTTPS are supported.",
        "Enter a valid root address without credentials, path, query, or fragment.",
        "This address is currently blocked. Choose another destination.",
    ),
    "values-en": (
        "Redirect destination",
        "After a site block: %1$s",
        "Choose the root address HardBlock should open after blocking a website. HTTP and HTTPS are supported.",
        "Enter a valid root address without credentials, path, query, or fragment.",
        "This address is currently blocked. Choose another destination.",
    ),
    "values-pt": (
        "Destino do redirecionamento",
        "Após bloquear um site: %1$s",
        "Escolha o endereço raiz que o HardBlock deve abrir depois de bloquear um site. HTTP e HTTPS são aceitos.",
        "Digite um endereço raiz válido, sem credenciais, caminho, consulta ou fragmento.",
        "Este endereço está bloqueado no momento. Escolha outro destino.",
    ),
    "values-pt-rBR": (
        "Destino do redirecionamento",
        "Após bloquear um site: %1$s",
        "Escolha o endereço raiz que o HardBlock deve abrir depois de bloquear um site. HTTP e HTTPS são aceitos.",
        "Digite um endereço raiz válido, sem credenciais, caminho, consulta ou fragmento.",
        "Este endereço está bloqueado no momento. Escolha outro destino.",
    ),
    "values-b+zh+Hans": (
        "重定向目标",
        "网站被拦截后打开：%1$s",
        "选择 HardBlock 在拦截网站后应打开的根地址。支持 HTTP 和 HTTPS。",
        "请输入有效的根地址，不要包含凭据、路径、查询参数或片段。",
        "此地址当前已被拦截。请选择其他目标。",
    ),
    "values-hi": (
        "रीडायरेक्ट गंतव्य",
        "साइट ब्लॉक होने के बाद: %1$s",
        "वेबसाइट ब्लॉक होने के बाद HardBlock जिस मूल पते को खोले, उसे चुनें। HTTP और HTTPS समर्थित हैं।",
        "बिना क्रेडेंशियल, पाथ, क्वेरी या फ़्रैगमेंट के एक मान्य मूल पता दर्ज करें।",
        "यह पता अभी ब्लॉक है। कोई दूसरा गंतव्य चुनें।",
    ),
    "values-es": (
        "Destino de redirección",
        "Después de bloquear un sitio: %1$s",
        "Elige la dirección raíz que HardBlock debe abrir después de bloquear un sitio web. Se admiten HTTP y HTTPS.",
        "Introduce una dirección raíz válida sin credenciales, ruta, consulta ni fragmento.",
        "Esta dirección está bloqueada actualmente. Elige otro destino.",
    ),
    "values-ar": (
        "وجهة إعادة التوجيه",
        "بعد حظر موقع: %1$s",
        "اختر العنوان الجذري الذي يفتحه HardBlock بعد حظر موقع. يتم دعم HTTP وHTTPS.",
        "أدخل عنوانًا جذريًا صالحًا من دون بيانات اعتماد أو مسار أو استعلام أو جزء.",
        "هذا العنوان محظور حاليًا. اختر وجهة أخرى.",
    ),
    "values-fr": (
        "Destination de redirection",
        "Après le blocage d’un site : %1$s",
        "Choisissez l’adresse racine que HardBlock doit ouvrir après le blocage d’un site. HTTP et HTTPS sont pris en charge.",
        "Saisissez une adresse racine valide sans identifiants, chemin, requête ni fragment.",
        "Cette adresse est actuellement bloquée. Choisissez une autre destination.",
    ),
    "values-bn": (
        "পুনঃনির্দেশের গন্তব্য",
        "সাইট ব্লক করার পরে: %1$s",
        "কোনো ওয়েবসাইট ব্লক করার পরে HardBlock যে মূল ঠিকানাটি খুলবে সেটি বেছে নিন। HTTP এবং HTTPS সমর্থিত।",
        "লগইন তথ্য, পাথ, কোয়েরি বা ফ্র্যাগমেন্ট ছাড়া একটি বৈধ মূল ঠিকানা লিখুন।",
        "এই ঠিকানাটি বর্তমানে ব্লক করা আছে। অন্য গন্তব্য বেছে নিন।",
    ),
    "values-id": (
        "Tujuan pengalihan",
        "Setelah situs diblokir: %1$s",
        "Pilih alamat root yang akan dibuka HardBlock setelah memblokir situs. HTTP dan HTTPS didukung.",
        "Masukkan alamat root yang valid tanpa kredensial, path, kueri, atau fragmen.",
        "Alamat ini sedang diblokir. Pilih tujuan lain.",
    ),
    "values-ur": (
        "ری ڈائریکٹ منزل",
        "سائٹ بلاک ہونے کے بعد: %1$s",
        "ویب سائٹ بلاک ہونے کے بعد HardBlock جو بنیادی پتہ کھولے، اسے منتخب کریں۔ HTTP اور HTTPS معاون ہیں۔",
        "اسناد، راستے، استفسار یا فریگمنٹ کے بغیر درست بنیادی پتہ درج کریں۔",
        "یہ پتہ اس وقت بلاک ہے۔ کوئی دوسری منزل منتخب کریں۔",
    ),
    "values-ru": (
        "Адрес перенаправления",
        "После блокировки сайта: %1$s",
        "Выберите корневой адрес, который HardBlock должен открыть после блокировки сайта. Поддерживаются HTTP и HTTPS.",
        "Введите корректный корневой адрес без учетных данных, пути, запроса и фрагмента.",
        "Этот адрес сейчас заблокирован. Выберите другой.",
    ),
    "values-de": (
        "Weiterleitungsziel",
        "Nach dem Blockieren einer Website: %1$s",
        "Wähle die Stammadresse, die HardBlock nach dem Blockieren einer Website öffnen soll. HTTP und HTTPS werden unterstützt.",
        "Gib eine gültige Stammadresse ohne Zugangsdaten, Pfad, Abfrage oder Fragment ein.",
        "Diese Adresse ist derzeit blockiert. Wähle ein anderes Ziel.",
    ),
    "values-ja": (
        "リダイレクト先",
        "サイトをブロックした後: %1$s",
        "Web サイトをブロックした後に HardBlock が開くルートアドレスを選択します。HTTP と HTTPS に対応しています。",
        "認証情報、パス、クエリ、フラグメントを含まない有効なルートアドレスを入力してください。",
        "このアドレスは現在ブロックされています。別の宛先を選択してください。",
    ),
    "values-b+pcm": (
        "Where redirect go",
        "After site block: %1$s",
        "Choose di root address wey HardBlock go open after e block website. HTTP and HTTPS dey work.",
        "Enter correct root address without login details, path, query or fragment.",
        "Dis address dey blocked now. Choose another place.",
    ),
    "values-b+arz": (
        "وجهة التحويل",
        "بعد ما الموقع يتمنع: %1$s",
        "اختار العنوان الرئيسي اللي HardBlock يفتحه بعد ما يمنع موقع. HTTP وHTTPS مدعومين.",
        "اكتب عنوان رئيسي صحيح من غير بيانات دخول أو مسار أو استعلام أو جزء.",
        "العنوان ده محظور دلوقتي. اختار وجهة تانية.",
    ),
    "values-mr": (
        "रीडायरेक्ट गंतव्य",
        "साइट ब्लॉक केल्यानंतर: %1$s",
        "वेबसाइट ब्लॉक केल्यानंतर HardBlock ने उघडायचा मूळ पत्ता निवडा. HTTP आणि HTTPS समर्थित आहेत.",
        "क्रेडेन्शियल, पाथ, क्वेरी किंवा फ्रॅगमेंट नसलेला वैध मूळ पत्ता टाका.",
        "हा पत्ता सध्या ब्लॉक आहे. दुसरे गंतव्य निवडा.",
    ),
    "values-vi": (
        "Đích chuyển hướng",
        "Sau khi chặn trang web: %1$s",
        "Chọn địa chỉ gốc mà HardBlock sẽ mở sau khi chặn một trang web. Hỗ trợ HTTP và HTTPS.",
        "Nhập địa chỉ gốc hợp lệ, không có thông tin đăng nhập, đường dẫn, truy vấn hoặc đoạn neo.",
        "Địa chỉ này hiện đang bị chặn. Hãy chọn đích khác.",
    ),
    "values-te": (
        "దారి మళ్లింపు గమ్యం",
        "సైట్‌ను బ్లాక్ చేసిన తర్వాత: %1$s",
        "వెబ్‌సైట్‌ను బ్లాక్ చేసిన తర్వాత HardBlock తెరవాల్సిన మూల చిరునామాను ఎంచుకోండి. HTTP మరియు HTTPS మద్దతు ఉన్నాయి.",
        "క్రెడెన్షియల్స్, పాత్, క్వెరీ లేదా ఫ్రాగ్మెంట్ లేని చెల్లుబాటు అయ్యే మూల చిరునామాను నమోదు చేయండి.",
        "ఈ చిరునామా ప్రస్తుతం బ్లాక్ చేయబడింది. మరో గమ్యాన్ని ఎంచుకోండి.",
    ),
    "values-sw": (
        "Mahali pa kuelekeza upya",
        "Baada ya kuzuia tovuti: %1$s",
        "Chagua anwani ya msingi ambayo HardBlock itafungua baada ya kuzuia tovuti. HTTP na HTTPS zinatumika.",
        "Weka anwani halali ya msingi bila taarifa za kuingia, njia, hoja au kipande.",
        "Anwani hii imezuiwa kwa sasa. Chagua mahali pengine.",
    ),
    "values-ha": (
        "Wurin turawa",
        "Bayan an toshe shafi: %1$s",
        "Zaɓi tushen adireshin da HardBlock zai buɗe bayan ya toshe shafin yanar gizo. Ana tallafa wa HTTP da HTTPS.",
        "Shigar da ingantaccen tushen adireshi ba tare da bayanan shiga, hanya, tambaya ko fragment ba.",
        "An toshe wannan adireshin yanzu. Zaɓi wani wuri.",
    ),
}

keys = (
    "settings_redirect_destination_title",
    "settings_redirect_destination_subtitle",
    "settings_redirect_destination_helper",
    "settings_redirect_destination_invalid",
    "settings_redirect_destination_blocked",
)

for folder, values in translations.items():
    path = Path("app/src/main/res") / folder / "strings.xml"
    if not path.is_file():
        raise SystemExit(f"supported locale file missing: {path}")
    text = path.read_text()
    if all(f'name="{key}"' in text for key in keys):
        continue
    if any(f'name="{key}"' in text for key in keys):
        raise SystemExit(f"partial redirect strings already present: {path}")
    block = ["    <!-- Website redirect destination -->"]
    for key, value in zip(keys, values):
        block.append(f'    <string name="{key}">{escape(value)}</string>')
    insertion = "\n".join(block) + "\n"
    if "</resources>" not in text:
        raise SystemExit(f"invalid resources file: {path}")
    path.write_text(text.rsplit("</resources>", 1)[0] + insertion + "</resources>\n")

# Scope guards for the final v4 invariants implemented by this pass.
service_text = service.read_text()
required = [
    "CurtainMode.WEBSITE_BLOCK_NOTICE",
    "BrowserDetector.detect(candidate).classification.isBrowserLike",
    "BrowserProfileRegistry.isKnownBrowserPackage(packageName)",
    "visibleOverlayFlags(params.flags, focusable = websiteCurtain)",
    "shouldConsumeWebsiteCurtainKey(",
]
for marker in required:
    if marker not in service_text:
        raise SystemExit(f"missing final website v4 marker: {marker}")
