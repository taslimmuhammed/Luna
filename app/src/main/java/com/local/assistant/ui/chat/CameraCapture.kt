package com.local.assistant.ui.chat

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.local.assistant.media.AttachmentStore
import java.io.File

/**
 * Takes a photo with the phone's camera app, for a message: the chat's and the overlay's. The
 * camera writes it into Luna's own storage ([AttachmentStore.newCapture]), so no camera
 * permission is needed and the photo doesn't land in the gallery.
 *
 * Returns what opens the camera. [onResult] gets the photo, or null when the user backed out;
 * [onNoCamera] when the phone has no camera app to open.
 */
@Composable
fun rememberCamera(
    newCapture: () -> AttachmentStore.Capture,
    onResult: (File?) -> Unit,
    onNoCamera: () -> Unit = {},
): () -> Unit {
    // Kept if the activity is made again while the camera is up.
    var path by rememberSaveable { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val file = path?.let(::File)
        path = null
        if (taken && file != null && file.length() > 0) {
            onResult(file)
        } else {
            file?.delete()
            onResult(null)
        }
    }
    return {
        val capture = newCapture()
        path = capture.file.absolutePath
        try {
            camera.launch(capture.uri)
        } catch (e: ActivityNotFoundException) {
            path = null
            capture.file.delete()
            onNoCamera()
        }
    }
}
