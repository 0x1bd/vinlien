package org.kvxd.vinlien.android

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.content.edit

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            title = getString(R.string.settings_title)
            setDisplayHomeAsUpEnabled(true)
        }

        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, v.paddingBottom)
            insets
        }

        val prefs = getSharedPreferences(MainActivity.PREF_FILE, MODE_PRIVATE)
        val urlInput = findViewById<EditText>(R.id.urlInput)
        val saveBtn = findViewById<Button>(R.id.saveBtn)

        urlInput.setText(prefs.getString(MainActivity.PREF_SERVER_URL, ""))

        fun save() {
            val raw = urlInput.text.toString().trim()
            if (raw.isBlank()) return
            val url = if (raw.startsWith("http://") || raw.startsWith("https://")) raw
            else "http://$raw"
            prefs.edit { putString(MainActivity.PREF_SERVER_URL, url) }
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            finish()
        }

        saveBtn.setOnClickListener { save() }
        urlInput.setOnEditorActionListener { _, _, _ -> save(); true }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
