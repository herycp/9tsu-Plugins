package com.ninetsufix

import android.app.AlertDialog
import android.content.Context
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey

object NineTsuPrefs {
    private const val DOMAIN_KEY = "ninetsu_domain"
    private const val DEFAULT_DOMAIN = "in"

    fun getDomain(): String {
        return (getKey(DOMAIN_KEY) as? String) ?: DEFAULT_DOMAIN
    }

    fun setDomain(domain: String) {
        setKey(DOMAIN_KEY, domain)
    }

    fun showDomainDialog(context: Context, onSave: () -> Unit) {
        val current = getDomain()
        val options = listOf("in" to "9tsu.in", "vip" to "9tsu.vip")
        val checkedItem = options.indexOfFirst { it.first == current }.coerceAtLeast(0)

        AlertDialog.Builder(context)
            .setTitle("Pilih Domain 9tsu")
            .setSingleChoiceItems(options.map { it.second }.toTypedArray(), checkedItem) { dialog, which ->
                val selected = options[which].first
                setDomain(selected)
            }
            .setPositiveButton("Simpan") { _, _ ->
                onSave()
            }
            .setNegativeButton("Batal", null)
            .show()
    }
}
