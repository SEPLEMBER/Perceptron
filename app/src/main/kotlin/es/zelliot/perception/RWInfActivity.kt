package es.zelliot.perceptron

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import es.zelliot.perceptron.databinding.ActivityRwInfBinding 

class RWInfActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRwInfBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRwInfBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        
        // ПРОСТО И НАДЕЖНО: Хардкод версии, как вы и предложили
        binding.tvVersion.text = "Version: 1.0.0 (Stable)"
    }
}
