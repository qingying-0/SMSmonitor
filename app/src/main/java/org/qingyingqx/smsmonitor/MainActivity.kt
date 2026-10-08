package org.qingyingqx.smsmonitor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import org.qingyingqx.smsmonitor.ui.theme.*

class MainActivity : ComponentActivity() {

    // 可观察状态
    private val isMonitoringEnabled = mutableStateOf(false)
    private val isServiceRunning = mutableStateOf(false)
    private val hasExactAlarmPermission = mutableStateOf(true)
    private val hasSmsPermission = mutableStateOf(false)
    private val hasNotificationPermission = mutableStateOf(true)

    /** 每次回到前台自增，驱动 Compose 重新读取监控日志 */
    private val logRefreshTick = mutableStateOf(0)

    // 权限请求启动器
    private lateinit var smsPermissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 停留在本页时定期同步状态。
     * 服务可能在用户注视下被 ROM 杀掉，或从通知栏被停止，
     * 若不轮询，指示灯会与真实状态脱节。
     */
    private val stateTicker = object : Runnable {
        override fun run() {
            isServiceRunning.value = SmsMonitorService.isRunning
            isMonitoringEnabled.value = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
                .getBoolean(Prefs.KEY_MONITORING_ENABLED, false)
            mainHandler.postDelayed(this, SERVICE_STATE_POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initPermissionLaunchers()
        checkPermissions()

        enableEdgeToEdge()
        setContent {
            SMSmonitorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = InkBackground
                ) {
                    HomePage(
                        hasSmsPermission = hasSmsPermission.value,
                        hasNotificationPermission = hasNotificationPermission.value,
                        hasExactAlarmPermission = hasExactAlarmPermission.value,
                        isMonitoringEnabled = isMonitoringEnabled.value,
                        isServiceRunning = isServiceRunning.value,
                        logRefreshTick = logRefreshTick.value,
                        onRequestSmsPermission = { requestSmsPermission() },
                        onRequestNotificationPermission = { requestNotificationPermission() },
                        onRequestExactAlarmPermission = { requestExactAlarmPermission() },
                        onToggleMonitoring = { toggleMonitoring() },
                        onOpenBatterySettings = { requestIgnoreBatteryOptimizations() },
                        onOpenAppDetails = { openAppDetails() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkPermissions()
        // 开始轮询，保证指示灯反映真实状态
        mainHandler.removeCallbacks(stateTicker)
        mainHandler.postDelayed(stateTicker, SERVICE_STATE_POLL_MS)
    }

    override fun onPause() {
        mainHandler.removeCallbacks(stateTicker)
        super.onPause()
    }

    private fun initPermissionLaunchers() {
        smsPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            checkPermissions()
            val allGranted = permissions.values.all { it }
            if (allGranted) {
                Toast.makeText(this, "权限已授予", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "部分权限被拒绝，功能可能受限", Toast.LENGTH_SHORT).show()
            }
        }

        notificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            checkPermissions()
            if (granted) {
                Toast.makeText(this, "通知权限已授予", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "通知权限被拒绝，将无法显示通知", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkPermissions() {
        hasSmsPermission.value = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECEIVE_SMS
        ) == PackageManager.PERMISSION_GRANTED

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasNotificationPermission.value = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }

        // 加载监控开关状态
        val prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(Prefs.KEY_MONITORING_ENABLED, false)
        isMonitoringEnabled.value = enabled
        isServiceRunning.value = SmsMonitorService.isRunning
        hasExactAlarmPermission.value = AlarmScheduler.canScheduleExact(this)

        // 回到前台时刷新监控日志
        logRefreshTick.value = logRefreshTick.value + 1

        if (enabled) {
            // 自愈：开关为开但服务已被 ROM 清理，回到前台时重新拉起
            if (!SmsMonitorService.isRunning && hasSmsPermission.value) {
                SmsMonitorService.start(this)
            }
        }

        // 守护任务在重装/清数据后会丢失，每次回前台重新确保排入。
        // 监控与定时检查任一启用都需要它。
        Watchdog.sync(this)

        // 定时检查补检：这是"被强行停止"之后唯一的自动恢复路径，
        // 因此每次打开应用都尝试补齐错过的截止时刻（放后台线程，避免卡 UI）
        Thread {
            try {
                CheckRunner.runIfDue(this, "启动补检")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun requestSmsPermission() {
        smsPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_SMS
            )
        )
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun toggleMonitoring() {
        val target = !isMonitoringEnabled.value

        if (target && !hasSmsPermission.value) {
            Toast.makeText(this, "请先授予短信权限", Toast.LENGTH_SHORT).show()
            return
        }

        isMonitoringEnabled.value = target
        getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(Prefs.KEY_MONITORING_ENABLED, target).apply()

        if (target) {
            // 启动常驻前台服务，承载 ContentObserver 通道
            SmsMonitorService.start(this)
            // 排入/保留 15 分钟周期的守护任务
            Watchdog.sync(this)
            // 不做乐观置真：先取当下真实值，稍后复检，只有服务真正进入前台才显示就绪
            isServiceRunning.value = SmsMonitorService.isRunning
            mainHandler.postDelayed({
                isServiceRunning.value = SmsMonitorService.isRunning
            }, SERVICE_START_VERIFY_MS)
            Toast.makeText(this, "监控已启用（双通道 + 守护）", Toast.LENGTH_SHORT).show()
        } else {
            // 完整关闭：清开关 + 撤中转闹钟 + 停闹钟 + 停守护 + 停服务
            SmsMonitorService.shutdown(this)
            isServiceRunning.value = false
            Toast.makeText(this, "监控已停用", Toast.LENGTH_SHORT).show()
        }
    }

    /** 申请精准闹钟权限（Android 12+ 部分 ROM 默认关闭，需用户手动开启） */
    private fun requestExactAlarmPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (e: Exception) {
            e.printStackTrace()
            openAppDetails()
        }
    }

    /** 申请加入电池优化白名单 */
    private fun requestIgnoreBatteryOptimizations() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (e: Exception) {
            e.printStackTrace()
            // 部分 ROM 不支持该 Action，退化为应用详情页
            openAppDetails()
        }
    }

    /** 跳转应用详情页（小米/HyperOS 在此页开启「自启动」） */
    private fun openAppDetails() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "无法打开系统设置，请手动前往设置", Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        /** 停留在本页时，服务状态的轮询间隔 */
        const val SERVICE_STATE_POLL_MS = 2_000L

        /** 启用监控后，复检服务是否真正进入前台的延迟 */
        const val SERVICE_START_VERIFY_MS = 700L
    }
}

// ==================== Compose UI ====================

@Composable
fun HomePage(
    hasSmsPermission: Boolean,
    hasNotificationPermission: Boolean,
    hasExactAlarmPermission: Boolean,
    isMonitoringEnabled: Boolean,
    isServiceRunning: Boolean,
    logRefreshTick: Int,
    onRequestSmsPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onRequestExactAlarmPermission: () -> Unit,
    onToggleMonitoring: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenAppDetails: () -> Unit
) {
    val context = LocalContext.current

    // UI 状态
    var keywords by remember { mutableStateOf(setOf<String>()) }
    var newKeyword by remember { mutableStateOf("") }
    var volume by remember { mutableStateOf(80f) }
    var selectedRingtoneName by remember { mutableStateOf("默认闹钟") }
    var selectedRingtoneUri by remember { mutableStateOf<Uri?>(null) }
    var showRingtoneDialog by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }
    var testPending by remember { mutableStateOf(false) }
    var logEntries by remember { mutableStateOf<List<AlarmLogEntry>>(emptyList()) }
    var checkTask by remember { mutableStateOf(CheckTask()) }
    var lastCheckResult by remember { mutableStateOf<CheckResult?>(null) }
    var showTaskDialog by remember { mutableStateOf(false) }
    val isAlarmPlaying by AlarmPlayer.isPlaying.collectAsState()

    // 回到前台时重新读取监控日志
    LaunchedEffect(logRefreshTick) {
        logEntries = AlarmLog.read(context)
        checkTask = CheckTaskStore.load(context)
        lastCheckResult = CheckTaskStore.loadLastResult(context)
    }

    // 闹钟停止后（用户刚处理完一次响铃）也刷新一次
    LaunchedEffect(isAlarmPlaying) {
        if (!isAlarmPlaying) {
            logEntries = AlarmLog.read(context)
        }
    }

    // 闹钟真正出声后清除"启动中"状态
    LaunchedEffect(isAlarmPlaying) {
        if (isAlarmPlaying) testPending = false
    }

    // 兜底：迟迟不出声则结束加载态并给出排查提示，避免按钮一直转圈
    LaunchedEffect(testPending) {
        if (testPending) {
            delay(4000)
            if (testPending) {
                testPending = false
                Toast.makeText(context, "仍未出声，请检查通知权限与后台弹出限制", Toast.LENGTH_LONG).show()
            }
        }
    }

    // 加载已保存的设置
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        keywords = prefs.getStringSet(Prefs.KEY_KEYWORDS, emptySet()) ?: emptySet()
        volume = prefs.getInt(Prefs.KEY_VOLUME, 80).toFloat()
        val uriStr = prefs.getString(Prefs.KEY_RINGTONE_URI, null)
        if (uriStr != null) {
            selectedRingtoneUri = Uri.parse(uriStr)
            selectedRingtoneName = prefs.getString(Prefs.KEY_RINGTONE_NAME, "已选铃声") ?: "已选铃声"
        }
    }

    // 保存关键字集合到SharedPreferences
    fun persistKeywords(updated: Set<String>) {
        val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(Prefs.KEY_KEYWORDS, updated).apply()
    }

    // 添加关键字
    fun addKeyword() {
        val trimmed = newKeyword.trim()
        if (trimmed.isEmpty()) {
            Toast.makeText(context, "请输入关键字", Toast.LENGTH_SHORT).show()
            return
        }
        if (keywords.contains(trimmed)) {
            Toast.makeText(context, "该关键字已存在", Toast.LENGTH_SHORT).show()
            return
        }
        val updated = keywords + trimmed
        keywords = updated
        newKeyword = ""
        persistKeywords(updated)
        Toast.makeText(context, "已添加: $trimmed", Toast.LENGTH_SHORT).show()
    }

    // 删除关键字
    fun deleteKeyword(keyword: String) {
        val updated = keywords - keyword
        keywords = updated
        persistKeywords(updated)
        Toast.makeText(context, "已删除: $keyword", Toast.LENGTH_SHORT).show()
    }

    // 保存音量
    fun saveVolume(newVolume: Float) {
        volume = newVolume
        val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(Prefs.KEY_VOLUME, newVolume.toInt()).apply()
    }

    // 保存铃声选择
    fun saveRingtone(name: String, uri: Uri) {
        selectedRingtoneName = name
        selectedRingtoneUri = uri
        val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(Prefs.KEY_RINGTONE_URI, uri.toString())
            .putString(Prefs.KEY_RINGTONE_NAME, name)
            .apply()
    }

    // 保存定时检查任务
    fun saveCheckTask(t: CheckTask) {
        if (t.enabled && !t.configComplete) {
            Toast.makeText(context, "请先填写指定关键字", Toast.LENGTH_SHORT).show()
            return
        }

        CheckTaskStore.save(context, t)

        // 把"当前已过去的那个截止时刻"标记为已处理。
        // 否则刚启用就会对今天早些时候那个早已错过的时刻补检，凭空响一次。
        if (t.enabled) {
            CheckTaskStore.startFreshFromNow(context, t)
        }

        checkTask = t
        showTaskDialog = false
        Watchdog.sync(context)

        if (!t.enabled) {
            TaskAlarmScheduler.cancel(context)
            Toast.makeText(context, "任务已停用", Toast.LENGTH_SHORT).show()
            return
        }

        if (TaskAlarmScheduler.scheduleNext(context, t)) {
            Toast.makeText(context, "任务已启用", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                context,
                "已保存。精准闹钟不可用，将仅依赖 15 分钟兜底巡检",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // 测试闹钟（切换播放/停止）
    // 直接启动前台服务，跳过精准闹钟中转，降低响应延迟
    fun toggleTestAlarm() {
        if (isAlarmPlaying) {
            AlarmService.stop(context)
            return
        }

        testPending = true
        AlarmService.start(
            context = context,
            ringtoneUri = selectedRingtoneUri?.toString(),
            volume = volume / 100f,
            keyword = null,
            reason = AlarmReason.TEST
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(InkBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .padding(top = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 标题
            Text(
                text = "短信关键字闹钟",
                style = MaterialTheme.typography.headlineMedium,
                color = InkOnSurface,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // 闹钟响铃提醒（仅在闹钟播放时显示）
            if (isAlarmPlaying) {
                AlarmAlertCard(onDismiss = { AlarmService.stop(context) })
            }

            // 运行状态卡片
            StatusCard(
                hasSmsPermission = hasSmsPermission,
                hasNotificationPermission = hasNotificationPermission,
                hasExactAlarmPermission = hasExactAlarmPermission,
                isMonitoringEnabled = isMonitoringEnabled,
                isServiceRunning = isServiceRunning
            )

            // 权限申请按钮（每个都占满可用宽度，条件显示）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!hasSmsPermission) {
                    Button(
                        onClick = onRequestSmsPermission,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = InkPrimary),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("申请短信权限")
                    }
                }
                if (!hasNotificationPermission) {
                    Button(
                        onClick = onRequestNotificationPermission,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = InkPrimary),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("申请通知权限")
                    }
                }
                if (!hasExactAlarmPermission) {
                    Button(
                        onClick = onRequestExactAlarmPermission,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = InkWarningSoft,
                            contentColor = InkWarning
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("开启精准闹钟")
                    }
                }
            }

            // 关键字设置卡片
            KeywordCard(
                keywords = keywords,
                newKeyword = newKeyword,
                onNewKeywordChange = { newKeyword = it },
                onAddKeyword = { addKeyword() },
                onDeleteKeyword = { deleteKeyword(it) }
            )

            // 闹钟参数设置卡片
            AlarmSettingsCard(
                volume = volume,
                onVolumeChange = { saveVolume(it) },
                ringtoneName = selectedRingtoneName,
                onSelectRingtone = { showRingtoneDialog = true }
            )

            // 操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onToggleMonitoring,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isMonitoringEnabled) InkErrorSoft else InkPrimary,
                        contentColor = if (isMonitoringEnabled) InkError else InkOnPrimary
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(if (isMonitoringEnabled) "停用监控" else "启用监控")
                }

                OutlinedButton(
                    onClick = { toggleTestAlarm() },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, if (isAlarmPlaying) InkError else InkAccent),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (isAlarmPlaying) InkError else InkAccentDeep
                    )
                ) {
                    when {
                        testPending -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = InkAccentDeep
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("启动中")
                        }

                        isAlarmPlaying -> Text("停止测试")
                        else -> Text("测试闹钟")
                    }
                }
            }

            // 定时检查任务
            CheckTaskCard(
                task = checkTask,
                lastResult = lastCheckResult,
                onOpen = { showTaskDialog = true }
            )

            // 监控日志入口（点击后弹窗查看全部记录）
            AlarmLogCard(
                total = logEntries.size,
                matchedCount = logEntries.count { it.matched },
                checkCount = logEntries.count { it.isTaskCheck },
                onOpen = { showLogDialog = true }
            )

            // 后台保活引导（国产 ROM 必需）
            KeepAliveCard(
                onOpenBatterySettings = onOpenBatterySettings,
                onOpenAppDetails = onOpenAppDetails
            )

            // 隐私声明卡片
            PrivacyCard()

            // 底部间距
            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // 铃声选择对话框
    if (showRingtoneDialog) {
        RingtonePickerDialog(
            onDismiss = { showRingtoneDialog = false },
            onSelect = { name, uri ->
                saveRingtone(name, uri)
                showRingtoneDialog = false
            }
        )
    }

    // 定时检查任务对话框
    if (showTaskDialog) {
        CheckTaskDialog(
            initial = checkTask,
            onDismiss = { showTaskDialog = false },
            onSave = { saveCheckTask(it) }
        )
    }

    // 监控日志对话框
    if (showLogDialog) {
        AlarmLogDialog(
            entries = logEntries,
            onDismiss = { showLogDialog = false },
            onClear = {
                AlarmLog.clear(context)
                logEntries = emptyList()
                Toast.makeText(context, "日志已清空", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

// ==================== 卡片组件 ====================

/** 超过这个时长没有检查记录，就认为任务可能已被系统静默掐掉 */
private const val STALE_AFTER_MS = 30L * 60 * 60 * 1000

/**
 * 定时检查任务卡片：主页展示该任务的设定与上次检查结果
 */
@Composable
fun CheckTaskCard(
    task: CheckTask,
    lastResult: CheckResult?,
    onOpen: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = InkSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "定时检查任务",
                    style = MaterialTheme.typography.titleLarge,
                    color = InkOnSurface,
                    modifier = Modifier.weight(1f)
                )
                if (task.enabled && task.configComplete) {
                    LogBadge("已启用", InkSuccess)
                } else {
                    LogBadge("未启用", InkSecondary)
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (!task.configComplete) {
                Text(
                    text = "到点后回看一段时间的短信；若没有符合条件的短信，则响铃。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = InkSecondary,
                    lineHeight = 20.sp
                )
            } else {
                Text(
                    text = "每天 ${task.timeText} · 回看前 ${task.lookbackMinutes} 分钟",
                    style = MaterialTheme.typography.bodyMedium,
                    color = InkOnSurface
                )
                Text(
                    text = "号码 ${task.senderText} · 关键字 ${task.keyword}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = InkSecondary
                )
                if (lastResult != null) {
                    val label = when (lastResult.outcome) {
                        CheckOutcome.FOUND -> "已收到"
                        CheckOutcome.NOT_FOUND -> "未收到，已响铃"
                        CheckOutcome.UNVERIFIABLE -> "无法读取短信"
                    }
                    val labelColor = when (lastResult.outcome) {
                        CheckOutcome.FOUND -> InkSuccess
                        CheckOutcome.NOT_FOUND -> InkError
                        CheckOutcome.UNVERIFIABLE -> InkWarning
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "上次检查 ${AlarmLog.formatTime(lastResult.checkedAt)} · $label",
                        style = MaterialTheme.typography.bodySmall,
                        color = labelColor
                    )

                    // 长时间没有更新，说明任务很可能已被系统静默掐掉
                    if (System.currentTimeMillis() - lastResult.checkedAt > STALE_AFTER_MS) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "已超过 30 小时没有检查，任务可能已失效。请检查自启动与电池优化设置。",
                            style = MaterialTheme.typography.bodySmall,
                            color = InkError,
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onOpen,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = InkPrimary),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("设定任务")
            }
        }
    }
}

/**
 * 定时检查任务设定弹窗
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckTaskDialog(
    initial: CheckTask,
    onDismiss: () -> Unit,
    onSave: (CheckTask) -> Unit
) {
    val timeState = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = true
    )
    var lookback by remember { mutableStateOf(initial.lookbackMinutes.toFloat()) }
    var sender by remember { mutableStateOf(initial.sender) }
    var keyword by remember { mutableStateOf(initial.keyword) }
    var enabled by remember { mutableStateOf(initial.enabled) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "定时检查任务", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "到点后回看指定时长内的短信；若没有符合条件的短信，则响铃。",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSecondary,
                    lineHeight = 18.sp
                )

                Text(
                    text = "检查时间",
                    style = MaterialTheme.typography.bodyMedium,
                    color = InkSecondary
                )
                TimeInput(state = timeState)

                Text(
                    text = "回看时长：${lookback.toInt()} 分钟",
                    style = MaterialTheme.typography.bodyMedium,
                    color = InkSecondary
                )
                Slider(
                    value = lookback,
                    onValueChange = { lookback = it },
                    valueRange = 5f..240f,
                    colors = SliderDefaults.colors(
                        thumbColor = InkAccent,
                        activeTrackColor = InkAccent
                    )
                )

                OutlinedTextField(
                    value = sender,
                    onValueChange = { sender = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("指定号码（可选）") },
                    placeholder = { Text("留空表示任意号码") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = InkAccentDeep,
                        cursorColor = InkAccentDeep,
                        focusedLabelColor = InkAccentDeep
                    )
                )

                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("指定关键字") },
                    placeholder = { Text("例如 平安") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = InkAccentDeep,
                        cursorColor = InkAccentDeep,
                        focusedLabelColor = InkAccentDeep
                    )
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "启用该任务",
                        style = MaterialTheme.typography.bodyLarge,
                        color = InkOnSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    CheckTask(
                        enabled = enabled,
                        hour = timeState.hour,
                        minute = timeState.minute,
                        lookbackMinutes = lookback.toInt(),
                        sender = sender.trim(),
                        keyword = keyword.trim()
                    )
                )
            }) {
                Text("保存", color = InkAccentDeep)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = InkSecondary)
            }
        },
        shape = RoundedCornerShape(16.dp),
        containerColor = InkSurface
    )
}

@Composable
fun StatusCard(
    hasSmsPermission: Boolean,
    hasNotificationPermission: Boolean,
    hasExactAlarmPermission: Boolean,
    isMonitoringEnabled: Boolean,
    isServiceRunning: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = InkSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "运行状态",
                style = MaterialTheme.typography.titleLarge,
                color = InkOnSurface,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            StatusItem(label = "短信权限", isOk = hasSmsPermission)
            StatusItem(label = "通知权限", isOk = hasNotificationPermission)
            StatusItem(label = "精准闹钟", isOk = hasExactAlarmPermission)
            StatusItem(label = "监控开关", isOk = isMonitoringEnabled)
            StatusItem(label = "监控服务常驻", isOk = isServiceRunning)

            Spacer(modifier = Modifier.height(12.dp))
           
        }
    }
}

@Composable
fun KeepAliveCard(
    onOpenBatterySettings: () -> Unit,
    onOpenAppDetails: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = InkSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "后台保活设置",
                style = MaterialTheme.typography.titleLarge,
                color = InkOnSurface,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = "小米 / HyperOS / 华为等系统会清理后台进程。" +
                        "建议将本应用加入电池优化白名单，并在应用详情页开启「自启动」。",
                style = MaterialTheme.typography.bodyMedium,
                color = InkSecondary,
                lineHeight = 22.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onOpenBatterySettings,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = InkPrimary),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("电池白名单", fontSize = 13.sp)
                }
                OutlinedButton(
                    onClick = onOpenAppDetails,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, InkAccent),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = InkAccentDeep)
                ) {
                    Text("自启动设置", fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
fun LogBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            color = color,
            fontWeight = FontWeight.Medium
        )
    }
}

/** 通道徽标颜色 */
private fun channelColor(channel: TriggerChannel): Color = when (channel) {
    TriggerChannel.BROADCAST -> InkAccentDeep
    TriggerChannel.CONTENT_OBSERVER -> InkSuccess
    TriggerChannel.TASK_CHECK -> InkSecondary
}

/** 处理结果徽标颜色 */
private fun outcomeColor(outcome: AlarmOutcome): Color = when (outcome) {
    AlarmOutcome.RINGING -> InkSuccess
    AlarmOutcome.DEGRADED -> InkWarning
    AlarmOutcome.SUPPRESSED -> InkWarning
    AlarmOutcome.NOT_MATCHED -> InkSecondary
    AlarmOutcome.TASK_RECEIVED -> InkSuccess
    AlarmOutcome.TASK_MISSING -> InkError
    AlarmOutcome.TASK_UNVERIFIABLE -> InkWarning
}

/** 日志弹窗的分类筛选 */
private enum class LogFilter(val label: String) {
    ALL("全部"),
    SMS("短信"),
    TASK("定时检查")
}

/**
 * 日志入口卡片：只显示统计与按钮，明细在弹窗中查看
 */
@Composable
fun AlarmLogCard(
    total: Int,
    matchedCount: Int,
    checkCount: Int,
    onOpen: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = InkSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "监控日志",
                style = MaterialTheme.typography.titleLarge,
                color = InkOnSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (total == 0) {
                    "暂无记录。开启监控或设定定时检查后，事件都会记录在此"
                } else {
                    "共 $total 条 · 短信命中 $matchedCount 次 · 定时检查 $checkCount 次"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = InkSecondary,
                lineHeight = 20.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onOpen,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = InkPrimary),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("查看日志")
            }
        }
    }
}

/**
 * 监控日志弹窗：列出所有监控到的短信（含未命中关键字的）
 */
@Composable
fun AlarmLogDialog(
    entries: List<AlarmLogEntry>,
    onDismiss: () -> Unit,
    onClear: () -> Unit
) {
    var filter by remember { mutableStateOf(LogFilter.ALL) }
    val shown = when (filter) {
        LogFilter.ALL -> entries
        LogFilter.SMS -> entries.filter { !it.isTaskCheck }
        LogFilter.TASK -> entries.filter { it.isTaskCheck }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "监控日志", fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "共 ${entries.size} 条 · 短信 ${entries.count { !it.isTaskCheck }} 条" +
                            "（命中 ${entries.count { it.matched }}）" +
                            " · 定时检查 ${entries.count { it.isTaskCheck }} 次",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSecondary,
                    lineHeight = 16.sp
                )
                Spacer(modifier = Modifier.height(6.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LogFilter.values().forEach { f ->
                        val selected = filter == f
                        Text(
                            text = f.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (selected) InkOnPrimary else InkAccentDeep,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) InkPrimary else InkNeutralFill)
                                .clickable { filter = f }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                if (shown.isEmpty()) {
                    Text(
                        text = if (entries.isEmpty()) {
                            "暂无记录。开启监控或设定定时检查后，事件都会记录在此。"
                        } else {
                            "该分类下暂无记录。"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = InkSecondary,
                        lineHeight = 20.sp
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                        itemsIndexed(shown) { index, entry ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = AlarmLog.formatTime(entry.timestamp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = InkSecondary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    LogBadge(
                                        text = entry.channel.label,
                                        color = channelColor(entry.channel)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    LogBadge(
                                        text = entry.outcome.label,
                                        color = outcomeColor(entry.outcome)
                                    )
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                if (entry.keyword.isNotBlank()) {
                                    Text(
                                        text = "关键字：${entry.keyword}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = InkOnSurface,
                                        fontWeight = FontWeight.Medium
                                    )
                                }

                                if (entry.body.isNotBlank()) {
                                    Text(
                                        text = entry.body,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (entry.matched) InkOnSurface else InkSecondary
                                    )
                                }
                            }

                            if (index < shown.size - 1) {
                                HorizontalDivider(color = InkOutline, thickness = 0.5.dp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", color = InkSecondary)
            }
        },
        dismissButton = {
            if (entries.isNotEmpty()) {
                TextButton(onClick = onClear) {
                    Text("清空", color = InkError)
                }
            }
        },
        shape = RoundedCornerShape(16.dp),
        containerColor = InkSurface
    )
}

@Composable
fun StatusItem(label: String, isOk: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (isOk) InkSuccess else InkError)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = InkSecondary,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = if (isOk) "已就绪" else "未就绪",
            style = MaterialTheme.typography.bodyMedium,
            color = if (isOk) InkSuccess else InkError
        )
    }
}

@Composable
fun KeywordCard(
    keywords: Set<String>,
    newKeyword: String,
    onNewKeywordChange: (String) -> Unit,
    onAddKeyword: () -> Unit,
    onDeleteKeyword: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = InkSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "关键字",
                style = MaterialTheme.typography.titleLarge,
                color = InkOnSurface,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // 输入框 + 添加按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newKeyword,
                    onValueChange = onNewKeywordChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("请输入关键字") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = InkAccentDeep,
                        cursorColor = InkAccentDeep,
                        focusedLabelColor = InkAccentDeep
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onAddKeyword,
                    colors = ButtonDefaults.buttonColors(containerColor = InkPrimary),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)
                ) {
                    Text("添加")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 已保存的关键字列表
            if (keywords.isEmpty()) {
                Text(
                    text = "暂无关键字，请添加",
                    style = MaterialTheme.typography.bodyMedium,
                    color = InkSecondary
                )
            } else {
                Text(
                    text = "已保存 ${keywords.size} 个",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSecondary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                keywords.forEachIndexed { index, keyword ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "\u2022",
                            color = InkAccentDeep,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = keyword,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { onDeleteKeyword(keyword) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Text(
                                text = "\u00d7",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = InkError
                            )
                        }
                    }
                    if (index < keywords.size - 1) {
                        HorizontalDivider(
                            color = InkOutline,
                            thickness = 0.5.dp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AlarmSettingsCard(
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    ringtoneName: String,
    onSelectRingtone: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = InkSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "闹钟参数",
                style = MaterialTheme.typography.titleLarge,
                color = InkOnSurface,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // 音量滑块
            Text(
                text = "音量",
                style = MaterialTheme.typography.bodyMedium,
                color = InkSecondary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Slider(
                value = volume,
                onValueChange = onVolumeChange,
                valueRange = 0f..100f,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = InkAccent,
                    activeTrackColor = InkAccent
                )
            )
            Text(
                text = "${volume.toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = InkSecondary,
                modifier = Modifier.align(Alignment.End)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 铃声选择
            Text(
                text = "铃声",
                style = MaterialTheme.typography.bodyMedium,
                color = InkSecondary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectRingtone() },
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, InkOutline),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = ringtoneName,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "选择",
                        style = MaterialTheme.typography.labelLarge,
                        color = InkAccentDeep
                    )
                }
            }
        }
    }
}

@Composable
fun AlarmAlertCard(onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = InkErrorSoft),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "闹钟正在响铃",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = InkError
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = InkError,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("关闭闹钟", fontSize = 16.sp)
            }
        }
    }
}

