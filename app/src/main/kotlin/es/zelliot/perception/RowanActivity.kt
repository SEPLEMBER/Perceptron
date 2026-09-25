package es.zelliot.perceptron

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.Spannable
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.ActionMode
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import es.zelliot.perceptron.databinding.ActivityRowanBinding
import kotlinx.coroutines.*
import java.util.regex.Pattern

class RowanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRowanBinding
    private lateinit var highlighter: RowanHighlighter
    private val activityScope = CoroutineScope(Dispatchers.Main + Job())
    private var currentFileUri: Uri? = null
    private var isSearchPanelOpen = false
    private var searchMatches = listOf<IntRange>()
    private var currentMatchIndex = -1
    private var searchDebounceJob: Job? = null
    private var isResultExpanded = false
    private var savedMaxHeight = 0

    private val gestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val deltaX = e2.x - (e1?.x ?: 0f)
                if (kotlin.math.abs(deltaX) > kotlin.math.abs(e2.y - (e1?.y ?: 0f)) && kotlin.math.abs(deltaX) > 100f && kotlin.math.abs(velocityX) > 100f) {
                    if (deltaX < 0) { openSearchPanel(); return true } 
                    else if (isSearchPanelOpen) { closeSearchPanel(); return true }
                }
                return false
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private val openFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            currentFileUri = uri
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) {}
            loadFileContent(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        binding = ActivityRowanBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupSyntaxHighlighting()
        setupButtons()
        setupOverlays()
        setupSearchAndScroll()
        
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { 
                if (isSearchPanelOpen) closeSearchPanel() else showExitConfirmationDialog() 
            }
        })
    }

    private fun setupSyntaxHighlighting() {
        highlighter = RowanHighlighter(binding.etScript, lifecycle)
        binding.etScript.addTextChangedListener(highlighter)
    }

    private fun setupButtons() {
        binding.btnClear.setOnClickListener { 
            if (binding.etScript.text.isNotEmpty()) {
                binding.etScript.text.clear()
                currentFileUri = null
                showToast("Cleared")
            }
        }
        binding.btnExecute.setOnClickListener { executeScript(binding.etScript.text.toString()) }
        binding.btnStop.setOnClickListener {
            activityScope.coroutineContext[Job]?.cancelChildren()
            showToast("Stopped")
        }
        binding.btnOpen.setOnClickListener { openFileLauncher.launch(arrayOf("*/*", "text/plain")) }
        binding.btnExit.setOnClickListener { showExitConfirmationDialog() }
    }
    
    private fun showExitConfirmationDialog() {
        // ИСПРАВЛЕНО: используем стандартный системный стиль диалога
        AlertDialog.Builder(ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Dialog_Alert))
            .setTitle("Exit Rowan?")
            .setMessage("Terminate the engine?")
            .setPositiveButton("YES") { _, _ -> finishAffinity(); kotlin.system.exitProcess(0) }
            .setNegativeButton("NO", null)
            .show()
    }

    private fun setupOverlays() {
        binding.btnCloseResult.setOnClickListener { hideResult() }
        binding.dimView.setOnClickListener { hideResult() }
        binding.btnCopyResult.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("rowan_result", binding.tvResultContent.text.toString()))
            showToast("Copied")
        }
        binding.btnExpandResult.setOnClickListener {
            if (isResultExpanded) collapseResult() else expandResult()
        }
    }

    private fun showResult(text: String) {
        binding.tvResultContent.text = applyResultSyntax(text)
        binding.tvResultContent.scrollTo(0, 0)
        if (isResultExpanded) collapseResult()
        
        binding.dimView.visibility = View.VISIBLE
        binding.dimView.animate().alpha(1f).setDuration(200).start()
        binding.overlayResult.visibility = View.VISIBLE
        binding.overlayResult.animate().alpha(1f).scaleY(1f).scaleX(1f).setDuration(200).start()
    }

    private fun applyResultSyntax(text: String): Spannable {
        val spannable = android.text.SpannableString(text)
        spannable.setSpan(ForegroundColorSpan(Color.parseColor("#00E5FF")), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val greenColor = Color.parseColor("#A8FF60")
        listOf(Regex("\\bSuccess\\b", RegexOption.IGNORE_CASE), Regex("\\bOK\\b", RegexOption.IGNORE_CASE), Regex("\\btrue\\b", RegexOption.IGNORE_CASE), Regex("\\b\\+\\d+(?:\\.\\d+)?\\b")).forEach { pattern ->
            pattern.findAll(text).forEach { match ->
                spannable.setSpan(ForegroundColorSpan(greenColor), match.range.first, match.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return spannable
    }

    private fun hideResult() {
        if (isResultExpanded) collapseResult()
        binding.dimView.animate().alpha(0f).setDuration(150).withEndAction { binding.dimView.visibility = View.GONE }.start()
        binding.overlayResult.animate().alpha(0f).scaleY(0.95f).scaleX(0.95f).setDuration(150).withEndAction { binding.overlayResult.visibility = View.GONE }.start()
    }
    
    private fun expandResult() {
        savedMaxHeight = binding.tvResultContent.maxHeight
        binding.resultContainer.setPadding(0, 0, 0, 0)
        val params = binding.overlayResult.layoutParams as FrameLayout.LayoutParams
        params.width = FrameLayout.LayoutParams.MATCH_PARENT; params.height = FrameLayout.LayoutParams.MATCH_PARENT; params.gravity = android.view.Gravity.NO_GRAVITY
        binding.overlayResult.layoutParams = params
        binding.tvResultContent.maxHeight = Int.MAX_VALUE
        binding.btnExpandResult.text = "Collapse"; isResultExpanded = true
    }
    
    private fun collapseResult() {
        val paddingPx = (24 * resources.displayMetrics.density).toInt()
        binding.resultContainer.setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
        val params = binding.overlayResult.layoutParams as FrameLayout.LayoutParams
        params.width = FrameLayout.LayoutParams.MATCH_PARENT; params.height = FrameLayout.LayoutParams.WRAP_CONTENT; params.gravity = android.view.Gravity.CENTER
        binding.overlayResult.layoutParams = params
        binding.tvResultContent.maxHeight = savedMaxHeight
        binding.btnExpandResult.text = "Expand"; isResultExpanded = false
    }

    private fun loadFileContent(uri: Uri) {
        try {
            val content = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return
            currentFileUri = uri
            binding.etScript.setText(content)
            showToast("File loaded")
        } catch (e: Exception) { showToast("Error: ${e.message}") }
    }

    private fun executeScript(script: String) {
        activityScope.coroutineContext[Job]?.cancelChildren()
        activityScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    withTimeout(5000) { RowanEngine.evaluate(script.trim()) }
                }
                showResult(result)
            } catch (e: TimeoutCancellationException) { showResult("Error: Execution timeout (>5s)") }
            catch (e: Exception) { showResult("Error: ${e.message ?: "Unknown"}") }
        }
    }

    private fun showToast(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    override fun onDestroy() {
        super.onDestroy()
        if (::highlighter.isInitialized) { binding.etScript.removeTextChangedListener(highlighter); highlighter.cleanup() }
        activityScope.cancel()
    }

    private fun setupSearchAndScroll() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchDebounceJob?.cancel()
                searchDebounceJob = activityScope.launch { delay(150); performSearch() }
            }
        })
        binding.btnSearchPrev.setOnClickListener { goToMatch(-1) }
        binding.btnSearchNext.setOnClickListener { goToMatch(1) }
        binding.btnReplaceOne.setOnClickListener { 
            if (currentMatchIndex >= 0 && searchMatches.isNotEmpty()) {
                val range = searchMatches[currentMatchIndex]
                binding.etScript.text?.replace(range.first, range.last + 1, binding.etReplace.text.toString())
                performSearch()
            }
        }
        binding.btnReplaceAll.setOnClickListener { 
            val q = binding.etSearch.text.toString(); val r = binding.etReplace.text.toString()
            if (q.isNotEmpty()) { binding.etScript.text?.let { binding.etScript.setText(it.toString().replace(q.toRegex(RegexOption.IGNORE_CASE), r)) }; performSearch() }
        }
    }

    private fun openSearchPanel() { isSearchPanelOpen = true; binding.searchPanel.visibility = View.VISIBLE; binding.etSearch.requestFocus(); performSearch() }
    private fun closeSearchPanel() { isSearchPanelOpen = false; binding.searchPanel.visibility = View.GONE; binding.etSearch.clearFocus(); hideKeyboard() }
    private fun hideKeyboard() { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(currentFocus?.windowToken, 0) }

    private fun performSearch() {
        val query = binding.etSearch.text.toString(); val text = binding.etScript.text?.toString() ?: ""
        if (query.isEmpty()) { searchMatches = emptyList(); currentMatchIndex = -1; return }
        try {
            searchMatches = Regex(Regex.escape(query), RegexOption.IGNORE_CASE).findAll(text).map { it.range }.toList()
            currentMatchIndex = if (searchMatches.isNotEmpty()) 0 else -1
            if (currentMatchIndex >= 0) selectMatchAt(currentMatchIndex)
        } catch (_: Exception) { searchMatches = emptyList(); currentMatchIndex = -1 }
    }

    private fun selectMatchAt(index: Int) {
        if (index < 0 || index >= searchMatches.size) return
        val range = searchMatches[index]; val textLen = binding.etScript.text?.length ?: 0
        val safeStart = range.first.coerceIn(0, textLen); val safeEnd = (range.last + 1).coerceIn(0, textLen)
        binding.etScript.setSelection(safeStart, safeEnd)
        binding.etScript.post {
            val layout = binding.etScript.layout ?: return@post
            binding.etScript.scrollTo(0, layout.getLineTop(layout.getLineForOffset(safeStart)))
        }
    }

    private fun goToMatch(direction: Int) {
        if (searchMatches.isEmpty()) return
        currentMatchIndex = if (direction > 0) (currentMatchIndex + 1) % searchMatches.size else if (currentMatchIndex - 1 < 0) searchMatches.size - 1 else currentMatchIndex - 1
        selectMatchAt(currentMatchIndex)
    }

    private class RowanHighlighter(private val editText: EditText, private val lifecycle: androidx.lifecycle.Lifecycle) : TextWatcher {
        private val colorKeyword = Color.parseColor("#C792EA")
        private val colorString = Color.parseColor("#C3E88D")
        private val colorComment = Color.parseColor("#546E7A")
        private val colorNumber = Color.parseColor("#F78C6C")
        private val colorFunction = Color.parseColor("#82AAFF")
        private val colorOperator = Color.parseColor("#89DDFF")

        private val stringPattern = Pattern.compile("(\"(?:[^\"\\\\]|\\\\.)*\")")
        private val commentPattern = Pattern.compile("(#.*)")
        private val keywordPattern = Pattern.compile("\\b(do|set|let|fn|if|while|for|break|continue|match|class|new|self|get|put|super|quote|return|load|store|alloc|free|ptr|random|secrandom|print|read|type|cast|len|list|nth|append|count-bits|rat|and|or|not|true|false|null)\\b")
        private val functionPattern = Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_\\-\\+\\*\\/<>=!\\?]*)\\s*\\(")
        private val numberPattern = Pattern.compile("\\b(-?\\d+(?:/\\d+)?(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)\\b")
        private val operatorPattern = Pattern.compile("(==|!=|<=|>=|&&|\\|\\||\\+\\+|--|<<|>>|\\+=|-=|\\*=|/=|%=|\\.)")

        private var debounceJob: Job? = null
        private val debounceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        @Volatile private var isActive = true

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            if (!isActive || s == null) return
            debounceJob?.cancel()
            debounceJob = debounceScope.launch {
                delay(300)
                if (!isActive) return@launch
                applySyntaxHighlighting(s)
            }
        }
        
        private fun applySyntaxHighlighting(editable: Editable) {
            try {
                val text = editable.toString()
                if (text.length > 50000) return
                val oldSpans = editable.getSpans(0, editable.length, ForegroundColorSpan::class.java)
                oldSpans.forEach { try { editable.removeSpan(it) } catch (_: Exception) {} }
                
                listOf(stringPattern to colorString, commentPattern to colorComment, operatorPattern to colorOperator, keywordPattern to colorKeyword, functionPattern to colorFunction, numberPattern to colorNumber).forEach { (pattern, color) ->
                    val matcher = pattern.matcher(text)
                    while (matcher.find()) {
                        if (!isActive) return
                        val start = matcher.start(); val end = matcher.end()
                        if (start >= 0 && end <= text.length && start < end && !isInsideStringOrComment(text, start)) {
                            try { editable.setSpan(ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) } catch (_: Exception) {}
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        
        private fun isInsideStringOrComment(text: String, pos: Int): Boolean {
            var inQuote = false; var i = 0
            while (i < pos) {
                if (text[i] == '\\') { i += 2; continue }
                if (text[i] == '"') inQuote = !inQuote
                if (!inQuote && text[i] == '#') return true
                i++
            }
            return inQuote
        }
        
        fun cleanup() { isActive = false; debounceJob?.cancel(); debounceScope.cancel() }
    }
}
