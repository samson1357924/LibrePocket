package dev.librepocket.play

import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 驗證 Play 風味類別路徑（classpath）中完全隔離 Jackson 依賴。
 *
 * Jackson 僅隨 Azure Speech SDK 引入於 github flavor，
 * Play 版本必須維持零 Jackson 依賴以確保商店發行純淨度與最小攻擊面。
 */
class JacksonPlayIsolationTest {

    @Test
    fun jacksonObjectMapper_notPresentOnPlayClasspath() {
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("com.fasterxml.jackson.databind.ObjectMapper")
        }
    }

    @Test
    fun jacksonJsonFactory_notPresentOnPlayClasspath() {
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("com.fasterxml.jackson.core.JsonFactory")
        }
    }

    @Test
    fun jacksonJsonProperty_notPresentOnPlayClasspath() {
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("com.fasterxml.jackson.annotation.JsonProperty")
        }
    }
}
