package dev.amenhancer.module

class LyricWaveApplication : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        dev.amenhancer.module.feedback.FeedbackRuntime.initialize(this) {
            getSharedPreferences("lyricwave_feedback_config", MODE_PRIVATE).getBoolean("enabled", true)
        }
    }
}
