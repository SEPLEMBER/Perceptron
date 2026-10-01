package es.zelliot.perceptron

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class RWStartActivity : AppCompatActivity() {

    private lateinit var tvBootLog: TextView
    private lateinit var tvStatus: TextView

    // Реалистичный дамп инициализации Rowan Engine
    // Правая часть (ASCII) отражает реальные структуры данных и лимиты движка
    private val bootSequence = listOf(
        "ROWAN VM ENGINE v1.0.0 [RELEASE]",
        "Copyright (C) 2026 EFP\n",
        "ARCH: ARM64 / Host Emulation",
        "MODE: Sandboxed Execution Environment\n",
        
        // Hex-дамп инициализации внутреннего состояния (как реальный hexdump -C)
        "00000000: 52 4F 57 41 4E 5F 56 4D  5F 49 4E 49 54 00 00 00  |ROWAN_VM_INIT...|",
        "00000010: 42 55 49 4C 54 49 4E 5F  52 45 47 3A 20 20 20 20  |BUILTIN_REG:    |",
        "00000020: 73 74 6F 72 65 38 00 6C  6F 61 64 38 00 61 6C 6C  |store8.load8.all|",
        "00000030: 6F 63 00 66 72 65 65 00  73 79 73 63 61 6C 6C 00  |oc.free.syscall.|",
        "00000040: 48 45 41 50 5F 4C 49 4D  49 54 3A 20 31 30 4D 42  |HEAP_LIMIT: 10MB|",
        "00000050: 53 54 41 43 4B 5F 44 45  50 54 48 3A 20 31 30 30  |STACK_DEPTH: 100|",
        "00000060: 30 00 00 00 00 00 00 00  00 00 00 00 00 00 00 00  |0...............|\n",
        
        "PARSING AST TABLES... [OK]",
        "ALLOCATING BYTE MEMORY (Map<Long, Byte>)... [OK]",
        "REGISTERING GLOBAL ENVIRONMENT... [OK]",
        "SANDBOX LIMITS: MAX_ITER=100000, MAX_MEM=10M [OK]",
        "JMP TO USER SPACE..."
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rw_start)

        tvBootLog = findViewById(R.id.tvBootLog)
        tvStatus = findViewById(R.id.tvStatus)

        startBootSequence()
    }

    private fun startBootSequence() {
        lifecycleScope.launch {
            // Быстрый вывод: ~35-40 мс на строку. 
            // Весь лог пройдет за ~700 мс, создавая эффект мгновенной инициализации железа.
            for (line in bootSequence) {
                tvBootLog.append("$line\n")
                delay(40) 
            }

            // Запускаем анимацию точек параллельно
            launch {
                animateDots()
            }

            // Ждем оставшееся время до 1300 мс и переходим в основной редактор
            delay(600) 
            startActivity(Intent(this@RWStartActivity, RowanActivity::class.java))
            finish()
            // Плавное, но быстрое затухание, как переключение видеосигнала
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    private suspend fun animateDots() {
        val baseText = "Инициализация"
        val dots = listOf(".", "..", "...")
        var index = 0
        
        while (true) {
            tvStatus.text = "$baseText ${dots[index]}"
            index = (index + 1) % dots.size
            delay(200) // Чуть более резкое мигание для эффекта терминала
        }
    }
}
