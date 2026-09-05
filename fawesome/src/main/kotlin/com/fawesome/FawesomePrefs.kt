package com.fawesome

import android.app.AlertDialog
import android.content.Context
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey

object FawesomePrefs {
    private const val LAYOUT_KEY = "fawesome_layout"
    private const val DEFAULT_LAYOUT = "parent=Home" // Home sebagai default

    fun getLayout(): String {
        return (getKey(LAYOUT_KEY) as? String) ?: DEFAULT_LAYOUT
    }

    fun setLayout(layout: String) {
        setKey(LAYOUT_KEY, layout)
    }

    fun showLayoutDialog(context: Context, onSave: () -> Unit) {
        val current = getLayout()
        
        // Daftar opsi halaman depan berdasarkan data dari base country & home
        val options = listOf(
            "parent=Home" to "Home",
            "keys=82804" to "Italian",
            "keys=82798" to "French",
            "keys=82806" to "Japanese",
            "keys=82807" to "Korean",
            "keys=82801" to "German",
            "keys=82809" to "Chinese",
            "keys=82811" to "Portuguese",
            "keys=82813" to "Russian",
            "keys=82815" to "Persian",
            "keys=82946" to "Bollywood"
        )
        
        val checkedItem = options.indexOfFirst { it.first == current }.coerceAtLeast(0)

        AlertDialog.Builder(context)
            .setTitle("Pilih Halaman Depan Fawesome")
            .setSingleChoiceItems(options.map { it.second }.toTypedArray(), checkedItem) { _, which ->
                val selected = options[which].first
                setLayout(selected)
            }
            .setPositiveButton("Simpan") { _, _ ->
                onSave()
            }
            .setNegativeButton("Batal", null)
            .show()
    }
}