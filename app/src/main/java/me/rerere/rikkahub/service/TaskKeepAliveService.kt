package me.rerere.rikkahub.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.TASK_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.data.task.BackgroundTaskManager
import org.koin.android.ext.android.inject
import org.koin.core.component.KoinComponent

/**
 * [TURBO M1] 后台任务保活前台服务。
 *
 * BackgroundTaskManager 有活跃任务（PENDING/RUNNING）时启动，全部结束/取消后停止。
 * 目的：App 退到后台（甚至进程被 LMK 回收后重启）时，任务轮询/定时器不被杀。
 * 独立于 WebServerService（用户可能不开 WebServer，任务保活不应依赖它）。
 *
 * 通知为低打扰的持续状态通知，显示活跃任务数。
 */
class TaskKeepAliveService : Service(), KoinComponent {

    private val taskManager: BackgroundTaskManager by inject()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var activeObserverJob: kotlinx.coroutines.Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        if (!startForegroundCompat()) {
            stopSelf()
            return START_NOT_STICKY
        }
        // 观察活跃任务数：为 0 时自动停止服务（自清理）
        if (activeObserverJob?.isActive != true) {
            activeObserverJob = serviceScope.launch {
                taskManager.activeTaskCount.collectLatest { count ->
                    updateNotification(count)
                    if (count <= 0) {
                        stopSelf()
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        activeObserverJob?.cancel()
        serviceScope.cancel()
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
    }

    private fun startForegroundCompat(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification(0),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification(0))
            }
            true
        } catch (e: Exception) {
            // 部分 OEM ROM 拒绝 FGS 类型权限时降级：无前台服务也能跑（进程保活失效但任务不丢）
            android.util.Log.e(TAG, "Failed to start foreground service", e)
            false
        }
    }

    private fun updateNotification(activeCount: Int) {
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(activeCount))
        } catch (e: Exception) {
            android.util.Log.w(TAG, "updateNotification failed", e)
        }
    }

    private fun buildNotification(activeCount: Int) =
        NotificationCompat.Builder(this, TASK_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(getString(R.string.task_keepalive_title))
            .setContentText(getString(R.string.task_keepalive_desc, activeCount))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .build()

    companion object {
        private const val TAG = "TaskKeepAliveService"
        const val ACTION_STOP = "me.rerere.rikkahub.action.TASK_KEEPALIVE_STOP"
        const val NOTIFICATION_ID = 2002

        fun start(context: Context) {
            // Android 12+ 后台启动前台服务会抛 ForegroundServiceStartNotAllowedException，
            // 失败时静默降级（保活失效但任务不丢，下次进前台再拉起）。
            runCatching {
                context.startForegroundService(
                    Intent(context, TaskKeepAliveService::class.java)
                )
            }.onFailure {
                android.util.Log.w(TAG, "start foreground service failed (background restriction)", it)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, TaskKeepAliveService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
