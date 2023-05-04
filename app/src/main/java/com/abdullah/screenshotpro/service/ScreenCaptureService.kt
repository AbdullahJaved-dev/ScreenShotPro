package com.abdullah.screenshotpro.service

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.ImageReader.OnImageAvailableListener
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.abdullah.screenshotpro.MainActivity
import com.abdullah.screenshotpro.R
import com.abdullah.screenshotpro.data.ParcelableIntent
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Objects


class ScreenCaptureService : Service() {
    private var mMediaProjection: MediaProjection? = null
    private var mStoreDir: String? = null
    private var mImageReader: ImageReader? = null
    private var mHandler: Handler? = null
    private var mDisplay: Display? = null
    private var mVirtualDisplay: VirtualDisplay? = null
    private var mDensity = 0
    private var mWidth = 0
    private var mHeight = 0
    private var mRotation = 0
    private var mOrientationChangeCallback: OrientationChangeCallback? = null

    private var windowManager: WindowManager? = null
    private var mFloatingWidget: View? = null
    private lateinit var params: WindowManager.LayoutParams

    private val ssList = arrayListOf<Bitmap>()

    private inner class ImageAvailableListener : OnImageAvailableListener {
        override fun onImageAvailable(reader: ImageReader) {
            var fos: FileOutputStream? = null
            var bitmap: Bitmap? = null
            try {
                mImageReader!!.acquireLatestImage().use { image ->
                    if (image != null) {
                        val planes = image.planes
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * mWidth

                        // create bitmap
                        bitmap = Bitmap.createBitmap(
                            mWidth + rowPadding / pixelStride,
                            mHeight,
                            Bitmap.Config.ARGB_8888
                        )
                        bitmap?.copyPixelsFromBuffer(buffer)

                        // write bitmap to a file
                        fos =
                            FileOutputStream("$mStoreDir/myscreen_$IMAGES_PRODUCED.png")
                        bitmap?.compress(Bitmap.CompressFormat.JPEG, 100, fos)
                        IMAGES_PRODUCED++
                        Log.e(
                            TAG,
                            "captured image: $IMAGES_PRODUCED"
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                if (fos != null) {
                    try {
                        fos!!.close()
                    } catch (ioe: IOException) {
                        ioe.printStackTrace()
                    }
                }
                if (bitmap != null) {
                    bitmap!!.recycle()
                }
            }
        }
    }

    private inner class OrientationChangeCallback(context: Context?) :
        OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            val rotation = mDisplay!!.rotation
            if (rotation != mRotation) {
                mRotation = rotation
                try {
                    // clean up
                    if (mVirtualDisplay != null) mVirtualDisplay!!.release()
                    if (mImageReader != null) mImageReader!!.setOnImageAvailableListener(null, null)

                    // re-create virtual display depending on device width / height
                    createVirtualDisplay()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private inner class MediaProjectionStopCallback : MediaProjection.Callback() {
        override fun onStop() {
            Log.e(TAG, "stopping projection.")
            mHandler?.post {
                if (mVirtualDisplay != null) mVirtualDisplay!!.release()
                if (mImageReader != null) mImageReader!!.setOnImageAvailableListener(null, null)
                if (mOrientationChangeCallback != null) mOrientationChangeCallback!!.disable()
                mMediaProjection!!.unregisterCallback(this@MediaProjectionStopCallback)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()

        // create store dir
        /*val externalFilesDir: File? = getExternalFilesDir(null)
        if (externalFilesDir != null) {
            mStoreDir = externalFilesDir.absolutePath + "/screenshots/"
            val storeDirectory = mStoreDir?.let { File(it) }
            if (storeDirectory != null) {
                if (!storeDirectory.exists()) {
                    val success = storeDirectory.mkdirs()
                    if (!success) {
                        Log.e(TAG, "failed to create file storage directory.")
                        stopSelf()
                    }
                }
            }
        } else {
            Log.e(TAG, "failed to create file storage directory, getExternalFilesDir is null.")
            stopSelf()
        }*/

        // start capture handling thread
        object : Thread() {
            override fun run() {
                Looper.prepare()
                mHandler = Handler(Looper.getMainLooper())
                Looper.loop()
            }
        }.start()
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (isStartCommand(intent)) {
            // create notification
            createNotificationChannel()

            val notificationIntent = Intent(this, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                this,
                0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Foreground Service")
                .setContentText("Running...")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    1,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(1, notification)
            }

            if (windowManager != null && mFloatingWidget?.isShown == true) {
                windowManager?.removeView(mFloatingWidget)
                mFloatingWidget = null
                windowManager = null
            }

            mFloatingWidget = LayoutInflater.from(this).inflate(R.layout.custom_popup_window, null)

            params = if (Build.VERSION.SDK_INT > Build.VERSION_CODES.O) {
                WindowManager.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
                )
            } else {
                WindowManager.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
                )
            }
            params.gravity = Gravity.TOP or Gravity.START
            params.x = 0
            params.y = 300

            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            windowManager?.addView(mFloatingWidget, params)

            val btnCapture = mFloatingWidget?.findViewById<Button>(R.id.btn_capture)
            val btnStop = mFloatingWidget?.findViewById<Button>(R.id.btn_stop)

            btnCapture?.setOnClickListener {
                // start projection
                val resultCode = intent.getIntExtra(RESULT_CODE, Activity.RESULT_CANCELED)
                val data = intent.getParcelableExtra<ParcelableIntent>(DATA)
                startProjection(resultCode, data?.intent)
            }

            btnStop?.setOnClickListener {
                Intent(this@ScreenCaptureService, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (ssList.isEmpty()) {
                        Toast.makeText(this@ScreenCaptureService, "No SS", Toast.LENGTH_SHORT)
                            .show()
                    } else {
                        val b: Bitmap = if (ssList.size == 1) ssList.first()
                        else {
                            val a = combineBitmapsVertically(ssList)
                            ssList.clear()
                            a
                        }
                        val stream = ByteArrayOutputStream()
                        b.compress(Bitmap.CompressFormat.JPEG, 10, stream)
                        val byteArray = stream.toByteArray()
                        putExtra("bitmap", byteArray)
                    }

                    //putExtra("List",ssList)
                    startActivity(this)
                    stopSelf()
                }
            }

            initializeView()


        } else if (isStopCommand(intent)) {
            stopProjection()
            stopSelf()
        } else {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun initializeView() {
        mFloatingWidget!!.findViewById<View>(R.id.rvPopup)
            .setOnTouchListener(object : View.OnTouchListener {
                private var initialX = 0
                private var initialY = 0
                private var initialTouchX = 0f
                private var initialTouchY = 0f
                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = params.x
                            initialY = params.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            return true
                        }

                        MotionEvent.ACTION_UP -> {
                            //when the drag is ended switching the state of the widget
                            /*collapsedView?.visibility = View.GONE
                            expandedView?.visibility = View.VISIBLE*/
                            return true
                        }

                        MotionEvent.ACTION_MOVE -> {
                            //this code is helping the widget to move around the screen with fingers
                            params.x = initialX + (event.rawX - initialTouchX).toInt()
                            params.y = initialY + (event.rawY - initialTouchY).toInt()
                            windowManager!!.updateViewLayout(mFloatingWidget, params)
                            return true
                        }
                    }
                    return false
                }
            })
    }

    override fun onDestroy() {
        super.onDestroy()
        if (mFloatingWidget != null) {
            windowManager?.removeView(mFloatingWidget)
        }
    }

    private fun startProjection(resultCode: Int, data: Intent?) {
        val mpManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager?
        if (mMediaProjection == null) {
            mMediaProjection = mpManager!!.getMediaProjection(resultCode, data!!)
            if (mMediaProjection != null) {
                // display metrics
                mDensity = Resources.getSystem().displayMetrics.densityDpi
                val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager?
                mDisplay = windowManager!!.defaultDisplay

                // create virtual display depending on device width / height
                createVirtualDisplay()

                // register orientation change callback
                /*mOrientationChangeCallback = OrientationChangeCallback(this)
                if (mOrientationChangeCallback!!.canDetectOrientation()) {
                    mOrientationChangeCallback!!.enable()
                }*/

                // register media projection stop callback
                //mMediaProjection!!.registerCallback(MediaProjectionStopCallback(), mHandler)
            }
        } else {
            mDensity = Resources.getSystem().displayMetrics.densityDpi
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager?
            mDisplay = windowManager!!.defaultDisplay

            // create virtual display depending on device width / height
            createVirtualDisplay()
        }
    }

    private fun stopProjection() {
        mHandler?.post {
            if (mMediaProjection != null) {
                mMediaProjection!!.stop()
            }
        }
    }

    @SuppressLint("WrongConstant")
    private fun createVirtualDisplay() {
        // get width and height
        mWidth = Resources.getSystem().displayMetrics.widthPixels
        mHeight = Resources.getSystem().displayMetrics.heightPixels

        // start capture reader
        mImageReader = ImageReader.newInstance(mWidth, mHeight, PixelFormat.RGBA_8888, 2)
        mVirtualDisplay = mMediaProjection!!.createVirtualDisplay(
            SCREENCAP_NAME, mWidth, mHeight,
            mDensity,
            virtualDisplayFlags, mImageReader!!.surface, null, mHandler
        )

        var image: Image? = null
        var retries = 0
        while (image == null && retries < 5) {
            Thread.sleep(1000)
            image = mImageReader?.acquireNextImage()
            retries++
        }

        if (image != null) {
            // Process the image here
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * mWidth
            val bitmap = Bitmap.createBitmap(
                mWidth + rowPadding / pixelStride,
                mHeight,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            mImageReader?.close()
            image.close()
            mVirtualDisplay?.release()
            mMediaProjection?.stop()
            mImageReader = null
            mVirtualDisplay = null
            mMediaProjection = null
            ssList.add(bitmap)
            Toast.makeText(this, "Captured", Toast.LENGTH_SHORT).show()
        }
        //mImageReader!!.setOnImageAvailableListener(ImageAvailableListener(), mHandler)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Foreground Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    companion object {
        const val CHANNEL_ID = "ForegroundServiceChannel"
        private const val TAG = "ScreenCaptureService"
        private const val RESULT_CODE = "RESULT_CODE"
        private const val DATA = "DATA"
        private const val ACTION = "ACTION"
        private const val START = "START"
        private const val STOP = "STOP"
        private const val SCREENCAP_NAME = "screencap"
        private var IMAGES_PRODUCED = 0

        fun getStartIntent(context: Context?, resultCode: Int, data: ParcelableIntent): Intent {
            val intent = Intent(context, ScreenCaptureService::class.java)
            intent.putExtra(ACTION, START)
            intent.putExtra(RESULT_CODE, resultCode)
            intent.putExtra(DATA, data)
            return intent
        }

        fun getStopIntent(context: Context?): Intent {
            val intent = Intent(context, ScreenCaptureService::class.java)
            intent.putExtra(ACTION, STOP)
            return intent
        }

        private fun isStartCommand(intent: Intent): Boolean {
            return (intent.hasExtra(RESULT_CODE) && intent.hasExtra(DATA)
                    && intent.hasExtra(ACTION) && Objects.equals(
                intent.getStringExtra(ACTION),
                START
            ))
        }

        private fun isStopCommand(intent: Intent): Boolean {
            return intent.hasExtra(ACTION) && Objects.equals(intent.getStringExtra(ACTION), STOP)
        }

        private val virtualDisplayFlags: Int
            get() = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
    }

    private fun combineBitmapsVertically(bitmaps: List<Bitmap>): Bitmap {
        // Calculate the size of the new bitmap
        val width = bitmaps.maxOfOrNull { it.width } ?: 0
        val height = bitmaps.sumOf { it.height }

        // Create the new bitmap
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        // Draw the bitmaps onto the new bitmap
        val canvas = Canvas(result)
        var y = 0f
        for (bitmap in bitmaps) {
            canvas.drawBitmap(bitmap, 0f, y, null)
            y += bitmap.height.toFloat()
        }
        return result
    }
}