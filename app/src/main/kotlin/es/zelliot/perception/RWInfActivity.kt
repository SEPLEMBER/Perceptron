package es.zelliot.perception

import android.content.pm.PackageInfo
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import es.zelliot.perception.databinding.ActivityRwInfBinding

class RWInfActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRwInfBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRwInfBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        
        // Безопасное получение версии через PackageManager (работает в любых версиях AGP)
        val versionName = try {
            val pInfo: PackageInfo = packageManager.getPackageInfo(packageName, 0)
            pInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
        
        binding.tvVersion.text = "Version: $versionName"
    }
}
