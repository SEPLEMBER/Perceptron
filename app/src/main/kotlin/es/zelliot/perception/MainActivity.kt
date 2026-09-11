package es.zelliot.perceptron

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.AlphaAnimation
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val imagePerceptron = findViewById<ImageView>(R.id.imagePerceptron)

        // Лёгкая анимация (плавное появление)
        val fadeIn = AlphaAnimation(0f, 1f)
        fadeIn.duration = 700 // Длительность появления 1 секунда (можно вернуть 700)
        fadeIn.fillAfter = true // Сохраняет конечное состояние (полная видимость) после анимации
        
        // Если вы хотите, чтобы изображение постоянно мигало (как в исходном коде), 
        // раскомментируйте следующие две строки, а строку выше (fadeIn.fillAfter) удалите:
        // fadeIn.repeatCount = android.view.animation.Animation.INFINITE
        // fadeIn.repeatMode = android.view.animation.Animation.REVERSE

        imagePerceptron.startAnimation(fadeIn)

        // Запуск перехода через 2 секунды (2000 миллисекунд)
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, TesseractActivity::class.java))
            finish() // Закрываем MainActivity, чтобы пользователь не мог вернуться к сплэш-скрину кнопкой "Назад"
        }, 900) 
    }
}
