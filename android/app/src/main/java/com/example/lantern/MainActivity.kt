package com.example.lantern

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.lantern.ui.theme.LanternTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import androidx.core.content.PermissionChecker
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.UUID
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.util.Base64
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

class MainActivity : ComponentActivity() {
    companion object {
        // Localhost for Android emulator (10.0.2.2) and FastAPI default port
        const val SERVER_HOST = "10.0.2.2" // Localhost for Android emulator
        const val SERVER_PORT = 8080 // FastAPI default
        const val WS_PATH = "/ws/stream"
    }
    private lateinit var cameraExecutor: ExecutorService
    var webSocket: WebSocket? = null
    private var wsConnected = mutableStateOf(false)
    private var wsStatus = mutableStateOf("")
    var sessionId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        cameraExecutor = Executors.newSingleThreadExecutor()
        setContent {
            LanternTheme {
                VideoCaptureScreen(cameraExecutor, wsConnected.value, wsStatus.value, ::connectWebSocket, ::disconnectWebSocket)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        disconnectWebSocket()
    }

    private fun connectWebSocket() {
        val url = "ws://$SERVER_HOST:$SERVER_PORT$WS_PATH"
        val client = OkHttpClient()
        val request = Request.Builder().url(url).build()
        wsStatus.value = "Connecting..."
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                wsConnected.value = true
                wsStatus.value = "Connected"
                // Send init message
                sessionId = UUID.randomUUID().toString()
                val initMsg = JSONObject().apply {
                    put("type", "init")
                    put("session_id", sessionId)
                    put("video", JSONObject().apply {
                        put("width", 640)
                        put("height", 480)
                        put("fps", 15)
                    })
                    put("device", JSONObject().apply {
                        put("model", android.os.Build.MODEL)
                        put("platform", "android")
                    })
                }
                webSocket.send(initMsg.toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                // TODO: Handle server responses (detections, guidance, etc.)
                Log.d("Lantern", "WS message: $text")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                wsConnected.value = false
                wsStatus.value = "Closed: $reason"
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                wsConnected.value = false
                wsStatus.value = "Error: ${t.localizedMessage}"
            }
        })
    }

    private fun disconnectWebSocket() {
        webSocket?.close(1000, "App closed")
        webSocket = null
        wsConnected.value = false
        wsStatus.value = "Disconnected"
    }
}

@Composable
fun VideoCaptureScreen(
    cameraExecutor: ExecutorService,
    wsConnected: Boolean,
    wsStatus: String,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPermissions by remember { mutableStateOf(false) }
    var isStreaming by remember { mutableStateOf(false) }
    var serverResponse by remember { mutableStateOf("") }

    // Permissions
    val permissions = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms: Map<String, Boolean> ->
        hasPermissions = perms.all { it.value }
    }
    LaunchedEffect(Unit) {
        hasPermissions = permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!hasPermissions) launcher.launch(permissions.toTypedArray())
    }

    if (!hasPermissions) {
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Text("Please grant camera and audio permissions.")
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Button(onClick = onConnect, enabled = !wsConnected) { Text("Connect") }
            Button(onClick = onDisconnect, enabled = wsConnected) { Text("Disconnect") }
            Text(wsStatus)
        }
        Spacer(Modifier.height(8.dp))
        CameraStreamPreview(
            isStreaming = isStreaming && wsConnected,
            cameraExecutor = cameraExecutor,
            lifecycleOwner = lifecycleOwner,
            onFrame = { jpegBase64, ts ->
                // Send frame as JSON message over WebSocket
                (context as? MainActivity)?.webSocket?.send(
                    JSONObject().apply {
                        put("type", "frame")
                        put("session_id", (context as MainActivity).sessionId ?: "")
                        put("ts", ts)
                        put("image_b64", jpegBase64)
                    }.toString()
                )
            }
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Button(onClick = { isStreaming = !isStreaming }, enabled = wsConnected) {
                Text(if (isStreaming) "Stop Streaming" else "Start Streaming")
            }
        }
        if (serverResponse.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text("Server Response: $serverResponse")
        }
    }
}

@Composable
fun CameraStreamPreview(
    isStreaming: Boolean,
    cameraExecutor: ExecutorService,
    lifecycleOwner: LifecycleOwner,
    onFrame: (jpegBase64: String, ts: Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val previewView = remember { androidx.camera.view.PreviewView(context) }
    var analysisUseCase by remember { mutableStateOf<ImageAnalysis?>(null) }
    val previewUseCase = remember { Preview.Builder().build() }

    DisposableEffect(isStreaming) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val cameraProvider = cameraProviderFuture.get()
        cameraProvider.unbindAll()
        previewUseCase.setSurfaceProvider(previewView.surfaceProvider)
        val useCases = mutableListOf<androidx.camera.core.UseCase>(previewUseCase)
        if (isStreaming) {
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                val jpegBase64 = imageProxyToBase64(imageProxy)
                if (jpegBase64 != null) {
                    onFrame(jpegBase64, System.currentTimeMillis())
                }
                imageProxy.close()
            }
            analysisUseCase = analysis
            useCases.add(analysis)
        } else {
            analysisUseCase?.clearAnalyzer()
            analysisUseCase = null
        }
        cameraProvider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            *useCases.toTypedArray()
        )
        onDispose {
            cameraProvider.unbindAll()
        }
    }
    AndroidView({ previewView }, modifier = modifier.fillMaxWidth().aspectRatio(3f/4f))
}

fun imageProxyToBase64(imageProxy: ImageProxy): String? {
    val image = imageProxy.image ?: return null
    if (image.format != ImageFormat.YUV_420_888) return null
    val nv21 = yuv420ToNv21(image)
    val yuvImage = android.graphics.YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
    val out = java.io.ByteArrayOutputStream()
    yuvImage.compressToJpeg(android.graphics.Rect(0, 0, image.width, image.height), 80, out)
    val jpegBytes = out.toByteArray()
    return Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
}

fun yuv420ToNv21(image: Image): ByteArray {
    val ySize = image.planes[0].buffer.remaining()
    val uSize = image.planes[1].buffer.remaining()
    val vSize = image.planes[2].buffer.remaining()
    val nv21 = ByteArray(ySize + uSize + vSize)
    image.planes[0].buffer.get(nv21, 0, ySize)
    image.planes[2].buffer.get(nv21, ySize, vSize)
    image.planes[1].buffer.get(nv21, ySize + vSize, uSize)
    return nv21
}