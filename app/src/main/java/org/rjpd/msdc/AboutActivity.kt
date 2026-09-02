package org.rjpd.msdc

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class AboutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        val rootView = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            windowInsets
        }

        findViewById<TextView>(R.id.version_textview)?.text = "Version ${BuildConfig.VERSION_NAME}"

        findViewById<LinearLayout>(R.id.sideseeing_card).setOnClickListener {
            openUrl("https://sites.usp.br/sideseeing")
        }

        findViewById<LinearLayout>(R.id.github_card).setOnClickListener {
            openUrl("https://github.com/rafaelpezzuto/multi-sensor-data-collection")
        }

        findViewById<LinearLayout>(R.id.privacy_card).setOnClickListener {
            openUrl("https://github.com/rafaelpezzuto/multi-sensor-data-collection/blob/main/privacy-policy.md")
        }

        findViewById<LinearLayout>(R.id.email_card).setOnClickListener {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:rafael.pezzuto@gmail.com")
            }
            try {
                startActivity(intent)
            } catch (_: Exception) {}
        }

        val buttonClose = findViewById<Button>(R.id.close_button)
        buttonClose.setOnClickListener {
            finish()
        }
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (_: Exception) {}
    }
}
