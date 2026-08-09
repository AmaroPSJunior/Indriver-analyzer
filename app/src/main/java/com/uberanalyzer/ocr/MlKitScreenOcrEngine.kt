package com.uberanalyzer.ocr

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.Display
import androidx.annotation.RequiresApi
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.uberanalyzer.parser.RideParser
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Engine responsável por capturar o bitmap diretamente dos pixels da tela (Screenshot)
 * e processar localmente usando Google ML Kit Text Recognition (OCR).
 * 
 * Permite contornar completamente restrições de acessibilidade no aplicativo inDrive.
 */
object MlKitScreenOcrEngine {

    private const val TAG = "MlKitScreenOcrEngine"
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val ocrExecutor: Executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var isProcessing = false
    private var lastCaptureTime = 0L
    private const val CAPTURE_COOLDOWN_MS = 1500L

    interface OcrResultCallback {
        fun onOcrCompleted(
            extractedText: String,
            fullImage: Bitmap?,
            textBlocks: List<OcrBlock>,
            lines: List<OcrLine>
        )
        fun onError(error: Exception)
    }

    data class OcrLine(
        val text: String,
        val boundingBox: Rect?
    )

    data class OcrBlock(
        val text: String,
        val boundingBox: Rect?,
        val confidence: Float? = null
    )

    /**
     * Captura a tela via API nativa de Acessibilidade (Android 11+) e processa OCR com Google ML Kit.
     */
    fun captureAndProcessScreen(
        service: AccessibilityService,
        callback: OcrResultCallback
    ) {
        val now = System.currentTimeMillis()
        if (isProcessing || (now - lastCaptureTime < CAPTURE_COOLDOWN_MS)) {
            callback.onError(IllegalStateException("OCR ocupado ou em cooldown"))
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            isProcessing = true
            lastCaptureTime = now

            try {
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    ocrExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshotResult: AccessibilityService.ScreenshotResult) {
                            var softwareBitmap: Bitmap? = null
                            try {
                                val hardwareBuffer = screenshotResult.hardwareBuffer
                                val colorSpace = screenshotResult.colorSpace
                                val hwBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                                
                                softwareBitmap = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                                try { hwBitmap?.recycle() } catch (_: Exception) {}
                                try { hardwareBuffer.close() } catch (_: Exception) {}

                                if (softwareBitmap != null) {
                                    processBitmapWithMlKit(softwareBitmap, callback)
                                } else {
                                    isProcessing = false
                                    callback.onError(IllegalStateException("Falha ao converter bitmap da tela."))
                                }
                            } catch (e: Exception) {
                                isProcessing = false
                                Log.e(TAG, "Erro ao extrair bitmap da tela: ${e.message}", e)
                                try { softwareBitmap?.recycle() } catch (_: Exception) {}
                                callback.onError(e)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            isProcessing = false
                            Log.e(TAG, "Falha na captura de tela por Acessibilidade. Código: $errorCode")
                            callback.onError(RuntimeException("Erro de captura de tela de Acessibilidade (Código $errorCode)"))
                        }
                    }
                )
            } catch (e: Exception) {
                isProcessing = false
                Log.e(TAG, "Exceção em takeScreenshot: ${e.message}", e)
                callback.onError(e)
            }
        } else {
            callback.onError(UnsupportedOperationException("Captura direta de tela por Acessibilidade requer Android 11 (API 30) ou superior."))
        }
    }

    /**
     * Processa qualquer Bitmap diretamente com o Google ML Kit Vision Text Recognizer localmente.
     */
    fun processBitmapWithMlKit(
        bitmap: Bitmap,
        callback: OcrResultCallback
    ) {
        try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)

            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    try {
                        val fullExtractedText = buildFormattedOcrText(visionText)
                        val blocks = visionText.textBlocks.map { block ->
                            OcrBlock(
                                text = block.text,
                                boundingBox = block.boundingBox
                            )
                        }

                        val allLines = visionText.textBlocks
                            .flatMap { block -> block.lines }
                            .map { line ->
                                OcrLine(
                                    text = line.text,
                                    boundingBox = line.boundingBox
                                )
                            }
                            .sortedWith(compareBy<OcrLine> { it.boundingBox?.top ?: 0 }.thenBy { it.boundingBox?.left ?: 0 })

                        Log.d(TAG, "⚡ ML Kit OCR concluído com sucesso! Texto extraído:\n$fullExtractedText")
                        callback.onOcrCompleted(fullExtractedText, bitmap, blocks, allLines)
                    } catch (e: Exception) {
                        Log.e(TAG, "Erro ao processar resultado do OCR: ${e.message}", e)
                        callback.onError(e)
                    } finally {
                        isProcessing = false
                        try { bitmap.recycle() } catch (_: Exception) {}
                    }
                }
                .addOnFailureListener { e ->
                    isProcessing = false
                    try { bitmap.recycle() } catch (_: Exception) {}
                    Log.e(TAG, "Erro no reconhecimento de texto pelo ML Kit: ${e.message}", e)
                    callback.onError(e)
                }
        } catch (e: Exception) {
            isProcessing = false
            try { bitmap.recycle() } catch (_: Exception) {}
            callback.onError(e)
        }
    }

    /**
     * Formata o texto retornado pelo ML Kit ordenado por posição vertical (Y) na tela
     * para manter a ordem exata da listagem do inDrive.
     */
    private fun buildFormattedOcrText(visionText: Text): String {
        val sb = StringBuilder()
        
        // Ordena linhas de texto de cima para baixo (Y) e da esquerda para a direita (X)
        val lines = visionText.textBlocks
            .flatMap { it.lines }
            .sortedWith(compareBy<Text.Line> { it.boundingBox?.top ?: 0 }.thenBy { it.boundingBox?.left ?: 0 })

        for (line in lines) {
            val text = line.text.trim()
            if (text.isNotBlank()) {
                sb.append(text).append(" | ")
            }
        }

        return sb.toString()
    }
}
