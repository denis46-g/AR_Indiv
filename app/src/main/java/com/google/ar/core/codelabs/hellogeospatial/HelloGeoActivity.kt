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

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.codelabs.hellogeospatial.data.Anchor
import com.google.ar.core.codelabs.hellogeospatial.data.AnchorDatabase
import com.google.ar.core.codelabs.hellogeospatial.data.AnchorsRepository
import com.google.ar.core.codelabs.hellogeospatial.data.OfflineAnchorsRepository
import com.google.ar.core.codelabs.hellogeospatial.helpers.ARCoreSessionLifecycleHelper
import com.google.ar.core.codelabs.hellogeospatial.helpers.GeoPermissionsHelper
import com.google.ar.core.codelabs.hellogeospatial.helpers.HelloGeoView
import com.google.ar.core.examples.java.common.helpers.FullScreenHelper
import com.google.ar.core.examples.java.common.samplerender.SampleRender
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

var firstPush = true

class HelloGeoActivity : AppCompatActivity() {
  companion object {
    private const val TAG = "HelloGeoActivity"
  }

  lateinit var arCoreSessionHelper: ARCoreSessionLifecycleHelper
  lateinit var view: HelloGeoView
  lateinit var renderer: HelloGeoRenderer

  // Database and repository
  val anchorsRepository: AnchorsRepository by lazy {
    OfflineAnchorsRepository(AnchorDatabase.getDatabase(applicationContext).anchorDao())
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Setup ARCore session lifecycle helper and configuration.
    arCoreSessionHelper = ARCoreSessionLifecycleHelper(this)

    // If Session creation or Session.resume() fails, display a message and log detailed
    // information.
    arCoreSessionHelper.exceptionCallback =
      { exception ->
        val message =
          when (exception) {
            is UnavailableUserDeclinedInstallationException ->
              "Please install Google Play Services for AR"
            is UnavailableApkTooOldException -> "Please update ARCore"
            is UnavailableSdkTooOldException -> "Please update this app"
            is UnavailableDeviceNotCompatibleException -> "This device does not support AR"
            is CameraNotAvailableException -> "Camera not available. Try restarting the app."
            else -> "Failed to create AR session: $exception"
          }
        Log.e(TAG, "ARCore threw an exception", exception)
        view.snackbarHelper.showError(this, message)
      }

    // Configure session features.
    arCoreSessionHelper.beforeSessionResume = ::configureSession
    lifecycle.addObserver(arCoreSessionHelper)

    // Set up the Hello AR renderer.
    renderer = HelloGeoRenderer(this)
    lifecycle.addObserver(renderer)

    // Set up Hello AR UI.
    view = HelloGeoView(this)
    lifecycle.addObserver(view)
    setContentView(view.root)

    // Sets up an example renderer using our HelloGeoRenderer.
    SampleRender(view.surfaceView, renderer, assets)

    // Main Window
    view.buttonSelf.visibility = View.INVISIBLE
    view.buttonAction.visibility = View.INVISIBLE
    view.helpTextView.visibility = View.INVISIBLE
    view.homeButton.visibility = View.INVISIBLE
    view.saveButton.visibility = View.INVISIBLE
    view.mapTouchWrapper.visibility = View.INVISIBLE

    view.homeButton.setOnClickListener{
      DoMainWindow()
      DoHomeMenu()
    }

    // Home Menu
    view.homeMenuTextView.visibility = View.INVISIBLE
    view.playArSessionButton.visibility = View.INVISIBLE
    view.settingsButton.visibility = View.INVISIBLE

    view.playArSessionButton.setOnClickListener{
      DoHomeMenu()
      DoMainWindow()
    }

    view.settingsButton.setOnClickListener{
      DoHomeMenu()
      DoSettings()
    }

    // Settings
    view.settingsTextView.visibility = View.INVISIBLE
    view.backButton.visibility = View.INVISIBLE
    view.model1Button.setBackgroundColor(Color.RED)
    view.model1Button.visibility = View.INVISIBLE
    view.model2Button.setBackgroundColor(Color.GRAY)
    view.model2Button.visibility = View.INVISIBLE

    view.backButton.setOnClickListener{
      DoSettings()
      DoHomeMenu()
    }
  }

