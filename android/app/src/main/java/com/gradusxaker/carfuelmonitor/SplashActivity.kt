package com.gradusxaker.carfuelmonitor

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.app.ActivityOptions
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private val SPLASH_TIME_OUT: Long = 2500 // 2.5 секунды

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val imgCar = findViewById<ImageView>(R.id.imgCar)
        val txtTitle = findViewById<TextView>(R.id.txtTitle)
        val txtSubtitle = findViewById<TextView>(R.id.txtSubtitle)
        val progressBar = findViewById<ProgressBar>(R.id.progressBar)

        val fadeIn = AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
        val slideIn = AnimationUtils.loadAnimation(this, android.R.anim.slide_in_left)
        imgCar.startAnimation(fadeIn)
        txtTitle.startAnimation(slideIn)
        txtSubtitle.startAnimation(fadeIn)
        progressBar.startAnimation(fadeIn)

        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val intent = Intent(this@SplashActivity, MainActivity::class.java)
                val options = ActivityOptions.makeCustomAnimation(
                    this@SplashActivity,
                    android.R.anim.fade_in,
                    android.R.anim.fade_out
                )
                startActivity(intent, options.toBundle())
                finish()
            } catch (e: Exception) {
                finish()
            }
        }, SPLASH_TIME_OUT)
    }
}
