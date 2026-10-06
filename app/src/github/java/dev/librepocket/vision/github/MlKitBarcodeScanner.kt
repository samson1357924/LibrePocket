package dev.librepocket.vision.github

import com.google.mlkit.vision.common.InputImage
import dev.librepocket.vision.BarcodeResult
import dev.librepocket.vision.BarcodeScanner

/**
 * GitHub-flavor ML Kit 条码扫描骨架（`src/github`，play/foss 物理缺失）。
 *
 * 接线证明：引用 [InputImage]（`com.google.mlkit:vision-common`，
 * github-only 依赖），完整 `BarcodeScanning` 客户端接线后续阶段补齐；
 * 当前返回空表，调用方按“无码”降级。
 */
class MlKitBarcodeScanner : BarcodeScanner {

    /** ML Kit 后端标记：保证 github-only 依赖在编译期被引用。 */
    val backend: Class<*> = InputImage::class.java

    override fun scan(image: ByteArray): List<BarcodeResult> = emptyList()
}
