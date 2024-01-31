package eu.kanade.tachiyomi.animeextension.id.nekopoi

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlin.system.exitProcess

class NekopoiUrlActivity : Activity() {

    private val tag = "NekopoiUrlActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pathSegments = intent?.data?.pathSegments

        if (pathSegments != null && pathSegments.size == 1) {
            val mainIntent = Intent().apply {
                action = "eu.kanade.tachiyomi.ANIMESEARCH"
                putExtra("query", "${Nekopoi.INTENT_PREFIX}${pathSegments[1]}")
                putExtra("filter", packageName)
            }
            try {
                startActivity(mainIntent)
            } catch (e: ActivityNotFoundException) {
                Log.e(tag, e.toString())
            }
        } else {
            Log.e(tag, "could not parse uri from intent $intent")
        }
        finish()
        exitProcess(0)
    }
}
