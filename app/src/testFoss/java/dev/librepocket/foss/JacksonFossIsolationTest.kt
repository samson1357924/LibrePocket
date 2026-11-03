package dev.librepocket.foss

import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 驗證 FOSS 風味類別路徑（classpath）中完全隔離 Jackson 依賴。
 *
 * Jackson 僅隨 Azure Speech SDK 引入於 github flavor，
 * FOSS 版本必須維持零 Jackson 依賴以確保開源純淨度與最小攻擊面。
 */
class JacksonFossIsolationTest {

    @Test
    fun jacksonObjectMapper_notPresentOnFossClasspath() {
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("com.fasterxml.jackson.databind.ObjectMapper")
        }
    }

    @Test
    fun jacksonCore_notPresentOnFossClasspath() {
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("com.fasterxml.jackson.core.JsonFactory")
        }
    }

    @Test
    fun jacksonJsonProperty_notPresentOnFossClasspath() {
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("com.fasterxml.jackson.annotation.JsonProperty")
        }
    }
}
