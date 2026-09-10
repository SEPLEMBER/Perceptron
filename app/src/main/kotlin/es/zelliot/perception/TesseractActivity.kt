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
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
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
import es.zelliot.perceptron.databinding.ActivityTesseractBinding
import kotlinx.coroutines.*
import java.util.regex.Pattern

class TesseractActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTesseractBinding
    private lateinit var highlighter: TesseractHighlighter
    
    private val activityScope = CoroutineScope(Dispatchers.Main + Job())
    private var currentFileUri: Uri? = null

    // --- ПЕРЕМЕННЫЕ ДЛЯ ПОИСКА И СКРОЛЛА ---
    private var isSearchPanelOpen = false
    private var searchMatches = listOf<IntRange>()
    private var currentMatchIndex = -1
    private var searchDebounceJob: Job? = null

    private val openFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            currentFileUri = uri
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {
                // Ignore permission errors gracefully
            }
            loadFileContent(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        binding = ActivityTesseractBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSyntaxHighlighting()
        setupButtons()
        setupOverlays()
        checkIntentForShortcut()
        
        // Инициализация новых функций
        setupSearchAndScroll()
        
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { 
                if (isSearchPanelOpen) {
                    closeSearchPanel() // Сначала закрываем панель поиска
                } else {
                    showExitConfirmationDialog() 
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, callback)
    }

    private fun setupSyntaxHighlighting() {
        highlighter = TesseractHighlighter(binding.etScript, lifecycle)
        binding.etScript.addTextChangedListener(highlighter)
    }

    private fun setupButtons() {
        binding.btnClear.setOnClickListener { showClearConfirmationDialog() }
        binding.btnExecute.setOnClickListener { executeScript(binding.etScript.text.toString()) }
        binding.btnStop.setOnClickListener {
            activityScope.coroutineContext[Job]?.cancelChildren()
            showToast(getString(R.string.toast_stopped))
        }
        binding.btnOpen.setOnClickListener {
            openFileLauncher.launch(arrayOf("*/*", "text/plain", "application/octet-stream"))
        }
        binding.btnShortcut.setOnClickListener { showShortcutDialog() }
        binding.btnExit.setOnClickListener { showExitConfirmationDialog() }
    }
    
    private fun showExitConfirmationDialog() {
        val darkContext = ContextThemeWrapper(this, R.style.DarkDialogTheme)
        AlertDialog.Builder(darkContext)
            .setTitle(getString(R.string.dialog_exit_title))
            .setMessage(getString(R.string.dialog_exit_message))
            .setPositiveButton(getString(R.string.dialog_yes)) { _, _ -> finish() }
            .setNegativeButton(getString(R.string.dialog_no), null)
            .show()
    }
    
    private fun showClearConfirmationDialog() {
        if (binding.etScript.text.isNullOrEmpty() && currentFileUri == null) return
        
        val darkContext = ContextThemeWrapper(this, R.style.DarkDialogTheme)
        AlertDialog.Builder(darkContext)
            .setTitle("Clear Editor")
            .setMessage("Are you sure you want to clear the current script?")
            .setPositiveButton(getString(R.string.dialog_yes)) { _, _ -> 
                binding.etScript.text.clear()
                currentFileUri = null
                showToast(getString(R.string.toast_cleared))
            }
            .setNegativeButton(getString(R.string.dialog_no), null)
            .show()
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
        binding.overlayResult.animate().alpha(1f).scaleY(1f).scaleX(1f).setDuration(200).start()
    }

    private fun hideResult() {
        binding.dimView.animate().alpha(0f).setDuration(150).withEndAction { binding.dimView.visibility = View.GONE }.start()
        binding.overlayResult.animate().alpha(0f).scaleY(0.95f).scaleX(0.95f).setDuration(150).withEndAction {
            binding.overlayResult.visibility = View.GONE
            binding.overlayResult.alpha = 1f
            binding.overlayResult.scaleY = 1f
            binding.overlayResult.scaleX = 1f
        }.start()
    }

    data class ConstantDef(val name: String, val defaultValue: Double, val matchRange: IntRange)

    private fun extractConstants(script: String): List<ConstantDef> {
        val constants = mutableListOf<ConstantDef>()
        val regex = Regex("""(?:val|var)\s+([a-zA-Z_][a-zA-Z0-9_]*)\s*=\s*(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)""", RegexOption.IGNORE_CASE)
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
        val builder = AlertDialog.Builder(darkContext).setTitle(getString(R.string.dialog_params_title))
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 20) }
        val editTexts = mutableMapOf<String, EditText>()

        for (const in constants) {
            val tv = TextView(this).apply {
                text = const.name; setTextColor(Color.parseColor("#FFAA00")); textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            val et = EditText(this).apply {
                setText(const.defaultValue.toString())
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                setTextColor(Color.parseColor("#00E676")); setHintTextColor(Color.GRAY)
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FFAA00"))
                setLongClickable(false); setTextIsSelectable(false)
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
            layout.addView(tv); layout.addView(et)
        }

        val scrollView = ScrollView(this).apply { 
            addView(layout, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        builder.setView(scrollView)

        builder.setPositiveButton(getString(R.string.btn_execute)) { _, _ ->
            val regex = Regex("""(?:val|var)\s+([a-zA-Z_][a-zA-Z0-9_]*)\s*=\s*(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)""", RegexOption.IGNORE_CASE)
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

            binding.etScript.setText(sb.toString())
            executeScript(sb.toString())
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
        val builder = AlertDialog.Builder(darkContext).setTitle(getString(R.string.dialog_shortcut_title))
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 20) }
        val etTitle = EditText(this).apply {
            hint = getString(R.string.hint_shortcut_name); setTextColor(Color.parseColor("#00E676")); setHintTextColor(Color.GRAY)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FFAA00"))
            setLongClickable(false); setTextIsSelectable(false)
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
            if (title.isNotEmpty()) createShortcut(title) else showToast(getString(R.string.toast_enter_shortcut_name))
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
            .setShortLabel(title).setLongLabel(title)
            .setIcon(IconCompat.createWithResource(this, android.R.drawable.ic_menu_edit))
            .setIntent(intent).build()
        ShortcutManagerCompat.requestPinShortcut(this, shortcut, null)
        showToast(getString(R.string.toast_shortcut_created, title))
    }

    private fun executeScript(script: String) {
        activityScope.coroutineContext[Job]?.cancelChildren()
        
        activityScope.launch {
            try {
                val systemResult = try {
                    withContext(Dispatchers.Default) {
                        withTimeout(3000) { TesseractEngine2.evaluateSystemCommand(this@TesseractActivity, script) }
                    }
                } catch (e: TimeoutCancellationException) { getString(R.string.error_timeout_system) }
                
                if (systemResult.isNotEmpty()) {
                    if (systemResult == "__TESSERACT_CLEAR__") {
                        binding.etScript.text.clear()
                        showToast(getString(R.string.toast_screen_cleared))
                    } else { showResult(systemResult) }
                    return@launch
                }

                val isEngine4Script = Regex("\\b(matrix|complex|gcd|lcm|factorial|comb|perm|is_finite|is_integer|is_close|rational|differentiate|integrate|simplify|solve|factor|expand|mempty|mappend|fmap|ap|bind|pure|transpose|det|inverse|dot|norm|cross|identity|zeros|ones|hypot|atan2|degrees|radians|sign|clamp|arg|conj|real|imag)\\b|\\*\\*").containsMatchIn(script)

                val result = try {
                    withContext(Dispatchers.Default) {
                        withTimeout(3000) {
                            if (isEngine4Script) {
                                TesseractEngine4.evaluate(this@TesseractActivity, script, emptyMap())
                            } else {
                                TesseractEngine3.evaluate(this@TesseractActivity, script, emptyMap())
                            }
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
                    
                    if (actualOutput.isNotEmpty() && actualOutput != "void") showResult(actualOutput) 
                    else showResult(getString(R.string.toast_script_executed_exit))
                    
                    showToast(getString(R.string.toast_exiting_in_ms, delayMs))
                    delay(delayMs)
                    finish()
                } else {
                    showResult(result)
                }

            } catch (e: TesseractOpenActCommand3) {
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
            } catch (e: TesseractOpenActCommand4) {
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
                // Expected behavior
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

    private fun showToast(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    override fun onDestroy() {
        super.onDestroy()
        if (::highlighter.isInitialized) {
            binding.etScript.removeTextChangedListener(highlighter)
            highlighter.cleanup()
        }
        activityScope.cancel()
    }

    // ========================================================================
    // НОВЫЕ ФУНКЦИИ: ПОИСК, ЗАМЕНА И БЫСТРЫЙ СКРОЛЛ
    // ========================================================================
    private fun setupSearchAndScroll() {
        setupSwipeGestures()
        setupSearchListeners()
        setupQuickScroll()
    }

    private fun setupSwipeGestures() {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val deltaX = e2.x - (e1?.x ?: 0f)
                val deltaY = e2.y - (e1?.y ?: 0f)
                
                // Горизонтальный свайп, достаточно длинный и быстрый
                if (Math.abs(deltaX) > Math.abs(deltaY) && Math.abs(deltaX) > 100f && Math.abs(velocityX) > 100f) {
                    if (deltaX < 0) {
                        openSearchPanel() // Свайп справа налево
                        return true
                    } else if (isSearchPanelOpen) {
                        closeSearchPanel() // Свайп слева направо (только если открыто)
                        return true
                    }
                }
                return false
            }
        })

        // Вешаем на корневой layout, чтобы свайп работал в любом месте экрана
        binding.root.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false // не блокируем обычные клики
        }
    }

    private fun openSearchPanel() {
        isSearchPanelOpen = true
        binding.searchPanel.visibility = View.VISIBLE
        binding.searchPanel.alpha = 0f
        binding.searchPanel.animate().alpha(1f).setDuration(200).start()
        binding.etSearch.requestFocus()
        performSearch()
    }

    private fun closeSearchPanel() {
        isSearchPanelOpen = false
        binding.searchPanel.animate().alpha(0f).setDuration(150).withEndAction {
            binding.searchPanel.visibility = View.GONE
            binding.etSearch.clearFocus()
            hideKeyboard()
        }.start()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(currentFocus?.windowToken, 0)
    }

    private fun setupSearchListeners() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchDebounceJob?.cancel()
                searchDebounceJob = activityScope.launch {
                    delay(300) // Debounce 300мс
                    performSearch()
                }
            }
        })

        binding.btnSearchPrev.setOnClickListener { goToMatch(-1) }
        binding.btnSearchNext.setOnClickListener { goToMatch(1) }
        binding.btnReplaceOne.setOnClickListener { replaceCurrentMatch() }
        binding.btnReplaceAll.setOnClickListener { showReplaceAllDialog() }
    }

    private fun performSearch() {
        val query = binding.etSearch.text.toString()
        val text = binding.etScript.text.toString()
        searchMatches = mutableListOf()
        currentMatchIndex = -1

        if (query.isNotEmpty()) {
            var index = text.indexOf(query, 0, ignoreCase = true)
            while (index != -1) {
                (searchMatches as MutableList).add(index until index + query.length)
                index = text.indexOf(query, index + query.length, ignoreCase = true)
            }
        }

        if (searchMatches.isNotEmpty()) {
            currentMatchIndex = 0
            highlightAndScrollToMatch()
        } else {
            binding.etScript.setSelection(0)
        }
    }

    private fun goToMatch(direction: Int) {
        if (searchMatches.isEmpty()) return
        currentMatchIndex = (currentMatchIndex + direction).mod(searchMatches.size)
        highlightAndScrollToMatch()
    }

    private fun highlightAndScrollToMatch() {
        val range = searchMatches[currentMatchIndex]
        // setSelection автоматически скроллит EditText к нужной позиции
        binding.etScript.setSelection(range.start, range.endInclusive + 1)
        showToast("Совпадение ${currentMatchIndex + 1} из ${searchMatches.size}")
    }

    private fun replaceCurrentMatch() {
        if (currentMatchIndex == -1 || searchMatches.isEmpty()) return
        
        val text = binding.etScript.text.toString()
        val range = searchMatches[currentMatchIndex]
        val replaceText = binding.etReplace.text.toString()
        
        val newText = text.replaceRange(range, replaceText)
        binding.etScript.setText(newText)
        
        performSearch()
        showToast("Заменено")
    }

    private fun showReplaceAllDialog() {
        val query = binding.etSearch.text.toString()
        if (query.isEmpty()) return

        val darkContext = ContextThemeWrapper(this, R.style.DarkDialogTheme)
        AlertDialog.Builder(darkContext)
            .setTitle("Заменить всё?")
            .setMessage("Заменить все вхождения \"$query\" на \"${binding.etReplace.text}\"?")
            .setPositiveButton("ДА") { _, _ ->
                val text = binding.etScript.text.toString()
                // Pattern.quote экранирует спецсимволы, IGNORE_CASE делает замену нечувствительной к регистру
                val regex = Regex(Pattern.quote(query), RegexOption.IGNORE_CASE)
                val newText = text.replace(regex, binding.etReplace.text.toString())
                
                binding.etScript.setText(newText)
                performSearch()
                showToast("Все совпадения заменены")
            }
            .setNegativeButton("ОТМЕНА", null)
            .show()
    }

    private fun setupQuickScroll() {
        // Обновляем позицию ползунка при скролле EditText
        binding.etScript.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                updateQuickScrollThumb()
                binding.etScript.viewTreeObserver.removeOnGlobalLayoutListener(this)
            }
        })

        binding.etScript.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            updateQuickScrollThumb()
        }

        // Обработка перетаскивания ползунка
        binding.quickScrollThumb.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val parentView = v.parent as View
                    val parentHeight = parentView.height
                    val thumbHeight = v.height
                    val maxThumbTop = parentHeight - thumbHeight
                    
                    var newTop = event.y - (thumbHeight / 2f)
                    newTop = newTop.coerceIn(0f, maxThumbTop.toFloat())
                    v.y = newTop
                    
                    val layout = binding.etScript.layout ?: return@setOnTouchListener true
                    val totalLines = layout.lineCount
                    if (totalLines > 0) {
                        val scrollRatio = newTop / maxThumbTop
                        val targetLine = (totalLines * scrollRatio).toInt().coerceIn(0, totalLines - 1)
                        val targetPos = layout.getLineStart(targetLine)
                        binding.etScript.setSelection(targetPos)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun updateQuickScrollThumb() {
        val layout = binding.etScript.layout ?: return
        val totalLines = layout.lineCount
        if (totalLines == 0) return

        val parentView = binding.quickScrollThumb.parent as View
        val parentHeight = parentView.height
        val thumbHeight = binding.quickScrollThumb.height
        
        val firstVisibleLine = layout.getLineForVertical(binding.etScript.scrollY)
        val scrollRatio = firstVisibleLine.toFloat() / totalLines
        
        val maxThumbTop = parentHeight - thumbHeight
        val newTop = (maxThumbTop * scrollRatio).coerceIn(0f, maxThumbTop.toFloat())
        
        binding.quickScrollThumb.y = newTop
        
        // Динамическая высота ползунка для удобства захвата
        val dynamicHeight = (parentHeight * (parentHeight.toFloat() / binding.etScript.height)).coerceIn(30f, 80f)
        if (Math.abs(binding.quickScrollThumb.height - dynamicHeight) > 5) {
            val params = binding.quickScrollThumb.layoutParams
            params.height = dynamicHeight.toInt()
            binding.quickScrollThumb.layoutParams = params
        }
    }

    // ========================================================================
    // ПОДСВЕЧИВАТЕЛЬ СИНТАКСИСА (без изменений)
    // ========================================================================
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

        private val stringPattern = Pattern.compile("(\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'|`(?:[^`\\\\]|\\\\.)*`)")
        private val commentPattern = Pattern.compile("(//.*|/\\*[\\s\\S]*?\\*/|#.*)")
        private val keywordPattern = Pattern.compile("\\b(if|else|elif|for|while|do|return|break|continue|try|catch|finally|throw|val|var|const|fn|function|class|interface|object|package|import|from|as|in|is|not|and|or|true|false|null|void|exit|assert|to)\\b")
        private val functionPattern = Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(")
        private val numberPattern = Pattern.compile("\\b(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)\\b")
        private val operatorPattern = Pattern.compile("(==|!=|<=|>=|&&|\\|\\||\\+\\+|--|<<|>>|\\+=|-=|\\*=|/=|%=|\\.)")

        private var debounceJob: Job? = null
        private val debounceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        private val debounceDelay = 300L
        @Volatile private var isActive = true

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
                if (text.length > 50000) { applyMinimalHighlighting(editable, text); return }
                
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
                try { editable.removeSpan(span) } catch (e: Exception) {}
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
                            editable.setSpan(ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) 
                        } catch (e: Exception) {}
                    }
                }
            } catch (e: Exception) {}
        }
        
        private fun isInsideStringOrComment(text: String, start: Int, end: Int): Boolean {
            try {
                var inDoubleQuote = false
                var inSingleQuote = false
                var inBacktick = false
                var blockCommentDepth = 0
                var i = 0
                
                while (i < start) {
                    if (text[i] == '\\') {
                        i += 2
                        continue
                    }
                    
                    val char = text[i]
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
                
                if (inDoubleQuote || inSingleQuote || inBacktick || blockCommentDepth > 0) return true
                
                val lineStart = text.lastIndexOf('\n', start - 1) + 1
                val linePrefix = text.substring(lineStart, start)
                if (linePrefix.contains("//") || linePrefix.contains("#")) return true
                
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
