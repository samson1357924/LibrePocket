package dev.librepocket.vision.foss

import dev.librepocket.vision.OcrEngine
import dev.librepocket.vision.OcrResult
import org.tensorflow.lite.Interpreter

/**
 * Foss-flavor LiteRT 端侧推理 OCR 骨架（`src/foss`，纯 OSS，play 物理缺失）。
 *
 * 接线证明：引用 [Interpreter]（`org.tensorflow:tensorflow-lite`，
 * foss+github 依赖，LiteRT 谱系 API 兼容），完整模型装载与推理接线
 * 后续阶段补齐；当前返回空文本，调用方按识别失败降级。
 */
class LiteRtOcrEngine : OcrEngine {

    /** LiteRT 后端标记：保证 OSS 依赖在编译期被引用。 */
    val backend: Class<*> = Interpreter::class.java

    override fun recognize(image: ByteArray): OcrResult = OcrResult()
}
