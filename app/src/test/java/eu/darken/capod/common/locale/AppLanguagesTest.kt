package eu.darken.capod.common.locale

import android.app.Application
import android.app.LocaleManager
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import eu.darken.capod.R
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.TestApplication
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApplication::class)
class AppLanguagesTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    @Test fun `Chinese override is stored in Android app locales and can follow system again`() {
        AppLanguages.select(app, AppLanguage.SIMPLIFIED_CHINESE)
        app.getSystemService(LocaleManager::class.java).applicationLocales[0].toLanguageTag() shouldBe "zh-CN"
        AppLanguages.current(app) shouldBe AppLanguage.SIMPLIFIED_CHINESE
        AppLanguages.select(app, AppLanguage.SYSTEM)
        app.getSystemService(LocaleManager::class.java).applicationLocales.isEmpty shouldBe true
        AppLanguages.current(app) shouldBe AppLanguage.SYSTEM
    }
    @Test fun `existing system app-language preference is read without overwriting it`() {
        app.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("zh-CN")
        AppLanguages.current(app) shouldBe AppLanguage.SIMPLIFIED_CHINESE
    }
    @Test fun `Chinese resources replace English actions and retain error format arguments`() {
        val localized = app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) })
        localized.getString(R.string.general_save_action) shouldBe "保存"
        localized.getString(R.string.settings_general_label) shouldBe "常规设置"
        localized.getString(R.string.device_settings_send_failed, "socket-test").contains("socket-test") shouldBe true
    }
}