@Composable
fun PrivacyCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = InkSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "隐私声明",
                style = MaterialTheme.typography.titleLarge,
                color = InkOnSurface,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = "本应用不申请任何网络权限。所有数据仅保存在本机，不会上传至任何服务器。",
                style = MaterialTheme.typography.bodyMedium,
                color = InkSecondary,
                lineHeight = 22.sp
            )
        }
    }
}

// ==================== 铃声选择对话框 ====================

@Composable
fun RingtonePickerDialog(
    onDismiss: () -> Unit,
    onSelect: (String, Uri) -> Unit
) {
    val context = LocalContext.current

    // 查询系统闹钟铃声
    val ringtones = remember {
        val list = mutableListOf<Pair<String, Uri>>()
        try {
            val manager = RingtoneManager(context)
            manager.setType(RingtoneManager.TYPE_ALARM)
            val cursor = manager.cursor
            while (cursor.moveToNext()) {
                val title = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX)
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    manager.getRingtoneUri(cursor.position)
                } else {
                    val baseUri = cursor.getString(RingtoneManager.URI_COLUMN_INDEX)
                    val id = cursor.getLong(RingtoneManager.ID_COLUMN_INDEX)
                    Uri.parse("$baseUri/$id")
                }
                if (uri != null) {
                    list.add(title to uri)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 如果列表为空，添加默认闹钟
        if (list.isEmpty()) {
            val defaultUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            if (defaultUri != null) {
                list.add("默认闹钟" to defaultUri)
            }
        }

        list
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "选择铃声",
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 400.dp)
            ) {
                ringtones.forEachIndexed { index, (title, uri) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(title, uri) }
                            .padding(vertical = 14.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    if (index < ringtones.lastIndex) {
                        HorizontalDivider(
                            color = InkOutline,
                            thickness = 0.5.dp,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = InkSecondary)
            }
        },
        shape = RoundedCornerShape(16.dp),
        containerColor = InkSurface
    )
}
