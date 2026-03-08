package ninja.unagi.enrichment

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import ninja.unagi.data.DeviceEnrichmentEntity
import ninja.unagi.scan.ScanController
import org.json.JSONArray
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

data class BleDeviceEnrichmentResult(
  val lastQueryTimestamp: Long,
  val queryMethod: String,
  val servicesPresent: List<String>,
  val disAvailable: Boolean,
  val disReadStatus: String,
  val manufacturerName: String?,
  val modelNumber: String?,
  val serialNumber: String?,
  val hardwareRevision: String?,
  val firmwareRevision: String?,
  val softwareRevision: String?,
  val systemId: String?,
  val pnpVendorIdSource: Int?,
  val pnpVendorId: Int?,
  val pnpProductId: Int?,
  val pnpProductVersion: Int?,
  val errorCode: Int?,
  val errorMessage: String?,
  val connectDurationMs: Long?,
  val servicesDiscovered: Int,
  val characteristicReadSuccessCount: Int,
  val characteristicReadFailureCount: Int,
  val finalGattStatus: Int?
) {
  fun toEntity(deviceKey: String): DeviceEnrichmentEntity {
    return DeviceEnrichmentEntity(
      deviceKey = deviceKey,
      lastQueryTimestamp = lastQueryTimestamp,
      queryMethod = queryMethod,
      servicesPresentJson = JSONArray(servicesPresent).toString(),
      disAvailable = disAvailable,
      disReadStatus = disReadStatus,
      manufacturerName = manufacturerName,
      modelNumber = modelNumber,
      serialNumber = serialNumber,
      hardwareRevision = hardwareRevision,
      firmwareRevision = firmwareRevision,
      softwareRevision = softwareRevision,
      systemId = systemId,
      pnpVendorIdSource = pnpVendorIdSource,
      pnpVendorId = pnpVendorId,
      pnpProductId = pnpProductId,
      pnpProductVersion = pnpProductVersion,
      errorCode = errorCode,
      errorMessage = errorMessage,
      connectDurationMs = connectDurationMs,
      servicesDiscovered = servicesDiscovered,
      characteristicReadSuccessCount = characteristicReadSuccessCount,
      characteristicReadFailureCount = characteristicReadFailureCount,
      finalGattStatus = finalGattStatus
    )
  }
}

