/*
 * Copyright 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ar.core.codelabs.hellogeospatial

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.media.Image
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.maps.model.LatLng
import com.google.ar.core.Anchor
import com.google.ar.core.Earth
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import com.google.ar.core.examples.java.common.helpers.DisplayRotationHelper
import com.google.ar.core.examples.java.common.helpers.TrackingStateHelper
import com.google.ar.core.examples.java.common.samplerender.Framebuffer
import com.google.ar.core.examples.java.common.samplerender.Mesh
import com.google.ar.core.examples.java.common.samplerender.SampleRender
import com.google.ar.core.examples.java.common.samplerender.Shader
import com.google.ar.core.examples.java.common.samplerender.Texture
import com.google.ar.core.examples.java.common.samplerender.arcore.BackgroundRenderer
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.DeadlineExceededException
import com.google.ar.core.exceptions.NotYetAvailableException
import org.w3c.dom.Text
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.IntBuffer
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

var AnchorsDatabaseList: MutableList<com.google.ar.core.codelabs.hellogeospatial.data.Anchor>? = mutableListOf()
var doAction = 0
const val eps = 0.001

private var isSavingImage = false // Флаг, показывающий, выполняется ли сохранение
private var isModelChanging = false

var pngFile = arrayOf("models/spatial_marker_baked.png", "models/point.png", "models/smile.png")
var objFile = arrayOf("models/geospatial_marker.obj", "models/point.obj", "models/smile.obj")

private var countModels = 3
private var modelNumber = 0

class HelloGeoRenderer(val activity: HelloGeoActivity) :
  SampleRender.Renderer, DefaultLifecycleObserver {
  //<editor-fold desc="ARCore initialization" defaultstate="collapsed">
  companion object {
    val TAG = "HelloGeoRenderer"

    private val Z_NEAR = 0.1f
    private val Z_FAR = 1000f
  }

  private lateinit var backgroundRenderer: BackgroundRenderer
  private lateinit var virtualSceneFramebuffer: Framebuffer
  private var hasSetTextureNames = false

  // Virtual object (ARCore pawn)
  private lateinit var virtualObjectMesh: Array<Mesh?>
  private lateinit var virtualObjectShader: Array<Shader?>
  private lateinit var virtualObjectTexture: Array<Texture?>

  // Temporary matrix allocated here to reduce number of allocations for each frame.
  private val modelMatrix = FloatArray(16)
  private val viewMatrix = FloatArray(16)
  private val projectionMatrix = FloatArray(16)
  private val modelViewMatrix = FloatArray(16) // view x model

  private val modelViewProjectionMatrix = FloatArray(16) // projection x view x model

  private val session
    get() = activity.arCoreSessionHelper.session

  private val displayRotationHelper = DisplayRotationHelper(activity)
  private val trackingStateHelper = TrackingStateHelper(activity)

  override fun onResume(owner: LifecycleOwner) {
    displayRotationHelper.onResume()
    hasSetTextureNames = false
  }

  override fun onPause(owner: LifecycleOwner) {
    displayRotationHelper.onPause()
  }

  override fun onSurfaceCreated(render: SampleRender) {
    // Prepare the rendering objects.
    // This involves reading shaders and 3D model files, so may throw an IOException.
    try {
      backgroundRenderer = BackgroundRenderer(render)
      virtualSceneFramebuffer = Framebuffer(render, /*width=*/ 1, /*height=*/ 1)

      virtualObjectTexture = arrayOfNulls(3)
      virtualObjectMesh = arrayOfNulls(3)
      virtualObjectShader = arrayOfNulls(3)

      for(i in 0 until countModels){
        // Virtual object to render (Geospatial Marker)
        virtualObjectTexture[i] =
          Texture.createFromAsset(
            render,
            pngFile[i],
            Texture.WrapMode.CLAMP_TO_EDGE,
            Texture.ColorFormat.SRGB
          )

        virtualObjectMesh[i] = Mesh.createFromAsset(render, objFile[i]);
        virtualObjectShader[i] =
          Shader.createFromAssets(
            render,
            "shaders/ar_unlit_object.vert",
            "shaders/ar_unlit_object.frag",
            /*defines=*/ null)
            .setTexture("u_Texture", virtualObjectTexture[i])
      }

      backgroundRenderer.setUseDepthVisualization(render, false)
      backgroundRenderer.setUseOcclusion(render, false)
    } catch (e: IOException) {
      Log.e(TAG, "Failed to read a required asset file", e)
      showError("Failed to read a required asset file: $e")
    }
  }

  override fun onSurfaceChanged(render: SampleRender, width: Int, height: Int) {
    displayRotationHelper.onSurfaceChanged(width, height)
    virtualSceneFramebuffer.resize(width, height)
  }
  //</editor-fold>

  override fun onDrawFrame(render: SampleRender) {

    if (isSavingImage || isModelChanging) return

    val session = session ?: return

    // Main Window
    val buttonSelf = activity.view.root.findViewById<Button>(R.id.buttonSelf)
    val buttonAction = activity.view.root.findViewById<Button>(R.id.buttonAction)
    val saveButton = activity.view.root.findViewById<Button>(R.id.saveButton)
    val helpTextView = activity.view.root.findViewById<TextView>(R.id.helpTextView)

    // Settings
    val model1Button: Button = activity.view.root.findViewById<Button>(R.id.buttonModel1)
    val model2Button: Button = activity.view.root.findViewById<Button>(R.id.buttonModel2)

    model1Button.setOnClickListener{
      isModelChanging = true
      modelNumber = 0
      model1Button.setBackgroundColor(Color.RED)
      model2Button.setBackgroundColor(Color.GRAY)
      activity.DoSettings()
      activity.DoMainWindow()
      isModelChanging = false
    }

    model2Button.setOnClickListener{
      isModelChanging = true
      modelNumber = 1
      model1Button.setBackgroundColor(Color.GRAY)
      model2Button.setBackgroundColor(Color.RED)
      activity.DoSettings()
      activity.DoMainWindow()
      isModelChanging = false
    }

    //<editor-fold desc="ARCore frame boilerplate" defaultstate="collapsed">
    // Texture names should only be set once on a GL thread unless they change. This is done during
    // onDrawFrame rather than onSurfaceCreated since the session is not guaranteed to have been
    // initialized during the execution of onSurfaceCreated.
    if (!hasSetTextureNames) {
      session.setCameraTextureNames(intArrayOf(backgroundRenderer.cameraColorTexture.textureId))
      hasSetTextureNames = true
    }

    // -- Update per-frame state

    // Notify ARCore session that the view size changed so that the perspective matrix and
    // the video background can be properly adjusted.
    displayRotationHelper.updateSessionIfNeeded(session)

    // Obtain the current frame from ARSession. When the configuration is set to
    // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
    // camera framerate.
    val frame =
      try {
        session.update()
      } catch (e: CameraNotAvailableException) {
        Log.e(TAG, "Camera not available during onDrawFrame", e)
        showError("Camera not available. Try restarting the app.")
        return
      }
    var image: Image? = null
    try {
      image = frame.acquireCameraImage()  // Можно сработать NotYetAvailableException

      saveButton.setOnClickListener{
        // Проверяем разрешение перед выполнением сохранения
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
          if (image != null) {
            saveSnapshot(image)
          } // Метод для захвата изображения
        } else {
          ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1001)
        }
      }

    } catch (e: NotYetAvailableException) {
      Log.e(TAG, "Image is not yet available: ${e.message}")
    } finally {
      // Если вы храните объект Image в переменной, которая требует освобождения, закройте её
      image?.close()
    }

    val camera = frame.camera

    // BackgroundRenderer.updateDisplayGeometry must be called every frame to update the coordinates
    // used to draw the background camera image.
    backgroundRenderer.updateDisplayGeometry(frame)

    // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
    trackingStateHelper.updateKeepScreenOnFlag(camera.trackingState)

    // -- Draw background
    if (frame.timestamp != 0L) {
      // Suppress rendering if the camera did not produce the first frame yet. This is to avoid
      // drawing possible leftover data from previous sessions if the texture is reused.
      backgroundRenderer.drawBackground(render)
    }

    // If not tracking, don't draw 3D objects.
    if (camera.trackingState == TrackingState.PAUSED) {
      return
    }

    // Get projection matrix.
    camera.getProjectionMatrix(projectionMatrix, 0, Z_NEAR, Z_FAR)

    // Get camera matrix and draw.
    camera.getViewMatrix(viewMatrix, 0)

    render.clear(virtualSceneFramebuffer, 0f, 0f, 0f, 0f)
    //</editor-fold>

    val earth = session.earth
    if (earth?.trackingState == TrackingState.TRACKING) {
      val cameraGeospatialPose = earth.cameraGeospatialPose
      activity.view.mapView?.updateMapPosition(
        latitude = cameraGeospatialPose.latitude,
        longitude = cameraGeospatialPose.longitude,
        heading = cameraGeospatialPose.heading
      )
    }
    if (earth != null) {
      activity.view.updateStatusText(earth, earth.cameraGeospatialPose)
    }

    if(Anchors == null && AnchorsCoordinates == null ||
      Anchors?.size == 0 && AnchorsCoordinates?.size == 0){
      activity.initListAnchor()
      Anchors = AnchorFromDBtoRealAnchor()
      AnchorsCoordinates = GetAnchorsCoordinatesFromDB()
    }

    if(activity.view.mapView?.earthMarkers == null ||
      activity.view.mapView?.earthMarkers!!.size == 0 && AnchorsCoordinates != null) {
      try{
        InitMarkers()
      }
      catch (e: Exception){
        Log.d("MyMarkersException", "Error after attempt to draw markers")
      }
    }

    buttonAction.setOnClickListener{
      doAction = if(doAction == 0)
        1
      else
        0
    }

    buttonSelf.setOnClickListener {

      if((activity.view.mapView?.earthMarkers == null ||
                activity.view.mapView?.earthMarkers!!.size == 0) && AnchorsCoordinates != null) {
        try {
          InitMarkers()
        }
        catch (e: Exception){
          Log.d("MyMarkersException", "Error after attempt to draw markers")
        }
      }

      if (Anchors != null && Anchors!!.size >= 3) {
        Anchors!!.removeAt(0)
        AnchorsCoordinates?.removeAt(0)
        activity.deleteFirstAnchor()
        activity.view.mapView?.earthMarkers?.first()?.apply {
          isVisible = false
        }
        activity.view.mapView?.earthMarkers?.removeAt(0)
      }

      //для рендеринга
      if (earth != null) {
        Anchors?.add(
          earth.createAnchor(
            earth.cameraGeospatialPose.latitude,
            earth.cameraGeospatialPose.longitude,
            earth.cameraGeospatialPose.altitude - 1.3, 0f, 0f, 0f, 1f
          )
        )
      }

      if (earth != null) {
        AnchorsCoordinates?.add(
          Pair(
            earth.cameraGeospatialPose.latitude,
            earth.cameraGeospatialPose.longitude
          )
        )
      }

      //для бд
      val newAnchor = earth?.cameraGeospatialPose?.let { it1 ->
        com.google.ar.core.codelabs.hellogeospatial.data.Anchor(
          latitude = earth?.cameraGeospatialPose!!.latitude,
          longitude = it1.longitude
        )
      }
      if (newAnchor != null) {
        activity.insertAnchor(newAnchor)
      }

      activity.view.mapView?.addMarker(Color.argb(255, 125, 125, 125))

      activity.view.mapView?.earthMarkers?.last()?.apply {
        if (earth != null) {
          position = LatLng(earth.cameraGeospatialPose.latitude, earth.cameraGeospatialPose.longitude)
        }
        isVisible = true
      }
    }

    val minDistance = GetMinDistance(earth)

    // Draw the placed anchor, if it exists.
    Anchors?.let {
      for(anchor in it){
        if (earth != null) {
          val ind = Anchors!!.indexOf(anchor)
          userAnchorDistance = AnchorsCoordinates?.get(ind)?.let { it1 ->
            haversineDistance(
              it1.first, it1.second,
              earth.cameraGeospatialPose.latitude, earth.cameraGeospatialPose.longitude)
          }
        }
        //if(userAnchorDistance!! <= 10){
          if(abs(userAnchorDistance!! - minDistance) < eps){
            activity.runOnUiThread {
              if(isAnchorVisible(anchor, earth) && activity.view.buttonSelf.visibility == View.VISIBLE){
                buttonAction.visibility = View.VISIBLE
                helpTextView.visibility = View.INVISIBLE
              }
              else if(activity.view.buttonSelf.visibility == View.VISIBLE){
                buttonAction.visibility = View.INVISIBLE
                helpTextView.visibility = View.VISIBLE
              }
            }
            var oldModelNumber = modelNumber
            if(userAnchorDistance!! <= 1)
              modelNumber = 2
            render.renderCompassAtAnchor(anchor, doAction)
            modelNumber = oldModelNumber
          }
          else
            render.renderCompassAtAnchor(anchor)
        //}
      }
    }

    // Compose the virtual scene with the background.
    backgroundRenderer.drawVirtualScene(render, virtualSceneFramebuffer, Z_NEAR, Z_FAR)
  }

  // Метод для захвата и сохранения изображения
  @SuppressLint("SuspiciousIndentation")
  private fun saveSnapshot(image: Image) {
    isSavingImage = true // Устанавливаем флаг сохранения

    //Thread {

      try {
        val bitmap = imageToBitmap(image) // Конвертируем в Bitmap
        if (bitmap != null) {
          saveBitmapToFile(bitmap)
        } // Сохраняем Bitmap в файл
        image.close() // Закрываем Image после использования
      } catch (e: Exception) {
        Log.e(TAG, "Error while saving snapshot: ${e.message}")
      } finally {
        isSavingImage = false // Сбрасываем флаг после завершения
      }
    //}.start() // Задержка в 1 секунду перед попыткой захвата изображения
  }

  private fun imageToBitmap(image: Image): Bitmap? {
    val planes: Array<Image.Plane> = image.planes
    val buffer: ByteBuffer = planes[0].buffer // Получаем информацию из плоскости Y
    val pixelStride: Int = planes[0].pixelStride
    val rowStride: Int = planes[0].rowStride
    val width: Int = image.width
    val height: Int = image.height

    // Создаем массив байтов для хранения данных типа YUV
    val ySize = buffer.remaining()
    val yBuffer = ByteArray(ySize)
    buffer.get(yBuffer) // Получаем данные

    // Создаем пустой Bitmap
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    // Переводим данные YUV в Bitmap
    for (y in 0 until height) {
      for (x in 0 until width) {
        val yIndex = y * rowStride + x * pixelStride
        val yValue = yBuffer[yIndex].toInt() and 0xFF // Упрощенный цвет
        bitmap.setPixel(x, y, (0xff shl 24) or (yValue shl 16) or (yValue shl 8) or yValue)
      }
    }
    return bitmap
  }

  private fun saveBitmapToFile(bitmap: Bitmap) {
    val storageDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
    val file = File(storageDir, "my_ar_image_${System.currentTimeMillis()}.png")

    try {
      FileOutputStream(file).use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) // Сохраните изображение в формате PNG
      }
      Toast.makeText(activity, "Image saved: ${file.absolutePath}", Toast.LENGTH_SHORT).show()
    } catch (e: IOException) {
      e.printStackTrace()
      Toast.makeText(activity, "Failed to save image", Toast.LENGTH_SHORT).show()
    }
  }

  private var Anchors: MutableList<Anchor>? = mutableListOf()
  private var AnchorsCoordinates: MutableList<Pair<Double, Double>>? = mutableListOf()
  private var userAnchorDistance: Double? = null

  private fun InitMarkers(){
    for(anchor in AnchorsCoordinates!!){
      activity.view.mapView?.addMarker(Color.argb(255, 125, 125, 125))
      activity.view.mapView?.earthMarkers?.last()?.apply {
        position = LatLng(anchor.first, anchor.second)
        isVisible = true
      }
    }
  }

  private fun AnchorFromDBtoRealAnchor(): MutableList<Anchor>? {
    var listAnchors: MutableList<Anchor> = mutableListOf()
    val earth = session?.earth
    val altitude = (earth?.cameraGeospatialPose?.altitude ?: 0.0) - 1.3
    if(AnchorsDatabaseList != null){
      for(anchor in AnchorsDatabaseList!!){
        if (earth != null) {
          listAnchors.add(earth.createAnchor(anchor.latitude, anchor.longitude,
            altitude, 0f, 0f, 0f, 1f))
        }
      }
    }
    return listAnchors
  }

  private fun GetAnchorsCoordinatesFromDB(): MutableList<Pair<Double, Double>>? {
    var listAnhorsCoordinates: MutableList<Pair<Double, Double>> = mutableListOf()
    val earth = session?.earth
    if(AnchorsDatabaseList != null){
      for(anchor in AnchorsDatabaseList!!){
        if (earth != null) {
          listAnhorsCoordinates.add(Pair(anchor.latitude, anchor.longitude))
        }
      }
    }
    return listAnhorsCoordinates
  }

  private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val R = 6371000 // радиус Земли в метрах
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)

    val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)

    val c = 2 * atan2(sqrt(a), sqrt(1 - a))

    return R * c // расстояние в метрах
  }

  private fun GetMinDistance(earth: Earth?): Double {
    var res = Double.MAX_VALUE
    Anchors?.let {
      for (anchor in it) {
        if (earth != null) {
          val ind = Anchors!!.indexOf(anchor)
          userAnchorDistance = AnchorsCoordinates?.get(ind)?.let { it1 ->
            haversineDistance(
              it1.first, it1.second,
              earth.cameraGeospatialPose.latitude, earth.cameraGeospatialPose.longitude
            )
          }
          if(userAnchorDistance!! < res)
            res = userAnchorDistance as Double
        }
      }
    }
    return res
  }

  private fun isAnchorVisible(anchor: Anchor, earth: Earth?): Boolean {
    // Получаем позу якоря
    val anchorPose = anchor.pose

    // Получаем кватернион якоря
    val anchorQuaternion = anchorPose.rotationQuaternion // [x, y, z, w]

    // Извлекаем компоненты кватерниона
    val (x, y, z, w) = anchorQuaternion

    // Вычисляем матрицу вращения из кватерниона
    val heading = atan2(2.0 * (y * w + x * z), (w * w + x * x - y * y - z * z).toDouble())

    // Переводим радианы в градусы
    val headingDegrees = Math.toDegrees(heading)

    // Получаем позу камеры
    val cameraPose = earth?.cameraGeospatialPose ?: return false

    // Получаем направление heading камеры (угол в градусах)
    val cameraHeading = cameraPose.heading // Угол ориентации камеры

    return abs((headingDegrees + 360) % 360 - cameraHeading) <= 75
  }



  fun onMapClick(latLng: LatLng) {
    val earth = session?.earth ?: return
    if (earth.trackingState != TrackingState.TRACKING) {
      return
    }

    if((activity.view.mapView?.earthMarkers == null ||
      activity.view.mapView?.earthMarkers!!.size == 0) && AnchorsCoordinates != null) {
      for (anchor in AnchorsCoordinates!!) {
        activity.view.mapView?.addMarker(Color.argb(255, 125, 125, 125))
        activity.view.mapView?.earthMarkers?.last()?.apply {
          position = LatLng(anchor.first, anchor.second)
          isVisible = true
        }
      }
    }

    if (Anchors != null && Anchors!!.size >= 3) {
      Anchors!!.removeAt(0)
      AnchorsCoordinates?.removeAt(0)
      activity.deleteFirstAnchor()
      activity.view.mapView?.earthMarkers?.first()?.apply {
        isVisible = false
      }
      activity.view.mapView?.earthMarkers?.removeAt(0)
    }
    // Place the earth anchor at the same altitude as that of the camera to make it easier to view.
    val altitude = earth.cameraGeospatialPose.altitude - 1.3
    // The rotation quaternion of the anchor in the East-Up-South (EUS) coordinate system.
    val qx = 0f
    val qy = 0f
    val qz = 0f
    val qw = 1f
    Anchors?.add(
      earth.createAnchor(latLng.latitude, latLng.longitude, altitude, qx, qy, qz, qw))

    AnchorsCoordinates?.add(
      Pair(
        latLng.latitude,
        latLng.longitude
      )
    )

    //для бд
    val newAnchor = com.google.ar.core.codelabs.hellogeospatial.data.Anchor(
      latitude = latLng.latitude,
      longitude = latLng.longitude
    )
    activity.insertAnchor(newAnchor)

    activity.view.mapView?.addMarker(Color.argb(255, 125, 125, 125))

    activity.view.mapView?.earthMarkers?.last()?.apply {
      position = latLng
      isVisible = true
    }
  }

  private fun SampleRender.renderCompassAtAnchor(anchor: Anchor, action: Int = 0) {
    // Get the current pose of the Anchor in world space. The Anchor pose is updated
    // during calls to session.update() as ARCore refines its estimate of the world.
    anchor.pose.toMatrix(modelMatrix, 0)

    if(modelNumber == 1 || modelNumber == 2){
      // Создайте матрицу масштабирования
      val scaleFactor = if(modelNumber == 1) 0.005f else 0.02f
      val scaleMatrix = FloatArray(16)
      Matrix.setIdentityM(scaleMatrix, 0) // Сначала установите единичную матрицу

      // Устанавливаем значения масштабирования
      scaleMatrix[0] = scaleFactor // Сcaling X
      scaleMatrix[5] = scaleFactor // Scaling Y
      scaleMatrix[10] = scaleFactor // Scaling Z

      // Умножьте модельную матрицу на матрицу масштабирования
      Matrix.multiplyMM(modelMatrix, 0, modelMatrix, 0, scaleMatrix, 0)
    }

    // Calculate model/view/projection matrices
    Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
    Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)

    // Update shader properties and draw
    virtualObjectShader[modelNumber]?.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)

    // Передаем цвет в шейдер
    virtualObjectShader[modelNumber]?.setInt("action", action)

    draw(virtualObjectMesh[modelNumber], virtualObjectShader[modelNumber], virtualSceneFramebuffer)
  }

  private fun showError(errorMessage: String) =
    activity.view.snackbarHelper.showError(activity, errorMessage)
}
