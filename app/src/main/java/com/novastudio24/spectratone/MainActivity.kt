package com.novastudio24.spectratone

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novastudio24.spectratone.engine.NativeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SpectraToneStudioApp()
        }
    }
}

enum class ToolTab { BRIGHTNESS, CONTRAST, SATURATION }

@Composable
fun SpectraToneStudioApp() {
    val context = LocalContext.current
    var originalBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var editedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showOriginal by remember { mutableStateOf(false) }

    var selectedTool by remember { mutableStateOf(ToolTab.BRIGHTNESS) }
    var brightness by remember { mutableFloatStateOf(0.0f) }   // -1.0 to +1.0
    var contrast by remember { mutableFloatStateOf(0.0f) }     // -100 to +100
    var saturation by remember { mutableFloatStateOf(0.0f) }   // -1.0 to +1.0

    // Photo Picker
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val source = ImageDecoder.createSource(context.contentResolver, it)
                val bmp = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.isMutableRequired = true
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                originalBitmap = bmp
                editedBitmap = bmp.copy(Bitmap.Config.ARGB_8888, true)
                brightness = 0.0f
                contrast = 0.0f
                saturation = 0.0f
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to load image: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Process image in C++ engine whenever sliders change
    LaunchedEffect(brightness, contrast, saturation, originalBitmap) {
        val src = originalBitmap ?: return@LaunchedEffect
        val dst = editedBitmap ?: return@LaunchedEffect

        withContext(Dispatchers.Default) {
            NativeEngine.applyAdjustments(src, dst, brightness, contrast, saturation)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF0D0D11)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // Top Studio Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("SpectraTone", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Nova Studio 24", fontSize = 11.sp, color = Color(0xFF00E676))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { galleryLauncher.launch("image/*") },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF242430)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Open Photo", fontSize = 12.sp, color = Color.White)
                    }

                    if (editedBitmap != null) {
                        Button(
                            onClick = {
                                saveBitmapToGallery(context, editedBitmap!!)
                                Toast.makeText(context, "Saved to Gallery in 100% Quality!", Toast.LENGTH_LONG).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Save", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                        }
                    }
                }
            }

            // Central Canvas Preview Area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(8.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF16161E)),
                contentAlignment = Alignment.Center
            ) {
                if (originalBitmap == null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No photo loaded", color = Color.Gray, fontSize = 15.sp)
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = { galleryLauncher.launch("image/*") },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F1F2C))
                        ) {
                            Text("Select an image to edit", color = Color(0xFF00E676))
                        }
                    }
                } else {
                    val displayBmp = if (showOriginal) originalBitmap else editedBitmap
                    displayBmp?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "Editor Preview",
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onPress = {
                                            showOriginal = true
                                            tryAwaitRelease()
                                            showOriginal = false
                                        }
                                    )
                                }
                        )
                    }

                    // Hold to Compare Badge
                    Surface(
                        color = Color.Black.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                    ) {
                        Text(
                            text = if (showOriginal) "SHOWING ORIGINAL" else "TOUCH & HOLD TO COMPARE",
                            color = if (showOriginal) Color(0xFFFFD54F) else Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // Bottom Editing Tools & Sliders
            if (originalBitmap != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF14141B))
                        .padding(16.dp)
                ) {
                    // Value and Slider Control
                    val (currentValue, range, onValueChange) = when (selectedTool) {
                        ToolTab.BRIGHTNESS -> Triple(brightness, -1.0f..1.0f) { v: Float -> brightness = v }
                        ToolTab.CONTRAST -> Triple(contrast, -100.0f..100.0f) { v: Float -> contrast = v }
                        ToolTab.SATURATION -> Triple(saturation, -1.0f..1.0f) { v: Float -> saturation = v }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = selectedTool.name,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00E676)
                        )
                        Text(
                            text = String.format("%.0f", if (selectedTool == ToolTab.CONTRAST) currentValue else currentValue * 100),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    Slider(
                        value = currentValue,
                        onValueChange = onValueChange,
                        valueRange = range,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF00E676),
                            activeTrackColor = Color(0xFF00E676),
                            inactiveTrackColor = Color(0xFF282836)
                        )
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Tool Selector Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        ToolButton("Brightness", selectedTool == ToolTab.BRIGHTNESS) { selectedTool = ToolTab.BRIGHTNESS }
                        ToolButton("Contrast", selectedTool == ToolTab.CONTRAST) { selectedTool = ToolTab.CONTRAST }
                        ToolButton("Saturation", selectedTool == ToolTab.SATURATION) { selectedTool = ToolTab.SATURATION }
                    }
                }
            }
        }
    }
}

@Composable
fun ToolButton(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isSelected) Color(0xFF00E676) else Color(0xFF1F1F2C)
        )
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = if (isSelected) Color.Black else Color.White,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

fun saveBitmapToGallery(context: android.content.Context, bitmap: Bitmap) {
    val filename = "SpectraTone_${System.currentTimeMillis()}.png"
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/SpectraTone")
        }
    }
    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
    uri?.let {
        context.contentResolver.openOutputStream(it)?.use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
    }
}