  fun DoMainWindow(){
    if(view.buttonSelf.visibility == View.INVISIBLE) {
      view.buttonSelf.visibility = View.VISIBLE
      view.homeButton.visibility = View.VISIBLE
      view.saveButton.visibility = View.VISIBLE
      view.surfaceView.visibility = View.VISIBLE
      view.mapTouchWrapper.visibility = View.VISIBLE
    }
    else if(view.buttonSelf.visibility == View.VISIBLE) {
      view.buttonSelf.visibility = View.INVISIBLE
      view.homeButton.visibility = View.INVISIBLE
      view.saveButton.visibility = View.INVISIBLE
      //
      view.surfaceView.visibility = View.INVISIBLE
      view.mapTouchWrapper.visibility = View.INVISIBLE
      //
      view.buttonAction.visibility = View.INVISIBLE
      view.helpTextView.visibility = View.INVISIBLE
    }
  }

  fun DoHomeMenu(){

    view.surfaceView.visibility = View.GONE

    if(view.searchingAnchorsTextView.visibility == View.VISIBLE)
      view.searchingAnchorsTextView.visibility = View.INVISIBLE

    if(view.homeMenuTextView.visibility == View.INVISIBLE){
      view.homeMenuTextView.visibility = View.VISIBLE
      view.playArSessionButton.visibility = View.VISIBLE
      view.settingsButton.visibility = View.VISIBLE
    }
    else if(view.homeMenuTextView.visibility == View.VISIBLE){
      view.homeMenuTextView.visibility = View.INVISIBLE
      view.playArSessionButton.visibility = View.INVISIBLE
      view.settingsButton.visibility = View.INVISIBLE
    }

  }

  fun DoSettings(){
    if(view.settingsTextView.visibility == View.INVISIBLE){
      view.settingsTextView.visibility = View.VISIBLE
      view.backButton.visibility = View.VISIBLE
      view.model1Button.visibility = View.VISIBLE
      view.model2Button.visibility = View.VISIBLE
    }
    else if(view.settingsTextView.visibility == View.VISIBLE){
      view.settingsTextView.visibility = View.INVISIBLE
      view.backButton.visibility = View.INVISIBLE
      view.model1Button.visibility = View.INVISIBLE
      view.model2Button.visibility = View.INVISIBLE
    }
  }

  fun initListAnchor(){
    view.setStatusMessage("Поиск якорей...")
    lifecycleScope.launch {
      AnchorsDatabaseList = anchorsRepository.getAllAnchorsStream().first().toMutableList()

      kotlinx.coroutines.delay(3000)

      val message = if (AnchorsDatabaseList!!.isNotEmpty()) {
        "Якоря найдены в количестве ${AnchorsDatabaseList!!.size}"
      } else {
        "Якоря не найдены"
      }
      view.setStatusMessage(message)

      kotlinx.coroutines.delay(2000)

      if(firstPush){
        DoHomeMenu()
        firstPush = false
      }
    }
  }

  fun insertAnchor(anchor: Anchor){
    lifecycleScope.launch {
      anchorsRepository.insertAnchor(anchor)
    }
  }

  fun deleteFirstAnchor(){
    lifecycleScope.launch {
      for(anchor in anchorsRepository.getAllAnchorsStream().first()){
        anchorsRepository.deleteAnchor(anchor)
        break
      }
    }
  }

  // Configure the session, setting the desired options according to your usecase.
  fun configureSession(session: Session) {
    session.configure(
      session.config.apply {
        // Enable Geospatial Mode.
        geospatialMode = Config.GeospatialMode.ENABLED
      }
    )
  }

  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<String>,
    results: IntArray
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, results)
    if (!GeoPermissionsHelper.hasGeoPermissions(this)) {
      // Use toast instead of snackbar here since the activity will exit.
      Toast.makeText(this, "Camera and location permissions are needed to run this application", Toast.LENGTH_LONG)
        .show()
      if (!GeoPermissionsHelper.shouldShowRequestPermissionRationale(this)) {
        // Permission denied with checking "Do not ask again".
        GeoPermissionsHelper.launchPermissionSettings(this)
      }
      finish()
    }
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus)
  }
}
