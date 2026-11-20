package com.pierreebba.sharedlists

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

class QuickNoteActivity : AppCompatActivity() {

    private var sending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quick_note)

        val me = AppPrefs.getCurrentUser(this)
        val partner = AppPrefs.getPartnerName(this)

        val backdrop = findViewById<View>(R.id.quickNoteBackdrop)
        val subtitle = findViewById<TextView>(R.id.quickNoteSubtitle)
        val closeBtn = findViewById<TextView>(R.id.quickNoteCloseBtn)
        val input = findViewById<EditText>(R.id.quickNoteInput)
        val counter = findViewById<TextView>(R.id.quickNoteCounter)
        val cancelBtn = findViewById<TextView>(R.id.quickNoteCancelBtn)
        val sendBtn = findViewById<TextView>(R.id.quickNoteSendBtn)

        subtitle.text = "From $me → $partner"
        input.hint = "Write a note for $partner ♥..."
        counter.text = "0 / 240"

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val len = s?.length ?: 0
                counter.text = "$len / 240"
            }
        })

        backdrop.setOnClickListener { if (!sending) finish() }
        closeBtn.setOnClickListener { if (!sending) finish() }
        cancelBtn.setOnClickListener { if (!sending) finish() }

        sendBtn.setOnClickListener {
            if (sending) return@setOnClickListener
            val text = input.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, "Write a short note first ♥", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            sending = true
            sendBtn.isEnabled = false
            sendBtn.text = "Sending..."

            val appCtx = applicationContext
            val (newState, newNote) = AppPrefs.writeMyNote(appCtx, text)
            NoteWidgetProvider.updateAllWidgets(appCtx)

            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(input.windowToken, 0)

            executor.execute {
                val synced = NoteFetcher.syncWrittenNote(appCtx, newState, newNote)
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        val msg = if (synced) "Note sent to $partner ♥" else "Saved locally — will sync soon"
                        Toast.makeText(appCtx, msg, Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            }
        }

        input.requestFocus()
        input.postDelayed({
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }, 120)
    }

    companion object {
        private val executor = Executors.newSingleThreadExecutor()
    }
}
