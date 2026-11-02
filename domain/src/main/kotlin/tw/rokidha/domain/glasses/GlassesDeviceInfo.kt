package tw.rokidha.domain.glasses

/** Device information reported by the glasses. Fields are null when not reported. */
data class GlassesDeviceInfo(
    val deviceName: String?,
    val systemVersion: String?,
    val batteryLevel: Int?,
    val charging: Boolean?,
    val wearingStatus: String?,
    /** Raw `toString()` of the SDK object, kept for verification logs only. */
    val raw: String,
)
