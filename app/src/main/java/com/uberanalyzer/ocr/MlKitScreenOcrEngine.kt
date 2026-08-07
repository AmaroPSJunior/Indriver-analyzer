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

    interface OcrResultCallback {
        fun onOcrCompleted(extractedText: String, fullImage: Bitmap?, textBlocks: List<OcrBlock>)
        fun onError(error: Exception)
    }

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                ocrExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: AccessibilityService.ScreenshotResult) {
                        try {
                            val hardwareBuffer = screenshotResult.hardwareBuffer
                            val colorSpace = screenshotResult.colorSpace
                            val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                            
                            // Copiar para software bitmap para manipulação local
                            val softwareBitmap = bitmap?.copy(Bitmap.Config.ARGB_8888, false)
                            hardwareBuffer.close()

                            if (softwareBitmap != null) {
                                processBitmapWithMlKit(softwareBitmap, callback)
                            } else {
                                callback.onError(IllegalStateException("Falha ao converter bitmap da tela."))
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Erro ao extrair bitmap da tela: ${e.message}", e)
                            callback.onError(e)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "Falha na captura de tela por Acessibilidade. Código: $errorCode")
                        callback.onError(RuntimeException("Erro de captura de tela de Acessibilidade (Código $errorCode)"))
                    }
                }
            )
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
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val fullExtractedText = buildFormattedOcrText(visionText)
                val blocks = visionText.textBlocks.map { block ->
                    OcrBlock(
                        text = block.text,
                        boundingBox = block.boundingBox
                    )
                }

                Log.d(TAG, "⚡ ML Kit OCR concluído com sucesso! Texto extraído:\n$fullExtractedText")
                callback.onOcrCompleted(fullExtractedText, bitmap, blocks)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Erro no reconhecimento de texto pelo ML Kit: ${e.message}", e)
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
