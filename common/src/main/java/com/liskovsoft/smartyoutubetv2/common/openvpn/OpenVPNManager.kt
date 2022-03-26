package com.liskovsoft.smartyoutubetv2.common.openvpn

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import com.liskovsoft.openvpn.OnVPNStatusChangeListener
import com.liskovsoft.openvpn.VPNService
import com.liskovsoft.smartyoutubetv2.common.BuildConfig
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs
import java.io.File

class OpenVPNManager(context: Context, val callback: OpenVPNCallback? = null) {
    private companion object {
        val TAG: String = OpenVPNManager::class.java.simpleName
    }

    class OpenVPNInfo(val configAddress: String)

    interface OpenVPNCallback {
        fun onProfileLoaded(profileLoaded: Boolean) {}
        fun onVPNStatusChanged(vpnActivated: Boolean) {}
        fun onConfigDownloadStart() {}
        fun onConfigDownloadProgress(progress: Int) {}
        fun onConfigDownloadEnd() {}
        fun onConfigDownloadError() {}
    }

    private val context = context.applicationContext
    private val prefs = AppPrefs.instance(context.applicationContext)
    private val routineName = "AZDownload"
    private var isConnected = false
    private var isDownload = true
    private val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    private val ovpnFile = File(downloadDir, "config.ovpn")
    private val numVPNService by lazy {
        VPNService(context).apply {
            launchVPN(prefs.openVPNConfigUri)
            setOnVPNStatusChangeListener(object : OnVPNStatusChangeListener {
                override fun onProfileLoaded(profileLoaded: Boolean) {
                    callback?.onProfileLoaded(profileLoaded)
                }

                override fun onVPNStatusChanged(vpnActivated: Boolean) {
                    isConnected = vpnActivated
                    callback?.onVPNStatusChanged(vpnActivated)
                }
            })
        }
    }

    val isOpenVPNSupported: Boolean
        get() = Build.VERSION.SDK_INT >= 19

    val isOpenVPNEnabled: Boolean
        get() = prefs.isOpenVPNEnabled

    val openVPNConfigUri: String
        get() = prefs.openVPNConfigUri

    fun saveOpenVPNInfoToPrefs(info: OpenVPNInfo? = null, enabled: Boolean) {
        info?.apply {
            prefs.openVPNConfigUri = configAddress
        }

        prefs.isOpenVPNEnabled = enabled
    }

    fun configureOpenVPN() {
        downloadConfig()

        numVPNService.init() // toggle connection

        isConnected = numVPNService.cStatus
    }

    private fun downloadConfig() {
        isDownload = true
        downloadOVPN()
    }

    private fun cancelDownloadConfig() {
        Handler(Looper.getMainLooper()).post {
            Coroutines.cancel(routineName)
            isDownload = false
        }
    }

    private fun downloadOVPN() {
        callback?.onConfigDownloadStart()

        Coroutines.launch(routineName) {
            try {
                Download.download(prefs.openVPNConfigUri, ovpnFile) { prc ->
                    Handler(Looper.getMainLooper()).post {
                        try {
                            callback?.onConfigDownloadProgress(prc)
                        } catch (e: Exception) {
                            isDownload = false
                        }
                    }
                    isDownload
                }
                Handler(Looper.getMainLooper()).post {
                    try {
                        callback?.onConfigDownloadEnd()
                        openOVPN()
                    } catch (e: Exception) {
                    }
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    try {
                        callback?.onConfigDownloadError()
                    } catch (e: Exception) {
                    }
                }
            }
        }
    }

    private fun openOVPN() {
        Handler(Looper.getMainLooper()).post {
            try {
                val intent = Intent(Intent.ACTION_VIEW)
                val path = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                    Uri.fromFile(ovpnFile)
                } else {
                    intent.flags += Intent.FLAG_GRANT_READ_URI_PERMISSION
                    FileProvider.getUriForFile(
                        context,
                        BuildConfig.APPLICATION_ID + ".provider",
                        ovpnFile
                    )
                }
                intent.setDataAndType(path, "application/x-openvpn-profile")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                val activities: List<ResolveInfo> = context.packageManager.queryIntentActivities(intent, 0)
                if (activities.isNotEmpty()) { // && vpnConnected
                    context.startActivity(intent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}