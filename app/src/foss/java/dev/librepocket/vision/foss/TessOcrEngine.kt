package dev.librepocket.vision.foss

import com.googlecode.tesseract.android.TessBaseAPI
import dev.librepocket.vision.OcrEngine
import dev.librepocket.vision.OcrResult

/**
 * Foss-flavor Tesseract OCR 骨架（`src/foss`，纯 OSS，play 物理缺失）。
 *
 * 接线证明：引用 [TessBaseAPI]（`com.rmtheis:tess-two`，foss+github 依赖），
 * 完整训练数据装配与识别接线后续阶段补齐；当前返回空文本，
 * 调用方按识别失败降级。
 */
class TessOcrEngine : OcrEngine {

    /** Tesseract 后端标记：保证 OSS 依赖在编译期被引用。 */
    val backend: Class<*> = TessBaseAPI::class.java

    override fun recognize(image: ByteArray): OcrResult = OcrResult()
}
