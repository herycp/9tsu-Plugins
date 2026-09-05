// FawesomePrefs.kt
package com.fawesometv

import android.app.AlertDialog
import android.content.Context
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object FawesomePrefs {
    private const val MAIN_PAGE_KEY = "fawesome_main_page"
    private const val DEFAULT_MAIN_PAGE = "home"

    // Daftar statis kategori & negara dengan URL penuh (domain sudah di-fix)
    private val categoryItems = listOf(
        "Action" to "url:https://fawesome.tv/home/new/v453/api/shows.php?searchType=listoflist&keys=9983&siteId=236&country=US",
        "Western" to "url:https://fawesome.tv/home/new/v453/api/shows.php?searchType=listoflist&keys=10019&siteId=236&country=US",
        "Horror" to "url:https://fawesome.tv/home/new/v453/api/shows.php?searchType=listoflist&keys=9244&siteId=236&country=US",
        "Comedy" to "url:https://fawesome.tv/home/new/v453/api/shows.php?searchType=listoflist&keys=9245&siteId=236&country=US",
        "Thriller" to "url:https://fawesome.tv/home/new/v453/api/shows.php?searchType=listoflist&keys=10012&siteId=236&country=US",
        "Romance" to "url:https://fawesome.tv/home/new/v453/api/shows.php?searchType=listoflist&keys=10008&siteId=236&country=US",
        "Sci-Fi" to "url:https://fawesome.tv/home/new/v453/api/shows.php?searchType=listoflist&keys=9247&siteId=236&country=US",
        "LGBTQIA+" to "url:https://fawesome.tv/home/new/v453/api/shows.php?searchType=listoflist&keys=10034&siteId=236&country=US",
        "Faith" to "url:https://fawesome.tv/home/new/v453/api/shows.php?searchType=listoflist&keys=32085&siteId=236&country=US",
        "En Español" to "url:https://fawesome.tv/home/new/v453/api/shows.php?searchType=listoflist&keys=29868&siteId=236&country=US",
        "War" to "url:https://fawesome.tv/home/new/v453/api/shows.php?searchType=listoflist&keys=10015&siteId=236&country=US",
        "Drama" to "url:https://fawesome.tv/home/new/v453/api/shows.php?searchType=listoflist&keys=10033&siteId=236&country=US",
        "Fantasy" to "url:https://fawesome.tv/home/new/v453/api/shows.php?searchType=listoflist&keys=10000&siteId=236&country=US",
        "Documentary" to "url:https://fawesome.tv/home/new/v453/api/shows.php?searchType=listoflist&keys=8694&siteId=236&country=US",
        "Black Cinema" to "url:https://fawesome.tv/home/new/v453/api/shows.php?searchType=listoflist&keys=27392&siteId=236&country=US",
        "Gaming" to "url:https://fawesome.tv/home/new/v453/api/shows.php?searchType=listoflist&keys=107630&siteId=236&country=US",
        "Crime" to "url:https://fawesome.tv/home/new/v453/api/shows.php?searchType=listoflist&keys=27127&siteId=236&country=US",
        "Family" to "url:https://fawesome.tv/home/new/v453/api/shows.php?searchType=listoflist&keys=9991&siteId=236&country=US",
        "Mystery" to "url:https://fawesome.tv/home/new/v453/api/shows.php?searchType=listoflist&keys=27195&siteId=236&country=US",
        "British" to "url:https://fawesome.tv/home/new/v453/api/shows.php?searchType=listoflist&keys=31299&siteId=236&country=US",
        "History" to "url:https://fawesome.tv/home/new/v453/api/shows.php?searchType=listoflist&keys=86055&siteId=236&country=US",
        "Adventure" to "url:https://fawesome.tv/home/new/v453/api/shows.php?searchType=listoflist&keys=31296&siteId=236&country=US",
        "World Cinema" to "url:https://fawesome.tv/home/new/v453/api/shows.php?searchType=listoflist&keys=10024&siteId=236&country=US"
    )

    private val countryItems = listOf(
        "Italian" to "url:https://fawesome.tv/home/new/v453/api/shows.php?searchType=listoflist&keys=82804&siteId=236&country=US",
        "French" to "url:https://fawesome.tv/home/new/v453/api/shows.php?searchType=listoflist&keys=82798&siteId=236&country=US",
        "Japanese" to "url:https://fawesome.tv/home/new/v453/api/shows.php?searchType=listoflist&keys=82806&siteId=236&country=US",
        "Korean" to "url:https://fawesome.tv/home/new/v453/api/shows.php?searchType=listoflist&keys=82807&siteId=236&country=US",
        "German" to "url:https://fawesome.tv/home/new/v453/api/shows.php?searchType=listoflist&keys=82801&siteId=236&country=US",
        "Chinese" to "url:https://fawesome.tv/home/new/v453/api/shows.php?searchType=listoflist&keys=82809&siteId=236&country=US",
        "Portuguese" to "url:https://fawesome.tv/home/new/v453/api/shows.php?searchType=listoflist&keys=82811&siteId=236&country=US",
        "Russian" to "url:https://fawesome.tv/home/new/v453/api/shows.php?searchType=listoflist&keys=82813&siteId=236&country=US",
        "Persian" to "url:https://fawesome.tv/home/new/v453/api/shows.php?searchType=listoflist&keys=82815&siteId=236&country=US",
        "Bollywood" to "url:https://fawesome.tv/home/new/v453/api/shows.php?searchType=listoflist&keys=82946&siteId=236&country=US"
    )

    fun getMainPageType(): String {
        return (getKey(MAIN_PAGE_KEY) as? String) ?: DEFAULT_MAIN_PAGE
    }

    fun setMainPageType(value: String) {
        setKey(MAIN_PAGE_KEY, value)
    }

    suspend fun showMainPageDialog(context: Context, onSave: () -> Unit) {
        val items = mutableListOf<Pair<String, String>>()
        items.add("🏠 Home" to "home")
        items.addAll(categoryItems.map { (name, value) -> "📂 $name" to value })
        items.addAll(countryItems.map { (name, value) -> "🌍 $name" to value })

        val current = getMainPageType()
        val checkedItem = items.indexOfFirst { it.second == current }.coerceAtLeast(0)

        withContext(Dispatchers.Main) {
            AlertDialog.Builder(context)
                .setTitle("Pilih Halaman Depan")
                .setSingleChoiceItems(items.map { it.first }.toTypedArray(), checkedItem) { dialog, which ->
                    val selected = items[which].second
                    setMainPageType(selected)
                }
                .setPositiveButton("Simpan") { _, _ ->
                    onSave()
                }
                .setNegativeButton("Batal", null)
                .show()
        }
    }
}