package es.zelliot.perceptron

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import es.zelliot.perceptron.databinding.ActivityRwInfBinding

class RWInfActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRwInfBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRwInfBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        
        // Можно динамически подставить версию из BuildConfig
        binding.tvVersion.text = "Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    }
}
