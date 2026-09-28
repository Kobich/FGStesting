package com.engboost.fgstesting

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobService
import android.app.job.JobScheduler
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val UIDT_LOG_TAG = "UidtStress"
private const val UIDT_CHANNEL_ID = "uidt_stress_channel"
private const val UIDT_TRANSFER_JOB_ID = 2_001
private const val UIDT_TIME_MONITOR_JOB_ID = 2_002
private const val UIDT_NOTIFICATION_TRANSFER_ID = 2_101
private const val UIDT_NOTIFICATION_TIME_ID = 2_102
private const val ONE_GIB = 1_024L * 1_024L * 1_024L

/**
 * Deliberately abusive UIDT experiment. It is not a production use of UIDT.
 *
 * The scheduler setup still obeys UIDT's platform prerequisites: it is initiated by a
 * visible Activity, requests an internet-capable network, declares RUN_USER_INITIATED_JOBS,
 * and exposes an ongoing notification while the job runs. The work itself intentionally
 * simulates transfer/counting to observe when the system stops the job.
 */
object UidtStressJobScheduler {
    fun startTransfer(context: Context): Int = schedule(
        context = context,
        jobId = UIDT_TRANSFER_JOB_ID,
        jobClass = UidtTransferStressJobService::class.java,
    )

    fun startTimeMonitor(context: Context): Int = schedule(
        context = context,
        jobId = UIDT_TIME_MONITOR_JOB_ID,
        jobClass = UidtTimeMonitorStressJobService::class.java,
    )

    fun stopAll(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        scheduler.cancel(UIDT_TRANSFER_JOB_ID)
        scheduler.cancel(UIDT_TIME_MONITOR_JOB_ID)
        Log.i(UIDT_LOG_TAG, "MANUAL_CANCEL all UIDT stress jobs")
    }

    private fun schedule(
        context: Context,
        jobId: Int,
        jobClass: Class<out JobService>,
    ): Int {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        check(scheduler.canRunUserInitiatedJobs()) {
            "RUN_USER_INITIATED_JOBS is unavailable for this app"
        }
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val jobInfo = JobInfo.Builder(jobId, ComponentName(context, jobClass))
            .setUserInitiated(true)
            .setRequiredNetwork(networkRequest)
            // A large estimate prevents a tiny artificial test payload from being interpreted
            // as a job that should finish immediately.
            .setEstimatedNetworkBytes(ONE_GIB, ONE_GIB)
            .build()
        return scheduler.schedule(jobInfo).also { result ->
            Log.i(UIDT_LOG_TAG, "SCHEDULE jobId=$jobId result=$result")
        }
    }
}

abstract class BaseUidtStressJobService : JobService() {
    private val scope = CoroutineScope(Dispatchers.Default)
    private var runningJob: Job? = null
    private var startedAtElapsedMs = 0L

    protected abstract val experimentName: String
    protected abstract val notificationId: Int
    protected abstract suspend fun runExperiment(startElapsedMs: Long)

    override fun onStartJob(params: JobParameters): Boolean {
        val startedAt = SystemClock.elapsedRealtime()
        startedAtElapsedMs = startedAt
        Log.i(
            UIDT_LOG_TAG,
            "START experiment=$experimentName jobId=${params.jobId} elapsedMs=$startedAt",
        )
        createNotificationChannel()
        setNotification(
            params,
            notificationId,
            createNotification(params.jobId),
            JOB_END_NOTIFICATION_POLICY_DETACH,
        )
        runningJob?.cancel()
        runningJob = scope.launch {
            try {
                runExperiment(startedAt)
                Log.i(UIDT_LOG_TAG, "FINISH experiment=$experimentName: completed unexpectedly")
                jobFinished(params, false)
            } catch (cancelled: CancellationException) {
                Log.i(UIDT_LOG_TAG, "CANCELLED experiment=$experimentName")
                throw cancelled
            } catch (error: Throwable) {
                Log.e(UIDT_LOG_TAG, "ERROR experiment=$experimentName", error)
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val elapsedMs = SystemClock.elapsedRealtime()
        val aliveMs = elapsedMs - startedAtElapsedMs
        Log.w(
            UIDT_LOG_TAG,
            "STOP experiment=$experimentName jobId=${params.jobId} " +
                "stopReason=${params.stopReason} aliveMs=$aliveMs elapsedMs=$elapsedMs",
        )
        runningJob?.cancel()
        runningJob = null
        // The experiment must not silently restart; the UI schedules the next explicit run.
        return false
    }

    override fun onDestroy() {
        runningJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    protected fun logHeartbeat(startElapsedMs: Long, detail: String) {
        val aliveMs = SystemClock.elapsedRealtime() - startElapsedMs
        Log.i(UIDT_LOG_TAG, "ALIVE experiment=$experimentName aliveMs=$aliveMs $detail")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            UIDT_CHANNEL_ID,
            "UIDT stress tests",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(jobId: Int) = NotificationCompat.Builder(this, UIDT_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle("UIDT stress test: $experimentName")
        .setContentText("Running; check logcat for liveness and stop reason")
        .setOngoing(true)
        .addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Stop test",
            PendingIntent.getBroadcast(
                this,
                jobId,
                Intent(this, UidtJobStopReceiver::class.java)
                    .setAction(UidtJobStopReceiver.stopAction(jobId)),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()
}

class UidtTransferStressJobService : BaseUidtStressJobService() {
    override val experimentName = "mock upload/download"
    override val notificationId = UIDT_NOTIFICATION_TRANSFER_ID

    override suspend fun runExperiment(startElapsedMs: Long) {
        var iterations = 0L
        var simulatedBytes = 0L
        while (kotlin.coroutines.coroutineContext.isActive) {
            // Deliberately fake I/O: one tick represents 512 KiB uploaded and downloaded.
            simulatedBytes += 1_024L * 1_024L
            iterations++
            if (iterations % 5L == 0L) {
                logHeartbeat(startElapsedMs, "ticks=$iterations simulatedBytes=$simulatedBytes")
            }
            delay(1_000L)
        }
    }
}

class UidtTimeMonitorStressJobService : BaseUidtStressJobService() {
    override val experimentName = "periodic time monitor"
    override val notificationId = UIDT_NOTIFICATION_TIME_ID

    override suspend fun runExperiment(startElapsedMs: Long) {
        var ticks = 0L
        while (kotlin.coroutines.coroutineContext.isActive) {
            ticks++
            logHeartbeat(startElapsedMs, "ticks=$ticks wallTimeMs=${System.currentTimeMillis()}")
            delay(1_000L)
        }
    }
}

class UidtJobStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val jobId = when (intent.action) {
            stopAction(UIDT_TRANSFER_JOB_ID) -> UIDT_TRANSFER_JOB_ID
            stopAction(UIDT_TIME_MONITOR_JOB_ID) -> UIDT_TIME_MONITOR_JOB_ID
            else -> return
        }
        context.getSystemService(JobScheduler::class.java).cancel(jobId)
        Log.i(UIDT_LOG_TAG, "NOTIFICATION_CANCEL jobId=$jobId")
    }

    companion object {
        fun stopAction(jobId: Int) = "com.engboost.fgstesting.action.STOP_UIDT_$jobId"
    }
}
