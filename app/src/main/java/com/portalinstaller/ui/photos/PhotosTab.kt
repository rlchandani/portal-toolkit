package com.portalinstaller.ui.photos

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.portalinstaller.R
import com.portalinstaller.viewmodel.PhotoUploadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun PhotosTab(
    selectedUris: List<Uri>,
    isWifiConnected: Boolean,
    photoUploadState: PhotoUploadState,
    onOpenDevices: () -> Unit,
    onPickPhotos: () -> Unit,
    onUpload: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (!isWifiConnected) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.photos_connect_hint),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onOpenDevices) {
                        Text(stringResource(R.string.open_devices))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(onClick = onPickPhotos) {
            Text(stringResource(R.string.select_photos))
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (selectedUris.isNotEmpty()) {
            Text(
                text = stringResource(R.string.selected_photos_count, selectedUris.size),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                gridItems(selectedUris) { uri ->
                    PhotoThumbnail(uri = uri)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            val isUploading = photoUploadState is PhotoUploadState.Uploading

            Button(
                onClick = onUpload,
                enabled = isWifiConnected && !isUploading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isUploading) {
                    val state = photoUploadState as PhotoUploadState.Uploading
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(
                            R.string.uploading_progress,
                            state.current,
                            state.total
                        )
                    )
                } else {
                    Text(stringResource(R.string.send_to_portal))
                }
            }
        } else {
            Text(
                text = stringResource(R.string.select_photos_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}


@Composable
internal fun PhotoThumbnail(uri: Uri) {
    val context = LocalContext.current
    val thumbnail = rememberPhotoThumbnail(context, uri)

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}

/**
 * Incarca asincron (pe Dispatchers.IO) un thumbnail redus pentru un Uri
 * de poza, ca sa nu blocheze UI-ul si sa nu consume memorie excesiva.
 * Content resolver-ul este deschis de doua ori: o data pentru dimensiuni
 * si o data pentru decodarea efectiva, deoarece stream-urile content://
 * nu sunt garantat resetabile sau repositionabile.
 */
@Composable
internal fun rememberPhotoThumbnail(context: Context, uri: Uri): ImageBitmap? {
    val state = produceState<ImageBitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            try {
                val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, boundsOptions)
                }
                val sampleSize = calculateInSampleSize(boundsOptions, 150, 150)
                val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, decodeOptions)
                }?.asImageBitmap()
            } catch (e: Exception) {
                null
            }
        }
    }
    return state.value
}

/**
 * Calculeaza un factor de subesantionare (inSampleSize) pentru a decoda
 * o imagine la o rezolutie mult mai mica decat originalul, evitand
 * incarcarea integrala in memorie a unor poze de rezolutie mare doar
 * pentru un thumbnail mic.
 */
internal fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    val (height, width) = options.outHeight to options.outWidth
    var inSampleSize = 1
    if (height > reqHeight || width > reqWidth) {
        val halfHeight = height / 2
        val halfWidth = width / 2
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}
