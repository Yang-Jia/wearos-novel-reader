package com.wear.novelreader.transfer

data class UploadResult(
    val ok: Boolean,
    val title: String?,
    val message: String
)

data class TransferState(
    val starting: Boolean = false,
    val running: Boolean = false,
    val port: Int = 0,
    val urls: List<String> = emptyList(),
    val error: String? = null,
    val uploads: Int = 0,
    val lastUpload: String? = null,
    val activeUploads: Int = 0
)
