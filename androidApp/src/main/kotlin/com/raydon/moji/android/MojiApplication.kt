package com.raydon.moji.android

import android.app.Application
import com.raydon.moji.android.data.AndroidSettings
import com.raydon.moji.android.data.MojiDatabase
import com.raydon.moji.android.data.MojiRepository
import com.raydon.moji.android.platform.BackupScheduler
import com.raydon.moji.android.platform.NotificationChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MojiApplication : Application() {
    lateinit var repository: MojiRepository
        private set
    lateinit var settings: AndroidSettings
        private set

    override fun onCreate() {
        super.onCreate()
        repository = MojiRepository(MojiDatabase.get(this))
        settings = AndroidSettings(this)
        NotificationChannels.create(this)
        BackupScheduler.schedule(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { repository.ensureInitialized() }
    }
}
