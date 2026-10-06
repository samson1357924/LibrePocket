package dev.librepocket.vision

/**
 * Vision/OCR 能力介面（Phase2，MATRIX §1 慢通道视觉输入）。
 *
 * 设计约束：
 * - 本包零第三方 import（纯 Kotlin），foss（ZXing/Tesseract/LiteRT）与
 *   github（ML Kit）风味各自在风味源集中实现，play 构建物理缺失；
 * - 输入一律 `ByteArray`（已解码图像字节），不触碰 `android.graphics.*`，
 *   保持 JVM 单测可断言；
 * - 骨架默认返回空结果（识别逻辑后续阶段补齐），调用方按空结果降级。
 */

/** OCR 文本识别结果。 */
data class OcrResult(
    val text: String = "",
    val confidence: Float = 0f,
)

/** 条码扫描单条结果。 */
data class BarcodeResult(
    val rawValue: String,
    val format: String = "UNKNOWN",
)

/** OCR 引擎：图像字节 → 文本。 */
interface OcrEngine {
    fun recognize(image: ByteArray): OcrResult
}

/** 条码扫描器：图像字节 → 条码列表（无码返回空表）。 */
interface BarcodeScanner {
    fun scan(image: ByteArray): List<BarcodeResult>
}
