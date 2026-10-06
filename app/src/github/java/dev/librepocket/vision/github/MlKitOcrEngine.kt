package dev.librepocket.vision.github

import com.google.mlkit.vision.common.InputImage
import dev.librepocket.vision.OcrEngine
import dev.librepocket.vision.OcrResult

/**
 * GitHub-flavor ML Kit OCR 骨架（`src/github`，play/foss 物理缺失）。
 *
 * 接线证明：引用 [InputImage]（`com.google.mlkit:vision-common`，
 * github-only 依赖），完整 `TextRecognition` 客户端接线后续阶段补齐；
 * 当前返回空文本，调用方按识别失败降级。
 */
class MlKitOcrEngine : OcrEngine {

    /** ML Kit 后端标记：保证 github-only 依赖在编译期被引用。 */
    val backend: Class<*> = InputImage::class.java

    override fun recognize(image: ByteArray): OcrResult = OcrResult()
}
