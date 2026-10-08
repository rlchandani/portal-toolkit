/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import com.portalinstaller.data.companion.CompanionRepository
import org.json.JSONArray
import org.json.JSONObject

class ControlCenterViewModel(private val repository: CompanionRepository) : ViewModel() {
    constructor(context: Context) : this(CompanionRepository.create(context))
    val state = repository.state

    fun pair(endpoint: String, pin: String, name: String? = null) = repository.pair(endpoint, pin, name)
    fun pairFromCode(code: String, pin: String? = null) = repository.pairFromCode(code, pin)
    fun selectDevice(id: String) = repository.selectDevice(id)
    fun disconnect() = repository.disconnect()
    fun forgetDevice(id: String) = repository.forgetDevice(id)
    fun refresh() = repository.refresh()
    fun refreshStatus() = repository.refreshStatus()
    fun refreshSettings() = repository.refreshSettings()
    fun refreshSources() = repository.refreshSources()
    fun refreshOperations() = repository.refreshOperations()
    fun refreshLayout() = repository.refreshLayout()
    fun refreshCatalog() = repository.refreshCatalog()
    fun refreshScenes() = repository.refreshScenes()
    fun refreshJarvis() = repository.refreshJarvis()
    fun remoteAction(action: String) = repository.remoteAction(action)
    fun moveCursor(dx: Float, dy: Float) = repository.moveCursor(dx, dy)
    fun tap() = repository.tap()
    fun swipe(dx: Float, dy: Float) = repository.swipe(dx, dy)
    fun scroll(direction: String) = repository.scroll(direction)
    fun sendText(text: String, mode: String = "set") = repository.sendText(text, mode)
    fun media(action: String, positionMs: Long? = null) = repository.media(action, positionMs)
    fun volume(direction: String) = repository.volume(direction)
    fun launchApp(packageName: String) = repository.launchApp(packageName)
    fun writeSettings(domain: String, values: JSONObject) = repository.writeSettings(domain, values)
    fun writeSources(values: JSONObject) = repository.writeSources(values)
    fun previewScreensaver() = repository.previewScreensaver()
    fun updateLayout(layout: JSONObject, expectedRevision: String? = null) = repository.updateLayout(layout, expectedRevision)
    fun exportSnapshot() = repository.exportSnapshot()
    suspend fun exportSnapshotResult(): JSONObject? = repository.exportSnapshotResult()
    fun restoreSnapshot(snapshot: JSONObject, expectedRevision: String? = null) = repository.restoreSnapshot(snapshot, expectedRevision)
    fun startOperation(request: JSONObject) = repository.startOperation(request)
    fun cancelOperation(id: String) = repository.cancelOperation(id)
    fun retryOperation(id: String) = repository.retryOperation(id)
    fun runScene(id: String) = repository.runScene(id)
    fun saveScenes(scenes: JSONArray) = repository.saveScenes(scenes)
    fun updateJarvis(values: JSONObject) = repository.updateJarvis(values)
    fun fetchImmichAlbums(url: String, key: String) = repository.fetchImmichAlbums(url, key)
    fun savePresets(presets: JSONArray) = repository.savePresets(presets)
    fun runPreset(id: String) = repository.runPreset(id)
    fun refreshPresets() = repository.refreshPresets()
    fun listPhotos(folder: String = state.value.photoFolder) = repository.listPhotos(folder)
    fun uploadUris(context: Context, uris: List<Uri>, folder: String = state.value.photoFolder) =
        repository.uploadUris(context, uris, folder)
    fun deletePhoto(path: String) = repository.deletePhoto(path)
    fun loadPhotoThumbnail(path: String, onLoaded: (ByteArray) -> Unit) = repository.loadPhotoThumbnail(path, onLoaded)
    fun cancelPhotoUpload() = repository.cancelPhotoUpload()
    suspend fun photoBytes(path: String, thumbnail: Boolean = true): ByteArray? = repository.photoBytes(path, thumbnail)
    suspend fun appIconBytes(packageName: String): ByteArray? = repository.appIconBytes(packageName)
    fun startDiscovery() = repository.startDiscovery()
    fun stopDiscovery() = repository.stopDiscovery()
    fun clearError() = repository.clearError()

    override fun onCleared() { repository.close(); super.onCleared() }
}
