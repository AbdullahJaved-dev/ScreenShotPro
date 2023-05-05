package com.abdullah.screenshotpro

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.abdullah.screenshotpro.data.ParcelableIntent
import com.abdullah.screenshotpro.databinding.ActivityMainBinding
import com.abdullah.screenshotpro.service.ScreenCaptureService
import java.io.File
import java.io.FileOutputStream


const val STORAGE_PERMISSION = android.Manifest.permission.WRITE_EXTERNAL_STORAGE

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var bitmap: Bitmap? = null

    private val requestStoragePermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (isGranted) {
                bitmap?.let { saveSS(it) } ?: kotlin.run {
                    Toast.makeText(this, "No Image", Toast.LENGTH_SHORT).show()
                }
            } else {
                checkStoragePermission()
            }
        }

    @RequiresApi(Build.VERSION_CODES.M)
    private val requestOverlayPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                captureScreenshot(this)
            } else {
                if (!Settings.canDrawOverlays(this)) {
                    captureScreenshot(this)
                } else {
                    Toast.makeText(this, "Overlay Permission is required", Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }

    private val startMediaProjectionManager =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                ContextCompat.startForegroundService(
                    this,
                    ScreenCaptureService.getStartIntent(
                        this,
                        it.resultCode,
                        ParcelableIntent(it.data!!)
                    )
                )
                this.finishAffinity()
            } else {
                Toast.makeText(this, "Media Projection Canceled", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (intent != null && intent.getByteArrayExtra("bitmap") != null) {
            val byteArray = intent.getByteArrayExtra("bitmap")
            bitmap = BitmapFactory.decodeByteArray(byteArray, 0, byteArray?.size ?: 0)
            bitmap?.let {
                binding.ivSS.setImageBitmap(it)
                binding.btnSave.visibility = View.VISIBLE
            }
        }

        binding.btnSave.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                bitmap?.let { saveSS(it) } ?: kotlin.run {
                    Toast.makeText(this, "No Image", Toast.LENGTH_SHORT).show()
                }
            } else {
                checkStoragePermission()
            }

        }

        binding.btnScreenshot.setOnClickListener {
            askPermission()
        }
    }

    private fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    STORAGE_PERMISSION
                ) == PackageManager.PERMISSION_GRANTED -> {
                    bitmap?.let { saveSS(it) } ?: kotlin.run {
                        Toast.makeText(this, "No Image", Toast.LENGTH_SHORT).show()
                    }
                }

                shouldShowRequestPermissionRationale(STORAGE_PERMISSION) -> {
                    requestStoragePermissionLauncher.launch(STORAGE_PERMISSION)
                }

                else -> {
                    requestStoragePermissionLauncher.launch(
                        STORAGE_PERMISSION
                    )
                }
            }
        } else {
            when (PackageManager.PERMISSION_GRANTED) {
                ContextCompat.checkSelfPermission(
                    this,
                    STORAGE_PERMISSION
                ) -> {
                    bitmap?.let { saveSS(it) } ?: kotlin.run {
                        Toast.makeText(this, "No Image", Toast.LENGTH_SHORT).show()
                    }
                }

                else -> {
                    requestStoragePermissionLauncher.launch(
                        STORAGE_PERMISSION
                    )
                }
            }
        }
    }

    private fun saveSS(bitmap: Bitmap) {
        val filename = "SS_${System.currentTimeMillis()}.png"
        val downloadDirectory =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

        if (!downloadDirectory.exists()) {
            downloadDirectory.mkdirs()
        }

        // Create the file object
        val file = File(downloadDirectory, filename)

        // Save the Bitmap to the file
        try {
            val out = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.flush()
            out.close()

            MediaScannerConnection.scanFile(
                this, arrayOf(file.absolutePath), null, null
            )

            // Show a success message
            Toast.makeText(this, "Image saved to Downloads", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            // Show an error message
            Toast.makeText(this, "Error saving image", Toast.LENGTH_SHORT).show()
        }
    }

    private fun askPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                requestOverlayPermission.launch(intent)
            } else {
                captureScreenshot(this)
            }
        } else {
            captureScreenshot(this)
        }

    }

    private fun captureScreenshot(activity: Activity) {
        mediaProjectionManager =
            activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startMediaProjectionManager.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    /*@SuppressLint("WrongConstant")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == 100) {
            if (resultCode == RESULT_OK) {
                captureScreenshot(this)
            } else {
                "Canceled"
            }
            *//*val serviceIntent = Intent(this, FloatingWidgetService::class.java)
            serviceIntent.putExtra("videoURI", videoURI)
            startService()*//*

        } else if (requestCode == 777) {
            if (resultCode == RESULT_OK) {

            } else {
                "Canceled"
            }
        } else {
            "OK"
        }

        *//*ContextCompat.startForegroundService(
            this, Intent(this, ScreenCaptureService::class.java)
        )*//*

        *//*val projection = mediaProjectionManager.getMediaProjection(Activity.RESULT_OK, data!!)

        if (projection == null) {
            Log.d("TAG", "captureScreenshot: ")
            return
        }

        val screenWidth = Resources.getSystem().displayMetrics.widthPixels
        val screenHeight = Resources.getSystem().displayMetrics.heightPixels
        val imageReader = ImageReader.newInstance(
            screenWidth, screenHeight, PixelFormat.RGBA_8888, 5
        )
        val virtualDisplay = projection.createVirtualDisplay(
            "Screenshot",
            screenWidth,
            screenHeight,
            resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            null
        )
        var image: Image? = null
        var retries = 0
        while (image == null && retries < 5) {
            Thread.sleep(1000)
            image = imageReader.acquireNextImage()
            retries++
        }

        if (image != null) {
            // Process the image here
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth
            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            imageReader.close()
            image.close()
            virtualDisplay.release()
            projection.stop()
            ssList.add(bitmap)
            Toast.makeText(this, "Captured", Toast.LENGTH_SHORT).show()
        }*//*
        // Process the image here
    }*/

}