class BleDeviceInfoQueryClient(
  private val context: Context,
  private val bluetoothAdapter: BluetoothAdapter?,
  private val scanController: ScanController?
) {
  suspend fun query(address: String, rawAddressType: Int?): BleDeviceEnrichmentResult {
    return withContext(Dispatchers.IO) {
      val startedAt = System.currentTimeMillis()
      if (!hasConnectPermission()) {
        return@withContext failureResult(
          startedAt = startedAt,
          status = STATUS_PERMISSION_DENIED,
          message = "Bluetooth Connect permission is missing",
          disReadStatus = "permission_denied"
        )
      }

      val adapter = bluetoothAdapter ?: return@withContext failureResult(
        startedAt = startedAt,
        status = STATUS_ADAPTER_UNAVAILABLE,
        message = "Bluetooth adapter unavailable",
        disReadStatus = "preflight_failed"
      )

      if (!adapter.isEnabled) {
        return@withContext failureResult(
          startedAt = startedAt,
          status = STATUS_BLUETOOTH_OFF,
          message = "Bluetooth is off",
          disReadStatus = "preflight_failed"
        )
      }

      scanController?.stopScan()
      if (adapter.isDiscovering) {
        runCatching { adapter.cancelDiscovery() }
      }

      val device = resolveRemoteDevice(adapter, address, rawAddressType) ?: return@withContext failureResult(
        startedAt = startedAt,
        status = STATUS_INVALID_DEVICE,
        message = "Unable to resolve BLE device address",
        disReadStatus = "preflight_failed"
      )

      val resultDeferred = CompletableDeferred<BleDeviceEnrichmentResult>()
      val callback = DeviceInfoQueryCallback(startedAt, resultDeferred)
      val gattRef = AtomicReference<BluetoothGatt?>()

      try {
        val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
          device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
          @Suppress("DEPRECATION")
          device.connectGatt(context, false, callback)
        }
        if (gatt == null) {
          return@withContext failureResult(
            startedAt = startedAt,
            status = STATUS_CONNECT_RETURNED_NULL,
            message = "connectGatt returned null",
            disReadStatus = "connect_failed"
          )
        }
        gattRef.set(gatt)
        callback.attachGatt(gatt)

        withTimeout(QUERY_TIMEOUT_MS) {
          resultDeferred.await()
        }
      } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
        callback.timeout()
      } finally {
        gattRef.get()?.let { gatt ->
          runCatching { gatt.disconnect() }
          runCatching { gatt.close() }
        }
      }
    }
  }

  private fun hasConnectPermission(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
      return true
    }
    return ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.BLUETOOTH_CONNECT
    ) == PackageManager.PERMISSION_GRANTED
  }

  private fun resolveRemoteDevice(
    adapter: BluetoothAdapter,
    address: String,
    rawAddressType: Int?
  ): BluetoothDevice? {
    return runCatching {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && rawAddressType != null) {
        adapter.getRemoteLeDevice(address, rawAddressType)
      } else {
        adapter.getRemoteDevice(address)
      }
    }.getOrNull()
  }

  private fun failureResult(
    startedAt: Long,
    status: Int,
    message: String,
    disReadStatus: String
  ): BleDeviceEnrichmentResult {
    return BleDeviceEnrichmentResult(
      lastQueryTimestamp = System.currentTimeMillis(),
      queryMethod = QUERY_METHOD_BLE_GATT_DIS,
      servicesPresent = emptyList(),
      disAvailable = false,
      disReadStatus = disReadStatus,
      manufacturerName = null,
      modelNumber = null,
      serialNumber = null,
      hardwareRevision = null,
      firmwareRevision = null,
      softwareRevision = null,
      systemId = null,
      pnpVendorIdSource = null,
      pnpVendorId = null,
      pnpProductId = null,
      pnpProductVersion = null,
      errorCode = status,
      errorMessage = message,
      connectDurationMs = System.currentTimeMillis() - startedAt,
      servicesDiscovered = 0,
      characteristicReadSuccessCount = 0,
      characteristicReadFailureCount = 0,
      finalGattStatus = status
    )
  }

  private class DeviceInfoQueryCallback(
    private val startedAt: Long,
    private val resultDeferred: CompletableDeferred<BleDeviceEnrichmentResult>
  ) : BluetoothGattCallback() {
    private val state = QueryState(lastQueryTimestamp = System.currentTimeMillis())
    private val pendingCharacteristicUuids = ArrayDeque<UUID>()
    private var gatt: BluetoothGatt? = null

    fun attachGatt(value: BluetoothGatt) {
      gatt = value
    }

    fun timeout(): BleDeviceEnrichmentResult {
      return complete(
        disReadStatus = "timeout",
        errorCode = STATUS_TIMEOUT,
        errorMessage = "GATT query timed out"
      )
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
      state.finalGattStatus = status
      if (status != BluetoothGatt.GATT_SUCCESS) {
        complete(
          disReadStatus = "connect_failed",
          errorCode = status,
          errorMessage = "GATT connection failed with status $status"
        )
        return
      }

      when (newState) {
        BluetoothProfile.STATE_CONNECTED -> {
          val started = gatt.discoverServices()
          if (!started) {
            complete(
              disReadStatus = "services_discovery_failed",
              errorCode = STATUS_DISCOVER_SERVICES_FAILED,
              errorMessage = "discoverServices() returned false"
            )
          }
        }
        BluetoothProfile.STATE_DISCONNECTED -> {
          if (!resultDeferred.isCompleted) {
            complete(
              disReadStatus = if (state.disAvailable) "disconnected" else "connect_failed",
              errorCode = status,
              errorMessage = "Disconnected before the query completed"
            )
          }
        }
      }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
      state.finalGattStatus = status
      state.connectDurationMs = System.currentTimeMillis() - startedAt
      if (status != BluetoothGatt.GATT_SUCCESS) {
        complete(
          disReadStatus = "services_discovery_failed",
          errorCode = status,
          errorMessage = "Service discovery failed with status $status"
        )
        return
      }

      val services = gatt.services.orEmpty()
      state.servicesPresent = services.map { it.uuid.toString() }
      state.servicesDiscovered = services.size
      val disService = gatt.getService(UUID_DEVICE_INFORMATION)
      state.disAvailable = disService != null

      if (disService == null) {
        complete(disReadStatus = "no_dis")
        return
      }

      pendingCharacteristicUuids.clear()
      DIS_CHARACTERISTIC_UUIDS.forEach { uuid ->
        if (disService.getCharacteristic(uuid) != null) {
          pendingCharacteristicUuids.add(uuid)
        }
      }

      if (pendingCharacteristicUuids.isEmpty()) {
        complete(disReadStatus = "dis_service_present_no_characteristics")
        return
      }

      readNext(disService)
    }

    @Deprecated("Uses pre-API-33 callback signature")
    @Suppress("DEPRECATION")
    override fun onCharacteristicRead(
      gatt: BluetoothGatt,
      characteristic: BluetoothGattCharacteristic,
      status: Int
    ) {
      handleCharacteristicRead(characteristic, characteristic.value, status)
    }

    override fun onCharacteristicRead(
      gatt: BluetoothGatt,
      characteristic: BluetoothGattCharacteristic,
      value: ByteArray,
      status: Int
    ) {
      handleCharacteristicRead(characteristic, value, status)
    }

    private fun handleCharacteristicRead(
      characteristic: BluetoothGattCharacteristic,
      value: ByteArray?,
      status: Int
    ) {
      state.finalGattStatus = status
      if (status == BluetoothGatt.GATT_SUCCESS && value != null) {
        state.characteristicReadSuccessCount += 1
        parseCharacteristic(characteristic.uuid, value)
      } else {
        state.characteristicReadFailureCount += 1
        state.errorCode = status
        state.errorMessage = state.errorMessage ?: "Characteristic read failed for ${characteristic.uuid}"
      }

      val disService = gatt?.getService(UUID_DEVICE_INFORMATION)
      if (disService == null) {
        complete(disReadStatus = "dis_missing_during_read")
        return
      }

      readNext(disService)
    }

    private fun readNext(disService: android.bluetooth.BluetoothGattService) {
      while (pendingCharacteristicUuids.isNotEmpty()) {
        val uuid = pendingCharacteristicUuids.removeFirst()
        val characteristic = disService.getCharacteristic(uuid) ?: continue
        val started = gatt?.readCharacteristic(characteristic) == true
        if (started) {
          return
        }
        state.characteristicReadFailureCount += 1
        state.errorCode = STATUS_READ_START_FAILED
        state.errorMessage = state.errorMessage ?: "Failed to start characteristic read for $uuid"
      }

      complete(
        disReadStatus = if (state.characteristicReadSuccessCount > 0) {
          "success"
        } else {
          "dis_service_present_no_readable_characteristics"
        }
      )
    }

    private fun parseCharacteristic(uuid: UUID, value: ByteArray) {
      when (uuid) {
        UUID_MANUFACTURER_NAME -> state.manufacturerName = parseUtf8(value)
        UUID_MODEL_NUMBER -> state.modelNumber = parseUtf8(value)
        UUID_SERIAL_NUMBER -> state.serialNumber = parseUtf8(value)
        UUID_HARDWARE_REVISION -> state.hardwareRevision = parseUtf8(value)
        UUID_FIRMWARE_REVISION -> state.firmwareRevision = parseUtf8(value)
        UUID_SOFTWARE_REVISION -> state.softwareRevision = parseUtf8(value)
        UUID_SYSTEM_ID -> state.systemId = value.toHexString(separator = ":")
        UUID_PNP_ID -> parsePnpId(value)
      }
    }

    private fun parsePnpId(value: ByteArray) {
      if (value.size < 7) {
        return
      }
      val buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
      state.pnpVendorIdSource = buffer.get().toInt() and 0xFF
      state.pnpVendorId = buffer.short.toInt() and 0xFFFF
      state.pnpProductId = buffer.short.toInt() and 0xFFFF
      state.pnpProductVersion = buffer.short.toInt() and 0xFFFF
    }

    private fun complete(
      disReadStatus: String,
      errorCode: Int? = state.errorCode,
      errorMessage: String? = state.errorMessage
    ): BleDeviceEnrichmentResult {
      val result = BleDeviceEnrichmentResult(
        lastQueryTimestamp = state.lastQueryTimestamp,
        queryMethod = QUERY_METHOD_BLE_GATT_DIS,
        servicesPresent = state.servicesPresent,
        disAvailable = state.disAvailable,
        disReadStatus = disReadStatus,
        manufacturerName = state.manufacturerName,
        modelNumber = state.modelNumber,
        serialNumber = state.serialNumber,
        hardwareRevision = state.hardwareRevision,
        firmwareRevision = state.firmwareRevision,
        softwareRevision = state.softwareRevision,
        systemId = state.systemId,
        pnpVendorIdSource = state.pnpVendorIdSource,
        pnpVendorId = state.pnpVendorId,
        pnpProductId = state.pnpProductId,
        pnpProductVersion = state.pnpProductVersion,
        errorCode = errorCode,
        errorMessage = errorMessage,
        connectDurationMs = state.connectDurationMs ?: (System.currentTimeMillis() - startedAt),
        servicesDiscovered = state.servicesDiscovered,
        characteristicReadSuccessCount = state.characteristicReadSuccessCount,
        characteristicReadFailureCount = state.characteristicReadFailureCount,
        finalGattStatus = state.finalGattStatus
      )
      resultDeferred.complete(result)
      return result
    }

    private fun parseUtf8(value: ByteArray): String? {
      return value.toString(Charsets.UTF_8)
        .replace('\u0000', ' ')
        .trim()
        .takeIf { it.isNotEmpty() }
    }

    private fun ByteArray.toHexString(separator: String = ""): String {
      return joinToString(separator) { byte -> "%02X".format(byte) }
    }
  }

  private data class QueryState(
    val lastQueryTimestamp: Long,
    var servicesPresent: List<String> = emptyList(),
    var disAvailable: Boolean = false,
    var manufacturerName: String? = null,
    var modelNumber: String? = null,
    var serialNumber: String? = null,
    var hardwareRevision: String? = null,
    var firmwareRevision: String? = null,
    var softwareRevision: String? = null,
    var systemId: String? = null,
    var pnpVendorIdSource: Int? = null,
    var pnpVendorId: Int? = null,
    var pnpProductId: Int? = null,
    var pnpProductVersion: Int? = null,
    var errorCode: Int? = null,
    var errorMessage: String? = null,
    var connectDurationMs: Long? = null,
    var servicesDiscovered: Int = 0,
    var characteristicReadSuccessCount: Int = 0,
    var characteristicReadFailureCount: Int = 0,
    var finalGattStatus: Int? = null
  )

  companion object {
    const val QUERY_METHOD_BLE_GATT_DIS = "BLE_GATT_DIS"

    private const val QUERY_TIMEOUT_MS = 20_000L

    private const val STATUS_PERMISSION_DENIED = -1001
    private const val STATUS_ADAPTER_UNAVAILABLE = -1002
    private const val STATUS_BLUETOOTH_OFF = -1003
    private const val STATUS_INVALID_DEVICE = -1004
    private const val STATUS_CONNECT_RETURNED_NULL = -1005
    private const val STATUS_DISCOVER_SERVICES_FAILED = -1006
    private const val STATUS_READ_START_FAILED = -1007
    private const val STATUS_TIMEOUT = -1008

    private val UUID_DEVICE_INFORMATION = UUID.fromString("0000180A-0000-1000-8000-00805F9B34FB")
    private val UUID_MANUFACTURER_NAME = UUID.fromString("00002A29-0000-1000-8000-00805F9B34FB")
    private val UUID_MODEL_NUMBER = UUID.fromString("00002A24-0000-1000-8000-00805F9B34FB")
    private val UUID_SERIAL_NUMBER = UUID.fromString("00002A25-0000-1000-8000-00805F9B34FB")
    private val UUID_HARDWARE_REVISION = UUID.fromString("00002A27-0000-1000-8000-00805F9B34FB")
    private val UUID_FIRMWARE_REVISION = UUID.fromString("00002A26-0000-1000-8000-00805F9B34FB")
    private val UUID_SOFTWARE_REVISION = UUID.fromString("00002A28-0000-1000-8000-00805F9B34FB")
    private val UUID_SYSTEM_ID = UUID.fromString("00002A23-0000-1000-8000-00805F9B34FB")
    private val UUID_PNP_ID = UUID.fromString("00002A50-0000-1000-8000-00805F9B34FB")

    private val DIS_CHARACTERISTIC_UUIDS = listOf(
      UUID_MANUFACTURER_NAME,
      UUID_MODEL_NUMBER,
      UUID_SERIAL_NUMBER,
      UUID_HARDWARE_REVISION,
      UUID_FIRMWARE_REVISION,
      UUID_SOFTWARE_REVISION,
      UUID_SYSTEM_ID,
      UUID_PNP_ID
    )
  }
}
