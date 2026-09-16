package com.aarrondo.droneview.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aarrondo.droneview.connect.UdpVideoSource
import com.aarrondo.droneview.media.MediaStoreSaver
import com.aarrondo.droneview.media.Mp4Recorder
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CaptureViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext: Context = getApplication<Application>().applicationContext

    val videoSource: UdpVideoSource = UdpVideoSource()

    private val recorderLazy = lazy { Mp4Recorder(appContext) }
    private val recorder: Mp4Recorder by recorderLazy

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _lastSavedUri = MutableStateFlow<Uri?>(null)

    private val _statusMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val statusMessage: SharedFlow<String> = _statusMessage.asSharedFlow()

    private val recordMutex = Mutex()

    private val isSavingPhoto = AtomicBoolean(false)

    init {
        viewModelScope.launch {
            videoSource.frame.collect { bmp ->
                if (bmp != null && _isRecording.value) {
                    try {
                        recorder.onFrame(bmp)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    fun onRecordToggle() {
        viewModelScope.launch {
            recordMutex.withLock {
                if (!_isRecording.value) {
                    try {
                        recorder.start()
                        _isRecording.value = true
                        _statusMessage.emit("Recording started")
                    } catch (e: CancellationException) {
                        _isRecording.value = false
                        throw e
                    } catch (e: Exception) {
                        _isRecording.value = false
                        _statusMessage.emit("Record failed: ${e.message}")
                    }
                } else {
                    _isRecording.value = false
                    try {
                        val uri = recorder.stop(save = true)
                        if (uri != null) {
                            _lastSavedUri.value = uri
                            _statusMessage.emit("Video saved to gallery")
                        } else {
                            _statusMessage.emit("Recording discarded (no frames)")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _statusMessage.emit("Record failed: ${e.message}")
                    }
                }
            }
        }
    }

    fun onSnap() {
        if (!isSavingPhoto.compareAndSet(false, true)) {
            viewModelScope.launch { _statusMessage.emit("Saving…") }
            return
        }
        val bmp = videoSource.frame.value
        if (bmp == null) {
            isSavingPhoto.set(false)
            viewModelScope.launch { _statusMessage.emit("No video yet — wait for LIVE") }
            return
        }
        viewModelScope.launch {
            try {
                val uri = MediaStoreSaver.savePhoto(appContext, bmp)
                _lastSavedUri.value = uri
                _statusMessage.emit("Photo saved to gallery")
            } catch (e: Exception) {
                _statusMessage.emit("Photo failed: ${e.message}")
            } finally {
                isSavingPhoto.set(false)
            }
        }
    }

    override fun onCleared() {
        val initialized = recorderLazy.isInitialized()
        val wasRecording = _isRecording.value || (initialized && recorder.isRecording)
        _isRecording.value = false
        if (initialized) {
            if (wasRecording) {
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching { recorder.stop(save = true) }
                    runCatching { recorder.release() }
                }
            } else {
                runCatching { recorder.release() }
            }
        }
        videoSource.stop()
        videoSource.release()
    }
}

