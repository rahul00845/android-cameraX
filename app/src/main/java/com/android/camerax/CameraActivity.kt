package com.android.camerax

import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.core.impl.VideoCaptureConfig
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class CameraActivity : BaseActivity() {

    private val CAMERA_STORAGE_PERMISION_REQ_CODE = 101
    private val permissionArray = arrayOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            android.Manifest.permission.RECORD_AUDIO
    )

    private lateinit var outputDirectory: File

    private var displayId: Int = -1
    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: androidx.camera.core.Camera? = null
    private var isVideoCameraActive = false
    private var isVideoRecording = false


    private val displayManager by lazy {
        getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

    /** Blocking camera operations are performed using this executor */
    private lateinit var cameraExecutor: ExecutorService


    /**
     * We need a display listener for orientation changes that do not trigger a configuration
     * change, for example if we choose to override config change in manifest or for 180-degree
     * orientation changes.
     */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        requestAppPermissions(
                permissionArray, CAMERA_STORAGE_PERMISION_REQ_CODE, this::onPermissionResult
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    override fun onPause() {
        super.onPause()
        if(isVideoRecording) {
            videoCapture?.stopRecording()
            isVideoRecording = false
        }
    }

    override fun onResume() {
        super.onResume()
        showUi()
        if(isVideoCameraActive) {
            videoSwitch.setImageResource(R.drawable.ic_camera_outline)
        } else {
            videoSwitch.setImageResource(R.drawable.ic_video_outline)
        }
    }

    private fun onPermissionResult(
            requestCode: Int,
            permissionStatus: Boolean
    ) {
        if (permissionStatus) {
            Log.d("PermissionStatus", "true")
            cameraExecutor = Executors.newSingleThreadExecutor()
            outputDirectory = getOutputDirectory(this)

            // Wait for the views to be properly laid out
            viewFinder.post {
                // Keep track of the display in which this view is attached
                displayId = viewFinder.display.displayId

                // Build UI controls
                updateCameraUi()

                // Bind use cases
                bindCameraUseCases()
            }
        } else {
            requestAppPermissions(
                    permissionArray, CAMERA_STORAGE_PERMISION_REQ_CODE, this::onPermissionResult
            )
            Log.d("PermissionStatus", "false")
        }
    }

    /**
     * Inflate camera controls and update the UI manually upon config changes to avoid removing
     * and re-adding the view finder from the view hierarchy; this provides a seamless rotation
     * transition on devices that support it.
     *
     * NOTE: The flag is supported starting in Android 8 but there still is a small flash on the
     * screen for devices that run Android 9 or below.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateCameraUi()
    }

    /** Declare and bind preview, capture and analysis use cases */
    private fun bindCameraUseCases() {

        // Get screen metrics used to setup camera for full screen resolution
        val metrics = DisplayMetrics().also { viewFinder.display.getRealMetrics(it) }
        Log.d(TAG, "Screen metrics: ${metrics.widthPixels} x ${metrics.heightPixels}")

        val screenAspectRatio = aspectRatio(metrics.widthPixels, metrics.heightPixels)
        Log.d(TAG, "Preview aspect ratio: $screenAspectRatio")

        val rotation = viewFinder.display.rotation

        // Bind the CameraProvider to the LifeCycleOwner
        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener(Runnable {

            // CameraProvider
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            preview = Preview.Builder()
                    // We request aspect ratio but no resolution
                    .setTargetAspectRatio(screenAspectRatio)
                    // Set initial target rotation
                    .setTargetRotation(rotation)
                    .build()

            // Attach the viewfinder's surface provider to preview use case
            preview?.setSurfaceProvider(viewFinder.previewSurfaceProvider)

            // ImageCapture
            imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    // We request aspect ratio but no resolution to match preview config, but letting
                    // CameraX optimize for whatever specific resolution best fits our use cases
                    .setTargetAspectRatio(screenAspectRatio)
                    // Set initial target rotation, we will have to call this again if rotation changes
                    // during the lifecycle of this use case
                    .setTargetRotation(rotation)
                    .build()

            // Create a configuration object for the video use case
            val videoCapture = VideoCaptureConfig.Builder().apply {
                setTargetRotation(viewFinder.display.rotation)
            }.build()

            this.videoCapture = videoCapture


            // Must unbind the use-cases before rebinding them
            cameraProvider.unbindAll()

            try {
                // A variable number of use-cases can be passed here -
                // camera provides access to CameraControl & CameraInfo
                camera = cameraProvider.bindToLifecycle(
                        this, cameraSelector, preview, imageCapture, videoCapture)
            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    /**
     *  [androidx.camera.core.ImageAnalysisConfig] requires enum value of
     *  [androidx.camera.core.AspectRatio]. Currently it has values of 4:3 & 16:9.
     *
     *  Detecting the most suitable ratio for dimensions provided in @params by counting absolute
     *  of preview ratio to one of the provided values.
     *
     *  @param width - preview width
     *  @param height - preview height
     *  @return suitable aspect ratio
     */
    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = max(width, height).toDouble() / min(width, height)
        if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }


    private fun imageCapture() {

        // Get a stable reference of the modifiable image capture use case
        imageCapture?.let { imageCapture ->

            // Create output file to hold the image
            val photoFile = createFile(outputDirectory, FILENAME, PHOTO_EXTENSION)

            // Setup image capture metadata
            val metadata = ImageCapture.Metadata().apply {

                // Mirror image when using the front camera
                isReversedHorizontal = lensFacing == CameraSelector.LENS_FACING_FRONT
            }

            // Create output options object which contains file + metadata
            val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile)
                    .setMetadata(metadata)
                    .build()

            // Setup image capture listener which is triggered after photo has been taken
            imageCapture.takePicture(
                    outputOptions, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri = output.savedUri ?: Uri.fromFile(photoFile)
                    Log.d(TAG, "Photo capture succeeded: $savedUri")
                    runOnUiThread {
                        Toast.makeText(this@CameraActivity, "Image saved to $savedUri", Toast.LENGTH_SHORT).show()
                    }
                }
            })
        }
    }

    private fun videoCapture() {
        val photoFile = createFile(outputDirectory, FILENAME, VIDEO_EXTENSION)
        videoCapture?.run {
            startRecording(photoFile, cameraExecutor, object : VideoCapture.OnVideoSavedCallback {
                override fun onVideoSaved(file: File) {
                    Log.d("VideoSaved", file.absolutePath)
                    runOnUiThread {
                        Toast.makeText(this@CameraActivity, "Video saved to ${file.absolutePath}", Toast.LENGTH_SHORT).show()

                    }
                }

                override fun onError(videoCaptureError: Int, message: String, cause: Throwable?) {
                    Log.d("Video", message + " " + cause?.message)
                }
            })
        }
    }

    private fun hideUi() {
        videoSwitch.visibility = View.GONE
        cameraSwitch.visibility = View.GONE
    }

    private fun showUi() {
        videoSwitch.visibility = View.VISIBLE
        cameraSwitch.visibility = View.VISIBLE
    }

    /** Method used to re-draw the camera UI controls, called every time configuration changes. */
    private fun updateCameraUi() {
        start.setOnClickListener {
            if(isVideoCameraActive) {
                Log.d("CameraStatus","video_recording")
                isVideoRecording = !isVideoRecording
                if(isVideoRecording) {
                    start.setImageResource(R.drawable.ic_stop_circle)
                    videoCapture()
                    hideUi()
                } else {
                    start.setImageResource(R.drawable.ic_circle)
                    videoCapture?.stopRecording()
                    showUi()
                }
            } else {
                Log.d("CameraStatus","image_capture")
                imageCapture()
            }
        }

        videoSwitch.setOnClickListener {
            isVideoCameraActive = !isVideoCameraActive
            if(isVideoCameraActive) {
                videoSwitch.setImageResource(R.drawable.ic_camera_outline)
            } else {
                videoSwitch.setImageResource(R.drawable.ic_video_outline)
            }
        }


        // Listener for button used to switch cameras
        cameraSwitch.setOnClickListener {
            lensFacing = if (CameraSelector.LENS_FACING_FRONT == lensFacing) {
                CameraSelector.LENS_FACING_BACK
            } else {
                CameraSelector.LENS_FACING_FRONT
            }
            // Re-bind use cases to update selected camera
            bindCameraUseCases()
        }
    }

    companion object {

        private const val TAG = "CameraXBasic"
        private const val FILENAME = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val PHOTO_EXTENSION = ".jpg"
        private const val VIDEO_EXTENSION = ".mp4"
        private const val RATIO_4_3_VALUE = 4.0 / 3.0
        private const val RATIO_16_9_VALUE = 16.0 / 9.0

        /** Helper function used to create a timestamped file */
        private fun createFile(baseFolder: File, format: String, extension: String) =
                File(baseFolder, SimpleDateFormat(format, Locale.US)
                        .format(System.currentTimeMillis()) + extension)

        /** Use external media if it is available, our app's file directory otherwise */
        fun getOutputDirectory(context: Context): File {
            val appContext = context.applicationContext
            val mediaDir = context.externalMediaDirs.firstOrNull()?.let {
                File(it, appContext.resources.getString(R.string.app_name)).apply { mkdirs() } }
            return if (mediaDir != null && mediaDir.exists())
                mediaDir else appContext.filesDir
        }
    }


}
