package dev.camerakmp.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.camerakmp.camera.AndroidCameraController

/** Camera2 preview target. The controller sizes the surface to a stream size the camera supports. */
@Composable
fun CameraPreview(controller: AndroidCameraController, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = controller.bindPreview(holder)
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
                        controller.onPreviewChanged(holder, width, height)
                    override fun surfaceDestroyed(holder: SurfaceHolder) = controller.unbindPreview()
                })
            }
        },
    )
}
