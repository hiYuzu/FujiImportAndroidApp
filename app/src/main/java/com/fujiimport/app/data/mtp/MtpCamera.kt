package com.fujiimport.app.data.mtp

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.mtp.MtpConstants
import android.mtp.MtpDevice
import android.mtp.MtpObjectInfo
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.fujiimport.app.domain.CameraStorage
import com.fujiimport.app.domain.CameraUnavailableException
import com.fujiimport.app.domain.ConnectionPhase
import com.fujiimport.app.domain.ConnectionState
import com.fujiimport.app.domain.Photo
import com.fujiimport.app.domain.PhotoFormat
import com.fujiimport.app.domain.PartialReadException
import com.fujiimport.app.domain.copyExactBytes
import com.fujiimport.app.domain.sha256
import com.fujiimport.app.util.Diagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.ArrayDeque
import java.util.UUID

/** The only MTP operations in this adapter read camera data. Never send/delete objects. */
class MtpCamera(private val context: Context, private val diagnostics: Diagnostics) {
    private val usbManager = context.getSystemService(UsbManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ioLock = Mutex()
    private val connectionLock = Mutex()
    private val mutableState = MutableStateFlow(ConnectionState())
    val state = mutableState.asStateFlow()
    @Volatile private var active: Session? = null
    @Volatile private var openingConnection: UsbDeviceConnection? = null
    private var connectionJob: Job? = null
    private val deniedDevices = mutableSetOf<String>()
    private var permissionDeviceId: Int? = null
    private val permissionAction = "${context.packageName}.USB_PERMISSION"
    private var started = false

    private data class Session(
        val id: String,
        val device: UsbDevice,
        val connection: UsbDeviceConnection,
        val mtp: MtpDevice,
        val cameraKey: String?,
        val model: String?,
        val operations: Set<Int>,
        var partialUsable: Boolean = true,
    )

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != permissionAction) return
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java) ?: return
            if (device.deviceId != permissionDeviceId) return
            permissionDeviceId = null
            val present = usbManager.deviceList[device.deviceName] ?: return
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && usbManager.hasPermission(present)) {
                connect(present)
            } else {
                deniedDevices.add(device.deviceName)
                mutableState.value = mutableState.value.copy(
                    phase = ConnectionPhase.DENIED,
                    message = "USB 访问权限被拒绝，可点击重新授权",
                )
                diagnostics.event("usb_permission_denied")
            }
        }
    }

    private val deviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java) ?: return
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    deniedDevices.remove(device.deviceName)
                    checkAttachment()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    deniedDevices.remove(device.deviceName)
                    // Validate current manager state rather than trusting broadcast extras.
                    if (usbManager.deviceList.containsKey(device.deviceName)) return
                    if (active?.device?.deviceName == device.deviceName || permissionDeviceId == device.deviceId) {
                        permissionDeviceId = null
                        detach()
                    }
                }
            }
        }
    }

    fun start() {
        if (started) { checkAttachment(); return }
        started = true
        ContextCompat.registerReceiver(context, permissionReceiver, IntentFilter(permissionAction), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(context, deviceReceiver, IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }, ContextCompat.RECEIVER_EXPORTED)
        checkAttachment()
    }

    fun stop() {
        if (started) {
            context.unregisterReceiver(permissionReceiver)
            context.unregisterReceiver(deviceReceiver)
            started = false
        }
        permissionDeviceId = null
        deniedDevices.clear()
        releaseSession()
        mutableState.value = ConnectionState()
        diagnostics.event("camera_session_closed")
    }

    fun retry() {
        deniedDevices.clear()
        checkAttachment()
    }

    fun checkAttachment() {
        val devices = usbManager.deviceList.values.toList()
        val session = active
        if (session != null) {
            if (devices.none { it.deviceName == session.device.deviceName }) detach()
            return
        }
        if (connectionJob?.isActive == true || permissionDeviceId != null) return
        val description = describe(devices)
        mutableState.value = ConnectionState(ConnectionPhase.DETECTING, "正在识别 USB 设备", diagnostics = description)
        val candidate = devices.firstOrNull { supportsStillImage(it) }
        if (candidate == null) {
            mutableState.value = ConnectionState(
                phase = if (devices.isEmpty()) ConnectionPhase.DISCONNECTED else ConnectionPhase.FAILED,
                message = if (devices.isEmpty()) "未连接相机" else "未发现 PTP/MTP 图片传输接口，请查看诊断与相机 USB 模式",
                diagnostics = description,
            )
            return
        }
        if (usbManager.hasPermission(candidate)) {
            connect(candidate)
        } else if (candidate.deviceName in deniedDevices) {
            mutableState.value = mutableState.value.copy(phase = ConnectionPhase.DENIED, message = "USB 访问权限被拒绝，可点击重新授权")
        } else {
            mutableState.value = mutableState.value.copy(phase = ConnectionPhase.PERMISSION, message = "请在系统弹窗中允许访问相机")
            permissionDeviceId = candidate.deviceId
            val permission = PendingIntent.getBroadcast(
                context, candidate.deviceId, Intent(permissionAction).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            try {
                usbManager.requestPermission(candidate, permission)
            } catch (error: Exception) {
                permissionDeviceId = null
                failure("无法申请 USB 权限，请重新插拔后重试", error)
            }
        }
    }

    private fun connect(device: UsbDevice) {
        if (connectionJob?.isActive == true || active != null) return
        mutableState.value = mutableState.value.copy(phase = ConnectionPhase.CONNECTING, message = "正在连接相机")
        connectionJob = scope.launch {
            connectionLock.withLock {
                var opened: MtpDevice? = null
                var openedConnection: UsbDeviceConnection? = null
                var stage = "usb_open"
                try {
                    if (!usbManager.deviceList.containsKey(device.deviceName)) throw CameraUnavailableException()
                    val connection = usbManager.openDevice(device) ?: throw IOException("usb_open")
                    openedConnection = connection
                    openingConnection = connection
                    currentCoroutineContext().ensureActive()
                    stage = "mtp_open"
                    val mtp = MtpDevice(device)
                    // MtpDevice takes ownership of connection, including on open failure.
                    if (!mtp.open(connection)) throw IOException("mtp_open")
                    opened = mtp
                    stage = "device_info"
                    val info = mtp.deviceInfo ?: throw IOException("device_info")
                    stage = "storage_ids"
                    val storageIds = mtp.storageIds ?: throw IOException("storage_ids")
                    val storages = storageIds.mapIndexed { index, id ->
                        val storage = mtp.getStorageInfo(id)
                        CameraStorage(id, "存储 ${index + 1}" + (storage?.description?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                            storage?.maxCapacity, storage?.freeSpace)
                    }
                    currentCoroutineContext().ensureActive()
                    val serial = info.serialNumber?.takeIf { it.isNotBlank() }
                    val session = Session(UUID.randomUUID().toString(), device, connection, mtp,
                        serial?.let { sha256("${info.manufacturer}\u0000${info.model}\u0000$it") },
                        info.model?.takeIf { it.isNotBlank() }, info.operationsSupported?.toSet().orEmpty())
                    // Publish on the same thread as stop/detach so a cancelled connection cannot become active.
                    withContext(Dispatchers.Main.immediate) {
                        if (!usbManager.deviceList.containsKey(device.deviceName)) throw CameraUnavailableException()
                        active = session
                        opened = null
                        if (openingConnection === connection) openingConnection = null
                        mutableState.value = ConnectionState(ConnectionPhase.CONNECTED,
                            if (storages.isEmpty()) "相机已连接，但没有可访问的存储，请检查 SD 卡" else "相机已连接",
                            session.model, session.id, storages,
                            describe(listOf(device)) + "\n支持的读取操作：" + session.operations
                                .filter { it in setOf(0x1001, 0x1004, 0x1005, 0x1007, 0x1008, 0x1009, 0x100a, 0x101b, 0x95c1) }
                                .joinToString { "0x%04x".format(it) })
                        diagnostics.event("mtp_connected")
                    }
                } catch (error: Exception) {
                    val failedDevice = opened
                    if (failedDevice != null) failedDevice.close() else openedConnection?.close()
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    withContext(Dispatchers.Main.immediate) {
                        diagnostics.event("connection_failed_$stage", error)
                        failure("无法打开相机。请关闭占用相机的系统导入界面或其他应用，重新插拔后重试", error)
                    }
                } finally {
                    if (openingConnection === openedConnection) openingConnection = null
                }
            }
        }
    }

    private fun failure(message: String, error: Throwable) {
        mutableState.value = mutableState.value.copy(phase = ConnectionPhase.FAILED, message = message, sessionId = null, storages = emptyList())
        diagnostics.event("mtp_connection_failed", error)
    }

    private fun detach() {
        releaseSession()
        mutableState.value = mutableState.value.copy(phase = ConnectionPhase.DETACHED, message = "相机已断开，请重新连接", sessionId = null, storages = emptyList())
        diagnostics.event("usb_detached")
    }

    private fun releaseSession() {
        val old = active
        active = null
        val opening = openingConnection
        openingConnection = null
        connectionJob?.cancel()
        if (old != null || opening != null) scope.launch {
            // Closing the USB connection can interrupt blocked I/O; never do this on the UI thread.
            opening?.close()
            old?.connection?.close()
            if (old != null) ioLock.withLock { old.mtp.close() }
        }
    }

    suspend fun scan(onBatch: (List<Photo>) -> Unit): Int {
        val session = active ?: throw CameraUnavailableException()
        val batch = ArrayList<Photo>(24)
        var failures = 0
        var lastEmission = System.nanoTime()
        val folderInfo = HashMap<Int, MtpObjectInfo>()
        val directoryKeys = HashMap<Int, String?>().apply { put(0, sha256("root")); put(-1, sha256("root")) }
        suspend fun directoryKey(parent: Int): String? {
            if (directoryKeys.containsKey(parent)) return directoryKeys[parent]
            val chain = ArrayList<Pair<Int, String>>()
            val visited = HashSet<Int>()
            var cursor = parent
            try {
                while (!directoryKeys.containsKey(cursor)) {
                    if (!visited.add(cursor)) { directoryKeys[parent] = null; return null }
                    val folder = folderInfo[cursor] ?: read(session.id) { it.mtp.getObjectInfo(cursor) }
                    if (folder == null || folder.format != MtpConstants.FORMAT_ASSOCIATION || folder.name.isNullOrBlank()) {
                        directoryKeys[parent] = null
                        return null
                    }
                    folderInfo[cursor] = folder
                    chain.add(cursor to folder.name)
                    cursor = folder.parent
                }
                var key = directoryKeys[cursor]
                for ((handle, name) in chain.asReversed()) {
                    key = key?.let { sha256("$it\u0000$name") }
                    directoryKeys[handle] = key
                }
                return key
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: CameraUnavailableException) { throw error }
            catch (error: Exception) {
                diagnostics.event("folder_identity_failed", error)
                directoryKeys[parent] = null
                return null
            }
        }
        suspend fun consume(handle: Int): MtpObjectInfo? {
            val info = try {
                read(session.id) { it.mtp.getObjectInfo(handle) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: CameraUnavailableException) {
                throw error
            } catch (error: Exception) {
                diagnostics.event("object_info_failed", error)
                null
            }
            if (info == null) { failures++; return null }
            if (info.format == MtpConstants.FORMAT_ASSOCIATION) folderInfo[handle] = info
            val format = PhotoFormat.fromFileName(info.name.orEmpty())
            if (format != null && info.format != MtpConstants.FORMAT_ASSOCIATION) {
                batch.add(Photo(session.id, session.cameraKey, session.model, info.storageId, handle, info.parent, directoryKey(info.parent),
                    info.name, format, info.compressedSizeLong, info.dateCreated.takeIf { it > 0 },
                    info.dateModified.takeIf { it > 0 }, info.imagePixWidth.takeIf { it > 0 },
                    info.imagePixHeight.takeIf { it > 0 }, info.thumbCompressedSizeLong))
                if (batch.size >= 24 || System.nanoTime() - lastEmission > 150_000_000) {
                    onBatch(batch.toList()); batch.clear(); lastEmission = System.nanoTime()
                }
            }
            return info
        }
        for (storage in state.value.storages) {
            // Parent 0 asks for every object across the directory tree, per Android's MTP API.
            val all = read(session.id) { it.mtp.getObjectHandles(storage.id, 0, 0) }
            if (all != null) {
                for (handle in all.distinct()) consume(handle)
            } else {
                // Some cameras only support folder enumeration; -1 means the root.
                val folders = ArrayDeque<Int>().apply { add(-1) }
                val visited = HashSet<Int>()
                while (folders.isNotEmpty()) {
                    val folder = folders.removeFirst()
                    if (!visited.add(folder)) continue
                    val children = read(session.id) { it.mtp.getObjectHandles(storage.id, 0, folder) }
                        ?: throw IOException("folder_enumeration")
                    for (handle in children) {
                        if (consume(handle)?.format == MtpConstants.FORMAT_ASSOCIATION) folders.add(handle)
                    }
                }
            }
        }
        if (batch.isNotEmpty()) onBatch(batch.toList())
        return failures
    }

    suspend fun thumbnail(photo: Photo): ByteArray? = read(photo.sessionId) { session ->
        if (photo.thumbnailSize > 8L * 1024 * 1024) null else session.mtp.getThumbnail(photo.handle)
    }

    suspend fun transfer(photo: Photo, descriptor: ParcelFileDescriptor, onBytes: (Long) -> Unit) {
        read(photo.sessionId) { session ->
            val actual = session.mtp.getObjectInfo(photo.handle) ?: throw IOException("object_info")
            if (actual.name != photo.name || actual.compressedSizeLong != photo.size || actual.storageId != photo.storageId ||
                actual.parent != photo.parentHandle || actual.dateCreated.takeIf { it > 0 } != photo.capturedAt) {
                throw IOException("stale_object")
            }
            val partial = session.partialUsable && 0x101b in session.operations && photo.size in 1..0xffffffffL
            if (partial) {
                var transferred = 0L
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor)).use { output ->
                        copyExactBytes(photo.size, output, { offset, requested, buffer ->
                            verifySession(photo.sessionId)
                            try { session.mtp.getPartialObject(photo.handle, offset, requested.toLong(), buffer) }
                            catch (error: IOException) { throw PartialReadException(error) }
                        }, { transferred = it; onBytes(it) })
                        output.fd.sync()
                    }
                    return@read
                } catch (error: PartialReadException) {
                    // A claimed operation can still be rejected. Only fallback before any bytes were written.
                    if (transferred > 0) throw error
                    currentCoroutineContext().ensureActive()
                    verifySession(photo.sessionId)
                    session.partialUsable = false
                    Os.ftruncate(descriptor.fileDescriptor, 0)
                    Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_SET)
                    diagnostics.event("partial_read_native_fallback", error)
                }
            }
            // Native importFile streams into a descriptor. No original file is held in RAM.
            if (!session.mtp.importFile(photo.handle, descriptor)) {
                diagnostics.event("native_original_read_failed")
                throw IOException("native_import")
            }
            currentCoroutineContext().ensureActive()
            verifySession(photo.sessionId)
            onBytes(photo.size)
        }
    }

    private fun verifySession(id: String): Session = active?.takeIf { it.id == id } ?: throw CameraUnavailableException()

    private suspend fun <T> read(id: String, block: suspend (Session) -> T): T = withContext(Dispatchers.IO) {
        ioLock.withLock {
            currentCoroutineContext().ensureActive()
            val session = verifySession(id)
            try {
                block(session).also { verifySession(id); currentCoroutineContext().ensureActive() }
            } catch (error: Exception) {
                if (active?.id != id) throw CameraUnavailableException()
                throw error
            }
        }
    }

    private fun supportsStillImage(device: UsbDevice): Boolean = (0 until device.interfaceCount).any { index ->
        val intf = device.getInterface(index)
        val bulk = (0 until intf.endpointCount).map { intf.getEndpoint(it) }.filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
        intf.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE && intf.interfaceSubclass == 1 && intf.interfaceProtocol == 1 &&
            bulk.any { it.direction == UsbConstants.USB_DIR_IN } && bulk.any { it.direction == UsbConstants.USB_DIR_OUT }
    }

    private fun describe(devices: List<UsbDevice>): String = if (devices.isEmpty()) "未检测到 USB 设备" else buildString {
        for (device in devices) {
            appendLine("VID=0x%04x PID=0x%04x；设备类=%d；接口数=%d".format(device.vendorId, device.productId, device.deviceClass, device.interfaceCount))
            for (index in 0 until device.interfaceCount) {
                val intf = device.getInterface(index)
                appendLine("接口 ${intf.id}：class=${intf.interfaceClass} subclass=${intf.interfaceSubclass} protocol=${intf.interfaceProtocol}")
                for (endpoint in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(endpoint)
                    appendLine("端点 0x%02x：type=%d direction=%d maxPacket=%d".format(ep.address, ep.type, ep.direction, ep.maxPacketSize))
                }
            }
        }
    }.trim()
}
