package com.mylivestreamer.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface

/** Sends the front camera into a SurfaceTexture owned by the stream's GL overlay filter. */
class CameraOverlay(private val ctx: Context) {
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var thread: HandlerThread? = null
    private var surface: Surface? = null

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun start(st: SurfaceTexture) {
        stop()
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = mgr.cameraIdList.firstOrNull {
            mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_FRONT
        } ?: return
        st.setDefaultBufferSize(640, 480)
        val s = Surface(st)
        surface = s
        val th = HandlerThread("mls-cam").also { it.start() }
        thread = th
        val h = Handler(th.looper)
        mgr.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(cd: CameraDevice) {
                device = cd
                try {
                    val req = cd.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    req.addTarget(s)
                    cd.createCaptureSession(listOf(s), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(cs: CameraCaptureSession) {
                            session = cs
                            runCatching { cs.setRepeatingRequest(req.build(), null, h) }
                        }

                        override fun onConfigureFailed(cs: CameraCaptureSession) {}
                    }, h)
                } catch (_: Exception) {
                    cd.close()
                }
            }

            override fun onDisconnected(cd: CameraDevice) {
                cd.close()
            }

            override fun onError(cd: CameraDevice, error: Int) {
                cd.close()
            }
        }, h)
    }

    fun stop() {
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { surface?.release() }
        runCatching { thread?.quitSafely() }
        session = null
        device = null
        surface = null
        thread = null
    }
}
