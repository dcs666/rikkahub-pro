package me.rerere.rikkahub.web

import android.content.Context
import android.util.Log
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.web.startWebServer
import java.net.ServerSocket

private const val TAG = "WebServerManager"
private const val HOST_ALL_INTERFACES = "0.0.0.0"
private const val HOST_LOOPBACK = "127.0.0.1"

data class WebServerState(
    val isRunning: Boolean = false,
    val isLoading: Boolean = false,
    val port: Int = 8080,
    val serviceName: String = DEFAULT_SERVICE_NAME,
    val localhostOnly: Boolean = false,
    val hostname: String? = null,
    val address: String? = null,
    val error: String? = null
)

class WebServerManager(
    private val context: Context,
    private val appScope: AppScope,
    private val chatService: ChatService,
    private val conversationRepo: ConversationRepository,
    private val folderRepo: FolderRepository,
    private val settingsStore: SettingsStore,
    private val filesManager: FilesManager,
    private val taskManager: me.rerere.rikkahub.data.task.BackgroundTaskManager? = null,
    private val eventBus: me.rerere.rikkahub.data.event.AppEventBus? = null,
) {
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val nsdRegistrar = NsdServiceRegistrar(context)

    private val _state = MutableStateFlow(WebServerState())
    val state: StateFlow<WebServerState> = _state.asStateFlow()

    fun start(
        port: Int = 8080,
        serviceName: String = DEFAULT_SERVICE_NAME,
        localhostOnly: Boolean = false
    ) {
        if (server != null) {
            Log.w(TAG, "Server already running")
            return
        }

        appScope.launch {
            // [SECURITY] fail-closed 护栏：请求监听所有接口（局域网访问）时，
            // 必须已启用 JWT 且设置了密码；否则强制绑定回环地址（仅本机可访问）。
            // 背景：webServerJwtEnabled 默认 false，若直接 0.0.0.0 监听，
            // 局域网内任何设备都能无认证读取全部对话/文件（Android 12- 无
            // ACCESS_LOCAL_NETWORK 权限保护）。无密码无 JWT = 只允许本机使用。
            val settings = settingsStore.settingsFlow.value
            val jwtSecured = settings.webServerJwtEnabled &&
                settings.webServerAccessPassword.isNotBlank()
            val effectiveLocalhostOnly = localhostOnly || !jwtSecured
            if (!localhostOnly && !jwtSecured) {
                Log.w(
                    TAG,
                    "WebServer: JWT not enabled, forcing loopback binding (LAN access requires password + JWT)"
                )
            }
            // 仅本机模式绑定回环地址
            val host = if (effectiveLocalhostOnly) HOST_LOOPBACK else HOST_ALL_INTERFACES
            val baseState = WebServerState(
                port = port,
                serviceName = serviceName,
                // [SECURITY] 展示实际生效的绑定（被强制回环时如实反映）
                localhostOnly = effectiveLocalhostOnly
            )
            try {
                _state.value = _state.value.copy(isLoading = true)
                Log.i(TAG, "Starting web server on $host:$port")
                if (!isPortAvailable(port)) {
                    Log.w(TAG, "Port $port is already in use")
                    _state.value = baseState.copy(error = "Port $port is already in use")
                    return@launch
                }
                server = startWebServer(port = port, host = host) {
                    configureWebApi(
                        context,
                        chatService,
                        conversationRepo,
                        folderRepo,
                        settingsStore,
                        filesManager,
                        taskManager,
                        eventBus,
                    )
                }.start(wait = false)

                _state.value = baseState.copy(isRunning = true)
                // 仅局域网模式注册 mDNS（实际绑定所有接口时）
                if (!effectiveLocalhostOnly) {
                    runCatching {
                        nsdRegistrar.register(
                            port = port,
                            serviceName = serviceName,
                            onRegistered = { info ->
                                _state.value = _state.value.copy(
                                    serviceName = info.serviceName,
                                    hostname = info.hostname,
                                    address = info.address.hostAddress
                                )
                            }
                        )
                    }.onFailure {
                        Log.w(TAG, "NSD register failed", it)
                    }
                }
                Log.i(TAG, "Web server started successfully on $host:$port")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start web server", e)
                _state.value = baseState.copy(error = e.message)
            }
        }
    }

    fun reportError(message: String) {
        _state.value = _state.value.copy(isRunning = false, isLoading = false, error = message)
    }

    fun stop() {
        _state.value =
            _state.value.copy(isRunning = false, isLoading = true, hostname = null, address = null, error = null)
        appScope.launch {
            try {
                Log.i(TAG, "Stopping web server")
                server?.stop(1000, 2000)
                server = null
                runCatching {
                    nsdRegistrar.unregister()
                }.onFailure {
                    Log.w(TAG, "NSD unregister failed", it)
                }
                _state.value = _state.value.copy(isLoading = false)
                Log.i(TAG, "Web server stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop web server", e)
                _state.value = _state.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    fun restart(
        port: Int = _state.value.port,
        serviceName: String = _state.value.serviceName,
        localhostOnly: Boolean = _state.value.localhostOnly
    ) {
        // [FIX] stop() 内部是异步 launch：restart() 立即调 start() 时旧 server
        // 尚未停止（server != null → start 直接 return "Server already running"），
        // 结果是只停不启。改为同步停止旧 server 后再启动。
        // 注意：不在此处理 NSD（unregister 是 suspend，且 restart 默认同端口同名，
        // 已注册的 NSD 服务继续有效；start() 内部会按需重新注册）。
        server?.let { old ->
            old.stop(1000, 2000)
            server = null
        }
        start(port, serviceName, localhostOnly)
    }

    private fun isPortAvailable(port: Int): Boolean {
        return try {
            ServerSocket(port).use { true }
        } catch (e: Exception) {
            false
        }
    }
}
