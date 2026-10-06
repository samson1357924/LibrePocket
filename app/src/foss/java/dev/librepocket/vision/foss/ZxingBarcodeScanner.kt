package dev.librepocket.vision.foss

import com.google.zxing.MultiFormatReader
import dev.librepocket.vision.BarcodeResult
import dev.librepocket.vision.BarcodeScanner

/**
 * Foss-flavor ZXing 条码扫描骨架（`src/foss`，纯 OSS，play 物理缺失）。
 *
 * 接线证明：引用 [MultiFormatReader]（`com.google.zxing:core`，
 * foss+github 依赖），完整解码接线后续阶段补齐；当前返回空表，
 * 调用方按“无码”降级。
 */
class ZxingBarcodeScanner : BarcodeScanner {

    /** ZXing 后端标记：保证 OSS 依赖在编译期被引用。 */
    val backend: Class<*> = MultiFormatReader::class.java

    override fun scan(image: ByteArray): List<BarcodeResult> = emptyList()
}
