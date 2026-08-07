package com.uberanalyzer.model

import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

object AccessibilityHierarchyFormatter {

    /**
     * Converts raw AccessibilityNodeInfo tree recursively into a formatted, friendly hierarchy string.
     */
    fun formatNodeTree(node: AccessibilityNodeInfo?, depth: Int = 0, sb: StringBuilder = StringBuilder()): String {
        if (node == null) return sb.toString()
        val indent = "  ".repeat(depth)
        val prefix = if (depth == 0) "📱 " else "├── "

        val rawClass = node.className?.toString() ?: "android.view.View"
        val simpleClass = rawClass.substringAfterLast('.')
        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        
        val visibleContent = when {
            text.isNotBlank() && desc.isNotBlank() -> "\"$text\" ($desc)"
            text.isNotBlank() -> "\"$text\""
            desc.isNotBlank() -> "\"$desc\""
            else -> null
        }

        val attributes = mutableListOf<String>()
        if (node.isClickable) attributes.add("Clicável")
        if (node.isScrollable) attributes.add("Rolável")
        if (!node.isVisibleToUser) attributes.add("Oculto")

        val attrStr = if (attributes.isNotEmpty()) " [${attributes.joinToString(", ")}]" else ""
        val resId = node.viewIdResourceName?.substringAfterLast(":id/") ?: ""
        val idStr = if (resId.isNotBlank()) " #$resId" else ""

        sb.append(indent)
            .append(prefix)
            .append("[").append(simpleClass).append("]").append(idStr)
        
        if (visibleContent != null) {
            sb.append(" : ").append(visibleContent)
        }
        sb.append(attrStr).append("\n")

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                formatNodeTree(child, depth + 1, sb)
                try { child.recycle() } catch (e: Exception) {}
            }
        }
        return sb.toString()
    }

    /**
     * Converts JSON ride payload or raw JSON array into a clean, friendly Accessibility Node Hierarchy listing.
     */
    fun formatJsonToHierarchy(jsonStr: String): String {
        if (jsonStr.isBlank()) {
            return buildEmptyHierarchyText()
        }

        return try {
            val trimmed = jsonStr.trim()
            if (trimmed.startsWith("[")) {
                val array = JSONArray(trimmed)
                formatJsonArrayToHierarchy(array)
            } else if (trimmed.startsWith("{")) {
                val obj = JSONObject(trimmed)
                if (obj.has("rides")) {
                    val ridesArray = obj.getJSONArray("rides")
                    formatJsonArrayToHierarchy(ridesArray)
                } else if (obj.has("mensagem") || obj.has("status")) {
                    val msg = obj.optString("mensagem", "Nenhum elemento detectado")
                    buildMessageHierarchyText(msg)
                } else {
                    formatJsonObjectToHierarchy(obj)
                }
            } else {
                jsonStr
            }
        } catch (e: Exception) {
            jsonStr
        }
    }

    private fun formatJsonArrayToHierarchy(array: JSONArray): String {
        if (array.length() == 0) return buildEmptyHierarchyText()

        val sb = StringBuilder()
        sb.append("📱 HIERARQUIA DE NÓS DE ACESSIBILIDADE\n")
        sb.append("════════════════════════════════════════════════════════\n")
        sb.append("Elementos Detectados na Tela: ${array.length()}\n\n")

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val pos = i + 1
            val passenger = obj.optString("passenger", obj.optString("passageiro", "Passageiro"))
            val rating = obj.optString("rating", "5.0")
            val price = obj.optDouble("price_brl", obj.optDouble("valor_brl", 0.0))
            val pickup = obj.optString("pickup_address", obj.optString("origem", "Não especificado"))
            val dropoff = obj.optString("dropoff_address", obj.optString("destino", "Não especificado"))
            val dist = obj.optDouble("total_distance_km", obj.optDouble("distancia_km", 0.0))
            val timeMin = obj.optInt("estimated_time_min", obj.optInt("tempo_min", 0))
            val perKm = obj.optDouble("earnings_per_km_brl", obj.optDouble("ganho_por_km_brl", 0.0))
            val score = obj.optDouble("score", 0.0)

            sb.append("🔹 [Elemento #$pos] 📦 ViewGroup (CardView - Item de Lista)\n")
            sb.append(" ├── 👤 [TextView] Nome do Passageiro : \"$passenger\"\n")
            if (rating.isNotBlank() && rating != "0.0") {
                sb.append(" ├── ⭐ [TextView] Avaliação : \"$rating ★\"\n")
            }
            sb.append(" ├── 💰 [TextView] Valor da Corrida : \"R$ ${String.format(java.util.Locale.US, "%.2f", price)}\"")
            if (perKm > 0) {
                sb.append(" (R$ ${String.format(java.util.Locale.US, "%.2f", perKm)}/km)")
            }
            sb.append("\n")
            sb.append(" ├── 📍 [TextView] Endereço de Embarque : \"$pickup\"\n")
            sb.append(" ├── 🏁 [TextView] Endereço de Desembarque : \"$dropoff\"\n")
            if (dist > 0) {
                sb.append(" ├── 📐 [TextView] Distância Total : \"${String.format(java.util.Locale.US, "%.1f", dist)} km\"")
                if (timeMin > 0) sb.append(" (Tempo: $timeMin min)")
                sb.append("\n")
            }
            if (score > 0) {
                sb.append(" ├── 🎯 [TextView] Score da Corrida : \"$score\"\n")
            }
            sb.append(" └── 🔘 [Button] Ação de Resposta : \"Aceitar / Oferecer Valor\" [Clicável]\n\n")
        }
        return sb.toString().trimEnd()
    }

    private fun formatJsonObjectToHierarchy(obj: JSONObject): String {
        val sb = StringBuilder()
        sb.append("📱 HIERARQUIA DE NÓS DE ACESSIBILIDADE\n")
        sb.append("════════════════════════════════════════════════════════\n")
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = obj.opt(k)
            sb.append(" ├── [TextView] $k : \"$v\"\n")
        }
        return sb.toString().trimEnd()
    }

    private fun buildEmptyHierarchyText(): String {
        return """
            📱 HIERARQUIA DE NÓS DE ACESSIBILIDADE
            ════════════════════════════════════════════════════════
            Status: Nenhuma corrida ou elemento detectado no momento.
            
            🔹 [ViewGroup] Container Principal
             └── ℹ️ [TextView] Mensagem : "Abra o aplicativo inDrive lado a lado. A hierarquia de nós de acessibilidade da tela será exibida aqui automaticamente."
        """.trimIndent()
    }

    private fun buildMessageHierarchyText(msg: String): String {
        return """
            📱 HIERARQUIA DE NÓS DE ACESSIBILIDADE
            ════════════════════════════════════════════════════════
            Status: Aguardando captura de tela
            
            🔹 [ViewGroup] Container de Status
             └── ℹ️ [TextView] Mensagem : "$msg"
        """.trimIndent()
    }
}
