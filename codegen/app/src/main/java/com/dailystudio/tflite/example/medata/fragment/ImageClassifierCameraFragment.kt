package com.dailystudio.tflite.example.medata.fragment

import android.content.Context
import android.graphics.Bitmap
import com.dailystudio.devbricksx.development.Logger
import com.dailystudio.devbricksx.utils.ImageUtils
import com.dailystudio.devbricksx.utils.MatrixUtils
import com.dailystudio.tensorflow.lite.viewer.image.AbsTFLiteCameraFragment
import com.dailystudio.tensorflow.lite.viewer.image.AbsTFLiteImageAnalyzer
import com.dailystudio.tensorflow.lite.viewer.image.ImageInferenceInfo
import com.dailystudio.tensorflow.lite.viewer.ui.InferenceSettingsPrefs
import com.dailystudio.tflite.example.medata.LiteModelFoodV1
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.label.Category
import org.tensorflow.lite.support.model.Model
import java.lang.Exception


class ImageClassifierAnalyzer(rotation: Int, lensFacing: Int)
    : AbsTFLiteImageAnalyzer<ImageInferenceInfo, List<Category>>(rotation, lensFacing) {

    private var classifier: LiteModelFoodV1? = null
    private var lock = Object()

    private fun createModel(context: Context): LiteModelFoodV1 {
        val deviceStr = InferenceSettingsPrefs.instance.device
        val numOfThreads = InferenceSettingsPrefs.instance.numberOfThreads

        val device = try {
            Model.Device.valueOf(deviceStr)
        } catch (e: Exception) {
            Logger.error("failed to parse device from [${deviceStr}]: $e")
            Model.Device.CPU
        }

        return when (device) {
            Model.Device.CPU, Model.Device.NNAPI -> {
                LiteModelFoodV1(context, device, numOfThreads)
            }
            Model.Device.GPU -> {
                LiteModelFoodV1(context, device, 1)
            }
        }
    }

    override fun analyzeFrame(context: Context,
                              inferenceBitmap: Bitmap,
                              info: ImageInferenceInfo): List<Category> {
        val tImage = TensorImage.fromBitmap(inferenceBitmap)

        val categories: MutableList<Category> =
            mutableListOf()

        synchronized(lock) {
            classifier = classifier ?: createModel(context)

            classifier?.let {
                val inputs: LiteModelFoodV1.Inputs  = it.createInputs()
                inputs.loadImage(tImage)

                val start = System.currentTimeMillis()

                val outputs = classifier?.run(inputs)?.probability
                outputs?.let { map ->
                    categories.addAll(map.toList().map { pair ->
                        Category.create(pair.first, pair.first, pair.second)
                    }.sortedByDescending { category ->
                        category.score
                    })
                }

                val end = System.currentTimeMillis()

                info.inferenceTime = (end - start)
            }
        }

        return categories
    }

    override fun createInferenceInfo(): ImageInferenceInfo {
        return ImageInferenceInfo()
    }

    override fun preProcessImage(
        frameBitmap: Bitmap?,
        info: ImageInferenceInfo
    ): Bitmap? {
        if (frameBitmap == null) {
            return frameBitmap
        }

        val matrix = MatrixUtils.getTransformationMatrix(
            frameBitmap.width,
            frameBitmap.height,
            640, 480,
            info.imageRotation,
            true
        )

        return ImageUtils.createTransformedBitmap(
            frameBitmap,
            matrix
        )
    }

    override fun onInferenceSettingsChange(changePrefName: String) {
        super.onInferenceSettingsChange(changePrefName)

        when (changePrefName) {
            InferenceSettingsPrefs.PREF_DEVICE, InferenceSettingsPrefs.PREF_NUMBER_OF_THREADS -> {
                GlobalScope.launch (Dispatchers.IO) {
                    synchronized(lock) {
                        classifier?.close()

                        classifier = null
                    }
                }
            }
        }
    }

}

class ImageClassifierCameraFragment
    : AbsTFLiteCameraFragment<ImageInferenceInfo, List<Category>>() {

    override fun createAnalyzer(screenAspectRatio: Int, rotation: Int, lensFacing: Int): AbsTFLiteImageAnalyzer<ImageInferenceInfo, List<Category>> {
        return ImageClassifierAnalyzer(rotation, lensFacing)
    }

}