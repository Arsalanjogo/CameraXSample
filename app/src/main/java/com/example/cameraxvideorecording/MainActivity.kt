package com.example.cameraxvideorecording

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.cameraxvideorecording.databinding.ActivityMainBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions
import java.io.File
import java.lang.Exception
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@SuppressLint("RestrictedApi")
@androidx.camera.core.ExperimentalGetImage
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var imageCapture: ImageCapture? = null

    //For video capture
    private lateinit var videoCapture: VideoCapture


    private lateinit var outputDirectory: File
    private lateinit var cameraExecutor: ExecutorService

    private lateinit var poseDetector: PoseDetector

    private val TAG = "MainActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)

        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this,
                Constants.requiredPermission,
                Constants.REQUEST_CODE_PERMISSION
            )
        }


        outputDirectory = getOutputDirectory()

        binding.startRecordBtn.setOnClickListener { startRecording() }

        binding.stopRecordBtn.setOnClickListener { stopRecording() }

        setupPoseDetector()

    }

    private fun setupPoseDetector() {
        // Pose Detect
        val options = AccuratePoseDetectorOptions.Builder()
            .setDetectorMode(AccuratePoseDetectorOptions.STREAM_MODE)
            .build()

        poseDetector = PoseDetection.getClient(options)
    }


    private fun getOutputDirectory(): File {
        val mediaDir = externalMediaDirs.firstOrNull()?.let {
            File(it, resources.getString(R.string.app_name)).apply { mkdirs() }
        }
        return if (mediaDir != null && mediaDir.exists())
            mediaDir else filesDir
    }


    private fun startRecording() {
//        binding.recordBtn.text="Recording"


        // Create time-stamped output file to hold the image
        val file = File(
            outputDirectory,
            "${System.currentTimeMillis()}.mp4"
        )

        val outputFileOptions = VideoCapture.OutputFileOptions.Builder(file).build()


        videoCapture.startRecording(
            outputFileOptions,
            ContextCompat.getMainExecutor(this),
            object : VideoCapture.OnVideoSavedCallback {
                override fun onVideoSaved(outputFileResults: VideoCapture.OutputFileResults) {
                    Toast.makeText(
                        applicationContext,
                        "Video saved${outputFileResults.savedUri}",
                        Toast.LENGTH_SHORT
                    ).show()

                    Log.d(TAG, "Video location ${outputFileResults.savedUri}")
                }

                override fun onError(videoCaptureError: Int, message: String, cause: Throwable?) {
                    Toast.makeText(applicationContext, "Error while recodrding", Toast.LENGTH_SHORT)
                        .show()
                }
            })
    }


    private fun stopRecording() {
        videoCapture.stopRecording()
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        // Create time-stamped output file to hold the image
        val photoFile = File(
            outputDirectory,
            SimpleDateFormat(
                Constants.FILE_NAME, Locale.US
            ).format(System.currentTimeMillis()) + ".jpg"
        )

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        // Set up image capture listener, which is triggered after photo has
        // been taken

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val savedUri = Uri.fromFile(photoFile)
                    val msg = "Photo capture succeeded: $savedUri"
                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                    Log.d(TAG, msg)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exception.message}", exception)
                }
            }
        )

    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray
    ) {

        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == Constants.REQUEST_CODE_PERMISSION) {
            if (allPermissionGranted()) {
                startCamera()
            } else {
                Toast.makeText(
                    this,
                    "Permissions not granted by the user.Reuqest them again",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    }

    private fun startCamera() {

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener(Runnable {
            val cameraProvider = cameraProviderFuture.get()

            //Surface provider builder
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

            //camera selector
//            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            val cameraSelector =
                CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()


            //Video recording configuration
            videoCapture = VideoCapture.Builder()
                .setCameraSelector(cameraSelector)
//                .setTargetRotation(binding.previewView.display.rotation)
                .build()

//            videoCapture = VideoCapture(videoCaptureConfig)

            //Pose detection configuration


            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(
                ContextCompat.getMainExecutor(this),
                ImageAnalysis.Analyzer { imageProxy ->

                    val mediaImage = imageProxy.image

                    if (mediaImage != null) {
                        val image = InputImage.fromMediaImage(
                            mediaImage,
                            imageProxy.imageInfo.rotationDegrees
                        )

                        // Pass image to an ML Kit Vision API
                        poseDetector.process(image)
                            .addOnSuccessListener { pose -> drawPose(pose) }
                            .addOnFailureListener {
                                Toast.makeText(
                                    this,
                                    "Pose detection failed on the current image",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                    }


                })




            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, videoCapture, imageAnalysis
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }


        }, ContextCompat.getMainExecutor(this))


    }

    private fun drawPose(pose: Pose) {

        if (binding.previewView.childCount>1){
            binding.previewView.removeAllViews()
        }

        //Detect all landmarks from the image
        if (pose.allPoseLandmarks.isNotEmpty()) {


            if (binding.previewView.childCount>1){
                binding.previewView.removeAllViews()
            }


            //if landmarks are not empty draw them

            val poseCanvasCustomView = Draw(applicationContext, pose)

            binding.previewView.addView(poseCanvasCustomView)
        }


    }

    private fun drawPosPoints(
        lShoulderX: Float,
        lShoulderY: Float,
        rShoulderX: Float,
        rShoulderY: Float
    ) {


    }


    private fun allPermissionGranted() =
        Constants.requiredPermission.all {
            ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
        }


    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }


//    internal class PoseAnalyzer:ImageAnalysis.Analyzer{
//
//        override fun analyze(imageProxy: ImageProxy) {
//            val mediaImage = imageProxy.image
//
//            if (mediaImage!=null){
//                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
//
//                // Pass image to an ML Kit Vision API
//
//
//            }
//
//        }
//
//    }
}