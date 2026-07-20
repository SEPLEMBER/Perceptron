package es.zelliot.perceptron

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val textPerceptron = findViewById<TextView>(R.id.textPerceptron)

        // Лёгкая анимация (появление)
        val fadeIn = AlphaAnimation(0f, 1f)
        fadeIn.duration = 700
        fadeIn.repeatCount = Animation.INFINITE
        fadeIn.repeatMode = Animation.REVERSE
        textPerceptron.startAnimation(fadeIn)

        // Запуск перехода через 2 секунды
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, TesseractActivity::class.java))
            finish()
        }, 1300)
    }
}
