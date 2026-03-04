package com.gradusxaker.carfuelmonitor

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private val SPLASH_TIME_OUT: Long = 2500 // 2.5 секунды

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Анимация появления
        val imgCar = findViewById<ImageView>(R.id.imgCar)
        val txtTitle = findViewById<TextView>(R.id.txtTitle)
        val txtSubtitle = findViewById<TextView>(R.id.txtSubtitle)

        // Анимация масштабирования
        val scaleUp = AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
        imgCar.startAnimation(scaleUp)

        // Переход к MainActivity
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val intent = Intent(this@SplashActivity, MainActivity::class.java)
                startActivity(intent)
                finish()
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            } catch (e: Exception) {
                e.printStackTrace()
                finish()
            }
        }, SPLASH_TIME_OUT)
    }
}
