package org.rjpd.msdc

import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class AboutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        val buttonClose = findViewById<Button>(R.id.close_button)
        buttonClose.setOnClickListener {
            finish()
        }
    }
}
