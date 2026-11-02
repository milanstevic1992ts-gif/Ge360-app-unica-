package it.edilmilan.ge360.attrezzi

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import it.edilmilan.ge360.cantieri.JobsiteRecord
import java.util.Locale

object JobResourceShare {

    private enum class Scope { TOOLS, MATERIALS, ALL }

    fun show(activity: Activity, job: JobsiteRecord, rows: List<JobResource>) {
        if (rows.isEmpty()) {
            Toast.makeText(activity, "La lista del cantiere è vuota", Toast.LENGTH_LONG).show()
            return
        }

        AlertDialog.Builder(activity)
            .setTitle("📤 Condividi lista")
            .setItems(arrayOf("🔧 Solo attrezzi", "📦 Solo materiali", "📋 Attrezzi + materiali")) { _, which ->
                val scope = when (which) {
                    0 -> Scope.TOOLS
                    1 -> Scope.MATERIALS
                    else -> Scope.ALL
                }
                val text = buildMessage(job, rows, scope)
                if (text == null) {
                    val label = if (scope == Scope.TOOLS) "attrezzi" else "materiali"
                    Toast.makeText(activity, "Nessun $label nella lista", Toast.LENGTH_LONG).show()
                } else {
                    shareToWhatsApp(activity, text)
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun buildMessage(job: JobsiteRecord, rows: List<JobResource>, scope: Scope): String? {
        val tools = rows
            .filter { it.kind == ResourceKind.TOOL }
            .sortedBy { it.name.lowercase(Locale.ITALY) }
        val materials = rows
            .filter { it.kind == ResourceKind.MATERIAL }
            .sortedBy { it.name.lowercase(Locale.ITALY) }

        if (scope == Scope.TOOLS && tools.isEmpty()) return null
        if (scope == Scope.MATERIALS && materials.isEmpty()) return null
        if (scope == Scope.ALL && tools.isEmpty() && materials.isEmpty()) return null

        return buildString {
            append("🏗️ CANTIERE ${job.code}\n")
            append(job.displayTitle())
            if (job.address.isNotBlank() && job.address.trim() != job.displayTitle().trim()) {
                append("\n📍 ${job.address.trim()}")
            }
            append("\n")

            if (scope != Scope.MATERIALS && tools.isNotEmpty()) {
                append("\n🔧 ATTREZZI DA PRENDERE\n")
                tools.forEach { item ->
                    append("☐ ${item.name}")
                    if (item.quantity > 1.0) append(" — ${quantityText(item)}")
                    append("\n")
                }
            }

            if (scope != Scope.TOOLS && materials.isNotEmpty()) {
                append("\n📦 MATERIALI\n")
                materials.forEach { item ->
                    append("☐ ${item.name} — ${quantityText(item)}\n")
                }
            }

            append("\nGE360 · Preparazione cantiere")
        }.trim()
    }

    private fun quantityText(item: JobResource): String {
        val value = clean(item.quantity)
        val unit = item.unit.trim()
        return if (unit.equals("sacco", ignoreCase = true) || unit.equals("sacchi", ignoreCase = true)) {
            if (item.quantity == 1.0) "$value sacco" else "$value sacchi"
        } else {
            "$value $unit".trim()
        }
    }

    private fun clean(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.ITALY, "%.2f", value)

    private fun shareToWhatsApp(activity: Activity, text: String) {
        val baseIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }

        val packages = listOf("com.whatsapp", "com.whatsapp.w4b")
        for (packageName in packages) {
            try {
                activity.startActivity(Intent(baseIntent).apply { setPackage(packageName) })
                return
            } catch (_: ActivityNotFoundException) {
                // Prova WhatsApp Business o il selettore Android.
            }
        }

        activity.startActivity(Intent.createChooser(baseIntent, "Condividi lista cantiere"))
    }
}
