package es.zelliot.perceptron

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import es.zelliot.perceptron.databinding.ActivityTesseractBinding
import kotlinx.coroutines.*
import java.util.regex.Pattern

class TesseractActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTesseractBinding
    private lateinit var highlighter: TesseractHighlighter
    
    private val activityScope = CoroutineScope(Dispatchers.Main + Job())
    private var currentFileUri: Uri? = null

    private val openFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            currentFileUri = uri
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                // Игнорируем
            }
            loadFileContent(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        binding = ActivityTesseractBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSyntaxHighlighting()
        setupButtons()
        setupOverlays()
        checkIntentForShortcut()
    }

    private fun setupSyntaxHighlighting() {
        highlighter = TesseractHighlighter(binding.etScript, lifecycle)
        binding.etScript.addTextChangedListener(highlighter)
    }

    private fun setupButtons() {
        binding.btnClear.setOnClickListener {
            binding.etScript.text.clear()
            currentFileUri = null
            showToast(getString(R.string.toast_cleared))
        }

        binding.btnExecute.setOnClickListener {
            executeScript(binding.etScript.text.toString())
        }

        binding.btnStop.setOnClickListener {
            activityScope.coroutineContext[Job]?.cancelChildren()
            showToast(getString(R.string.toast_stopped))
        }

        binding.btnOpen.setOnClickListener {
            openFileLauncher.launch(arrayOf("*/*", "text/plain", "application/octet-stream"))
        }

        binding.btnShortcut.setOnClickListener {
            showShortcutDialog()
        }

        binding.btnExit.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_exit_title))
                .setMessage(getString(R.string.dialog_exit_message))
                .setPositiveButton(getString(R.string.dialog_yes)) { _, _ -> finish() }
                .setNegativeButton(getString(R.string.dialog_no), null)
                .show()
        }
    }

    private fun setupOverlays() {
        binding.btnCloseResult.setOnClickListener { hideResult() }
        binding.dimView.setOnClickListener { hideResult() }

        binding.tvResultContent.setOnClickListener {
            val text = binding.tvResultContent.text.toString()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.clipboard_label), text))
            showToast(getString(R.string.toast_copied))
        }
    }

    private fun showResult(text: String) {
        binding.tvResultContent.text = text
        binding.tvResultContent.scrollTo(0, 0)
        
        binding.dimView.visibility = View.VISIBLE
        binding.dimView.alpha = 0f
        binding.dimView.animate().alpha(1f).setDuration(200).start()

        binding.overlayResult.visibility = View.VISIBLE
        binding.overlayResult.alpha = 0f
        binding.overlayResult.scaleY = 0.95f
        binding.overlayResult.scaleX = 0.95f
        binding.overlayResult.animate()
            .alpha(1f)
            .scaleY(1f)
            .scaleX(1f)
            .setDuration(200)
            .start()
    }

    private fun hideResult() {
        binding.dimView.animate().alpha(0f).setDuration(150).withEndAction {
            binding.dimView.visibility = View.GONE
        }.start()

        binding.overlayResult.animate()
            .alpha(0f)
            .scaleY(0.95f)
            .scaleX(0.95f)
            .setDuration(150)
            .withEndAction {
                binding.overlayResult.visibility = View.GONE
                binding.overlayResult.alpha = 1f
                binding.overlayResult.scaleY = 1f
                binding.overlayResult.scaleX = 1f
            }.start()
    }

    data class ConstantDef(val name: String, val defaultValue: Double, val matchRange: IntRange)

    private fun extractConstants(script: String): List<ConstantDef> {
        val constants = mutableListOf<ConstantDef>()
        val regex = Regex("""val\s+([a-zA-Z_][a-zA-Z0-9_]*)\s*=\s*(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)""", RegexOption.IGNORE_CASE)
        for (match in regex.findAll(script)) {
            val name = match.groupValues[1]
            val value = match.groupValues[2].toDoubleOrNull() ?: 0.0
            constants.add(ConstantDef(name, value, match.range))
        }
        return constants
    }

    private fun loadFileContent(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val content = inputStream.bufferedReader().use { it.readText() }
            currentFileUri = uri
            
            val constants = extractConstants(content)
            if (constants.isNotEmpty()) {
                showConstantsDialog(constants, content)
            } else {
                binding.etScript.setText(content)
                showToast(getString(R.string.toast_file_loaded))
            }
        } catch (e: Exception) {
            showToast(getString(R.string.toast_file_error, e.message ?: "Unknown"))
        }
    }

    private fun showConstantsDialog(constants: List<ConstantDef>, originalScript: String) {
        val darkContext = ContextThemeWrapper(this, R.style.DarkDialogTheme)
        val builder = AlertDialog.Builder(darkContext)
        builder.setTitle(getString(R.string.dialog_params_title))

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }

        val editTexts = mutableMapOf<String, EditText>()

        for (const in constants) {
            val tv = TextView(this).apply {
                text = const.name
                setTextColor(Color.parseColor("#FFAA00"))
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            
            val et = EditText(this).apply {
                setText(const.defaultValue.toString())
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                        android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                setTextColor(Color.parseColor("#00E676"))
                setHintTextColor(Color.GRAY)
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FFAA00"))
                setLongClickable(false)
                setTextIsSelectable(false)
                
                val actionModeCallback = object : ActionMode.Callback {
                    override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
                    override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
                    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean = false
                    override fun onDestroyActionMode(mode: ActionMode?) {}
                }
                setCustomSelectionActionModeCallback(actionModeCallback)
                setCustomInsertionActionModeCallback(actionModeCallback)
            }
            
            editTexts[const.name] = et
            layout.addView(tv)
            layout.addView(et)
        }

        val scrollView = ScrollView(this).apply { 
            addView(layout, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        builder.setView(scrollView)

        builder.setPositiveButton(getString(R.string.btn_execute)) { _, _ ->
            val regex = Regex("""val\s+([a-zA-Z_][a-zA-Z0-9_]*)\s*=\s*(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)""", RegexOption.IGNORE_CASE)
            val sb = StringBuilder()
            var lastEnd = 0
            
            for (match in regex.findAll(originalScript)) {
                sb.append(originalScript, lastEnd, match.range.first)
                val name = match.groupValues[1]
                val newValue = editTexts[name]?.text.toString().toDoubleOrNull() ?: match.groupValues[2]
                sb.append("val $name = $newValue")
                lastEnd = match.range.last + 1
            }
            sb.append(originalScript, lastEnd, originalScript.length)

            val newScript = sb.toString()
            binding.etScript.setText(newScript)
            executeScript(newScript)
        }
        
        builder.setNegativeButton(getString(R.string.dialog_cancel)) { _, _ ->
            binding.etScript.setText(originalScript)
        }
        
        builder.show()
    }

    private fun showShortcutDialog() {
        if (currentFileUri == null) {
            showToast(getString(R.string.toast_open_file_first))
            return
        }

        val darkContext = ContextThemeWrapper(this, R.style.DarkDialogTheme)
        val builder = AlertDialog.Builder(darkContext)
        builder.setTitle(getString(R.string.dialog_shortcut_title))

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }

        val etTitle = EditText(this).apply {
            hint = getString(R.string.hint_shortcut_name)
            setTextColor(Color.parseColor("#00E676"))
            setHintTextColor(Color.GRAY)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FFAA00"))
            setLongClickable(false)
            setTextIsSelectable(false)
            
            val actionModeCallback = object : ActionMode.Callback {
                override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
                override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
                override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean = false
                override fun onDestroyActionMode(mode: ActionMode?) {}
            }
            setCustomSelectionActionModeCallback(actionModeCallback)
            setCustomInsertionActionModeCallback(actionModeCallback)
        }
        
        layout.addView(etTitle)
        builder.setView(layout)

        builder.setPositiveButton(getString(R.string.btn_create)) { _, _ ->
            val title = etTitle.text.toString().trim()
            if (title.isNotEmpty()) {
                createShortcut(title)
            } else {
                showToast(getString(R.string.toast_enter_shortcut_name))
            }
        }
        builder.setNegativeButton(getString(R.string.dialog_cancel), null)
        builder.show()
    }

    private fun createShortcut(title: String) {
        val intent = Intent(this, TesseractActivity::class.java).apply {
            action = "es.zelliot.perceptron.ACTION_RUN_SHORTCUT"
            putExtra("SCRIPT_URI", currentFileUri.toString())
        }

        val shortcut = ShortcutInfoCompat.Builder(this, "perceptron_script_${System.currentTimeMillis()}")
            .setShortLabel(title)
            .setLongLabel(title)
            .setIcon(IconCompat.createWithResource(this, android.R.drawable.ic_menu_edit))
            .setIntent(intent)
            .build()

        ShortcutManagerCompat.requestPinShortcut(this, shortcut, null)
        showToast(getString(R.string.toast_shortcut_created, title))
    }

    private fun executeScript(script: String) {
        activityScope.coroutineContext[Job]?.cancelChildren()
        
        activityScope.launch {
            try {
                val systemResult = try {
                    withContext(Dispatchers.Default) {
                        withTimeout(3000) {
                            TesseractEngine2.evaluateSystemCommand(script)
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    getString(R.string.error_timeout_system)
                }
                
                if (systemResult.isNotEmpty()) {
                    if (systemResult == "__TESSERACT_CLEAR__") {
                        binding.etScript.text.clear()
                        showToast(getString(R.string.toast_screen_cleared))
                    } else {
                        showResult(systemResult)
                    }
                    return@launch
                }

                val result = try {
                    withContext(Dispatchers.Default) {
                        withTimeout(3000) {
                            TesseractEngine1.evaluate(script, emptyMap())
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    getString(R.string.error_timeout_script)
                }

                if (result.startsWith("__TESSERACT_EXIT__:")) {
                    val firstLine = result.substringBefore("\n")
                    val actualOutput = result.substringAfter("\n", "").trim()
                    
                    val delayStr = firstLine.removePrefix("__TESSERACT_EXIT__:")
                    val delayMs = (delayStr.toLongOrNull() ?: 0L).coerceAtLeast(0L)
                    
                    if (actualOutput.isNotEmpty() && actualOutput != "void") {
                        showResult(actualOutput)
                    } else {
                        showResult(getString(R.string.toast_script_executed_exit))
                    }
                    
                    showToast(getString(R.string.toast_exiting_in_ms, delayMs))
                    delay(delayMs)
                    finish()
                } else {
                    showResult(result)
                }

            } catch (e: TesseractOpenActCommand) {
                val target = e.packageName
                try {
                    val intent: Intent? = if (target.contains("/")) {
                        val parts = target.split("/", limit = 2)
                        Intent().setClassName(parts[0], parts[1])
                    } else {
                        packageManager.getLaunchIntentForPackage(target)
                    }

                    if (intent != null) {
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        startActivity(intent)
                        showResult(getString(R.string.toast_transition_success, target))
                    } else {
                        showResult(getString(R.string.error_open_target_tips, target))
                    }
                } catch (ex: Exception) {
                    showResult(getString(R.string.error_open_target_exception, target, ex.message ?: "Unknown"))
                }
            } catch (e: CancellationException) {
                // Нормальное поведение при отмене корутины
            } catch (e: Exception) {
                showResult(getString(R.string.error_generic, e.message ?: "Unknown"))
            }
        }
    }

    private fun checkIntentForShortcut() {
        if (intent.action == "es.zelliot.perceptron.ACTION_RUN_SHORTCUT") {
            val uriString = intent.getStringExtra("SCRIPT_URI")
            if (uriString != null) {
                currentFileUri = Uri.parse(uriString)
                loadFileContent(currentFileUri!!)
                Handler(Looper.getMainLooper()).postDelayed({
                    executeScript(binding.etScript.text.toString())
                }, 500)
            }
        }
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::highlighter.isInitialized) {
            binding.etScript.removeTextChangedListener(highlighter)
            highlighter.cleanup()
        }
        activityScope.cancel()
    }

    /**
     * Безопасная подсветка синтаксиса для Perceptron/Tesseract
     */
    private class TesseractHighlighter(
        private val editText: EditText,
        private val lifecycle: androidx.lifecycle.Lifecycle
    ) : TextWatcher {
        
        private val colorKeyword = Color.parseColor("#C792EA")
        private val colorString = Color.parseColor("#C3E88D")
        private val colorComment = Color.parseColor("#546E7A")
        private val colorNumber = Color.parseColor("#F78C6C")
        private val colorFunction = Color.parseColor("#82AAFF")
        private val colorOperator = Color.parseColor("#89DDFF")
        
        private val keywordPattern = Pattern.compile(
            "\\b(if|else|elif|for|while|do|return|break|continue|try|catch|finally|throw|val|var|const|fun|function|class|interface|object|package|import|from|as|in|is|not|and|or|true|false|null|void|open|close|exit|print|println|log|eval|exec|run|system|command)\\b"
        )
        
        private val functionPattern = Pattern.compile(
            "\\b([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\("
        )
        
        private val stringPattern = Pattern.compile(
            "(\"[^\"]*\"|'[^']*'|`[^`]*`)"
        )
        
        private val commentPattern = Pattern.compile(
            "(//.*|/\\*[\\s\\S]*?\\*/|#.*)"
        )
        
        private val numberPattern = Pattern.compile(
            "\\b(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)\\b"
        )
        
        private val operatorPattern = Pattern.compile(
            "(==|!=|<=|>=|&&|\\|\\||\\+\\+|--|<<|>>|\\+=|-=|\\*=|/=|%=)"
        )

        private var debounceJob: Job? = null
        private val debounceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        private val debounceDelay = 300L
        
        @Volatile
        private var isActive = true

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

        override fun afterTextChanged(s: Editable?) {
            if (!isActive || s == null) return
            
            debounceJob?.cancel()
            
            debounceJob = debounceScope.launch {
                delay(debounceDelay)
                if (!isActive) return@launch
                applySyntaxHighlighting(s)
            }
        }
        
        private fun applySyntaxHighlighting(editable: Editable) {
            try {
                val text = editable.toString()
                
                if (text.length > 50000) {
                    applyMinimalHighlighting(editable, text)
                    return
                }
                
                removeOldSpans(editable)
                
                applyPatternSafe(editable, text, stringPattern, colorString)
                applyPatternSafe(editable, text, commentPattern, colorComment)
                applyPatternSafe(editable, text, operatorPattern, colorOperator)
                applyPatternSafe(editable, text, keywordPattern, colorKeyword)
                applyPatternSafe(editable, text, functionPattern, colorFunction)
                applyPatternSafe(editable, text, numberPattern, colorNumber)
                
            } catch (e: Exception) {
                android.util.Log.w("TesseractHighlighter", "Error applying syntax highlighting", e)
            }
        }
        
        private fun applyMinimalHighlighting(editable: Editable, text: String) {
            removeOldSpans(editable)
            applyPatternSafe(editable, text, stringPattern, colorString)
            applyPatternSafe(editable, text, commentPattern, colorComment)
        }
        
        private fun removeOldSpans(editable: Editable) {
            val oldSpans = editable.getSpans(0, editable.length, ForegroundColorSpan::class.java)
            for (span in oldSpans) {
                try {
                    editable.removeSpan(span)
                } catch (e: Exception) {
                    // Игнорируем
                }
            }
        }

        private fun applyPatternSafe(editable: Editable, text: String, pattern: Pattern, color: Int) {
            try {
                val matcher = pattern.matcher(text)
                while (matcher.find()) {
                    if (!isActive) return
                    
                    val start = matcher.start()
                    val end = matcher.end()
                    
                    if (start < 0 || end > text.length || start >= end) continue
                    
                    if (!isInsideStringOrComment(text, start, end)) {
                        try {
                            editable.setSpan(
                                ForegroundColorSpan(color), 
                                start, 
                                end, 
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                        } catch (e: Exception) {
                            // Игнорируем
                        }
                    }
                }
            } catch (e: Exception) {
                // Игнорируем
            }
        }
        
        private fun isInsideStringOrComment(text: String, start: Int, end: Int): Boolean {
            try {
                var inDoubleQuote = false
                var inSingleQuote = false
                var inBacktick = false
                var blockCommentDepth = 0
                var i = 0
                
                while (i < start && i < text.length) {
                    val char = text[i]
                    
                    if (i > 0 && text[i - 1] == '\\') {
                        i++
                        continue
                    }
                    
                    when (char) {
                        '"' -> if (!inSingleQuote && !inBacktick && blockCommentDepth == 0) inDoubleQuote = !inDoubleQuote
                        '\'' -> if (!inDoubleQuote && !inBacktick && blockCommentDepth == 0) inSingleQuote = !inSingleQuote
                        '`' -> if (!inDoubleQuote && !inSingleQuote && blockCommentDepth == 0) inBacktick = !inBacktick
                    }
                    
                    if (blockCommentDepth == 0) {
                        if (i < text.length - 1 && text[i] == '/' && text[i + 1] == '*') {
                            blockCommentDepth++
                            i += 2
                            continue
                        }
                    } else {
                        if (i < text.length - 1 && text[i] == '*' && text[i + 1] == '/') {
                            blockCommentDepth--
                            i += 2
                            continue
                        }
                    }
                    
                    i++
                }
                
                if (inDoubleQuote || inSingleQuote || inBacktick || blockCommentDepth > 0) {
                    return true
                }
                
                val lineStart = text.lastIndexOf('\n', start - 1) + 1
                val linePrefix = text.substring(lineStart, start)
                
                if (linePrefix.contains("//") || linePrefix.contains("#")) {
                    return true
                }
                
                return false
            } catch (e: Exception) {
                return false
            }
        }
        
        fun cleanup() {
            isActive = false
            debounceJob?.cancel()
            debounceScope.cancel()
        }
    }
}
