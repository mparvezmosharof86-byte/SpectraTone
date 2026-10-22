package com.novastudio24.spectratone

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import java.io.InputStream
import kotlin.math.max

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SpectraToneStudioApp() }
    }
}

enum class ModeTab { ADJUST, HSL_MIX, PRESETS, TRANSFORM }
enum class AdjustTool { BRIGHTNESS, CONTRAST, SATURATION, SHARPNESS, WARMTH, TINT, VIGNETTE }
data class FilterPreset(val id: Int, val name: String)
data class HslColorInfo(val index: Int, val name: String, val color: Color)

@Composable
fun SpectraToneStudioApp() {
    val context = LocalContext.current

    var masterBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var previewOriginal by remember { mutableStateOf<Bitmap?>(null) }
    var previewEdited by remember { mutableStateOf<Bitmap?>(null) }
    var showOriginal by remember { mutableStateOf(false) }
    var renderVersion by remember { mutableIntStateOf(0) }

    var currentMode by remember { mutableStateOf(ModeTab.ADJUST) }
    var selectedTool by remember { mutableStateOf(AdjustTool.BRIGHTNESS) }

    // Sliders
    var brightness by remember { mutableFloatStateOf(0.0f) }
    var contrast by remember { mutableFloatStateOf(0.0f) }
    var saturation by remember { mutableFloatStateOf(0.0f) }
    var sharpness by remember { mutableFloatStateOf(0.0f) }
    var warmth by remember { mutableFloatStateOf(0.0f) }
    var tint by remember { mutableFloatStateOf(0.0f) }
    var vignette by remember { mutableFloatStateOf(0.0f) }
    var selectedPresetId by remember { mutableIntStateOf(0) }

    val hslValues = remember { mutableStateListOf(*Array(24) { 0.0f }) }
    var selectedColorIndex by remember { mutableIntStateOf(0) }

    val colorsList = remember {
        listOf(
            HslColorInfo(0, "Red", Color(0xFFFF3333)),
            HslColorInfo(1, "Orange", Color(0xFFFF9900)),
            HslColorInfo(2, "Yellow", Color(0xFFFFEE00)),
            HslColorInfo(3, "Green", Color(0xFF33CC33)),
            HslColorInfo(4, "Cyan", Color(0xFF00EEEE)),
            HslColorInfo(5, "Blue", Color(0xFF3366FF)),
            HslColorInfo(6, "Purple", Color(0xFF9933FF)),
            HslColorInfo(7, "Magenta", Color(0xFFFF3399))
        )
    }

    val presets = remember {
        listOf(
            FilterPreset(0, "Original"),
            FilterPreset(1, "Cinematic"),
            FilterPreset(2, "Vintage"),
            FilterPreset(3, "Film Noir"),
            FilterPreset(4, "Cyberpunk")
        )
    }

    fun setupNewBitmap(newMaster: Bitmap) {
        masterBitmap = newMaster
        val maxDimension = 1600
        val scale = if (max(newMaster.width, newMaster.height) > maxDimension) {
            maxDimension.toFloat() / max(newMaster.width, newMaster.height)
        } else 1.0f

        val previewBmp = if (scale < 1.0f) {
            Bitmap.createScaledBitmap(newMaster, (newMaster.width * scale).toInt(), (newMaster.height * scale).toInt(), true)
        } else {
            newMaster.copy(Bitmap.Config.ARGB_8888, true)
        }
        previewOriginal = previewBmp
        previewEdited = previewBmp.copy(Bitmap.Config.ARGB_8888, true)
        renderVersion++
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            try {
                val source = ImageDecoder.createSource(context.contentResolver, it)
                var bmp = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.isMutableRequired = true
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                context.contentResolver.openInputStream(it)?.use { stream ->
                    bmp = autoRotateBitmap(stream, bmp)
                }
                brightness = 0.0f; contrast = 0.0f; saturation = 0.0f; sharpness = 0.0f
                warmth = 0.0f; tint = 0.0f; vignette = 0.0f; selectedPresetId = 0
                for (i in 0 until 24) hslValues[i] = 0.0f
                setupNewBitmap(bmp)
            } catch (e: Exception) {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(brightness, contrast, saturation, sharpness, warmth, tint, vignette, selectedPresetId, hslValues.toList(), previewOriginal) {
        val src = previewOriginal ?: return@LaunchedEffect
        val dst = previewEdited ?: return@LaunchedEffect

        withContext(Dispatchers.Default) {
            NativeEngine.applyAdjustments(
                src, dst,
                brightness, contrast, saturation,
                warmth, tint, vignette, sharpness,
                selectedPresetId, hslValues.toFloatArray()
            )
        }
        renderVersion++
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0D0D11)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
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
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Open", fontSize = 12.sp, color = Color.White)
                    }
                    if (masterBitmap != null) {
                        Button(
                            onClick = {
                                val fullOriginal = masterBitmap!!
                                val fullOutput = fullOriginal.copy(Bitmap.Config.ARGB_8888, true)
                                NativeEngine.applyAdjustments(
                                    fullOriginal, fullOutput,
                                    brightness, contrast, saturation,
                                    warmth, tint, vignette, sharpness,
                                    selectedPresetId, hslValues.toFloatArray()
                                )
                                saveBitmapToGallery(context, fullOutput)
                                Toast.makeText(context, "Saved in 100% Original Resolution!", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Save", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                        }
                    }
                }
            }

            // Preview Canvas
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF16161E)),
                contentAlignment = Alignment.Center
            ) {
                if (previewOriginal == null) {
                    Button(onClick = { galleryLauncher.launch("image/*") }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F1F2C))) {
                        Text("Tap to Choose a Photo", color = Color(0xFF00E676), fontSize = 14.sp)
                    }
                } else {
                    key(renderVersion, showOriginal) {
                        val displayBmp = if (showOriginal) previewOriginal else previewEdited
                        displayBmp?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = "Preview",
                                modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                                    detectTapGestures(onPress = { showOriginal = true; tryAwaitRelease(); showOriginal = false })
                                }
                            )
                        }
                    }
                }
            }

            // Controls
            if (previewOriginal != null) {
                Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF14141B)).padding(14.dp)) {
                    // Mode Tabs
                    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.Center) {
                        Button(onClick = { currentMode = ModeTab.ADJUST }, shape = RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = if (currentMode == ModeTab.ADJUST) Color(0xFF00E676) else Color(0xFF22222E))) {
                            Text("Adjust", fontSize = 11.sp, color = if (currentMode == ModeTab.ADJUST) Color.Black else Color.White)
                        }
                        Button(onClick = { currentMode = ModeTab.HSL_MIX }, shape = RoundedCornerShape(0.dp), colors = ButtonDefaults.buttonColors(containerColor = if (currentMode == ModeTab.HSL_MIX) Color(0xFF00E676) else Color(0xFF22222E))) {
                            Text("Color Mix", fontSize = 11.sp, color = if (currentMode == ModeTab.HSL_MIX) Color.Black else Color.White)
                        }
                        Button(onClick = { currentMode = ModeTab.PRESETS }, shape = RoundedCornerShape(0.dp), colors = ButtonDefaults.buttonColors(containerColor = if (currentMode == ModeTab.PRESETS) Color(0xFF00E676) else Color(0xFF22222E))) {
                            Text("Presets", fontSize = 11.sp, color = if (currentMode == ModeTab.PRESETS) Color.Black else Color.White)
                        }
                        Button(onClick = { currentMode = ModeTab.TRANSFORM }, shape = RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = if (currentMode == ModeTab.TRANSFORM) Color(0xFF00E676) else Color(0xFF22222E))) {
                            Text("Crop/Rotate", fontSize = 11.sp, color = if (currentMode == ModeTab.TRANSFORM) Color.Black else Color.White)
                        }
                    }

                    if (currentMode == ModeTab.ADJUST) {
                        val currentVal = when (selectedTool) {
                            AdjustTool.BRIGHTNESS -> brightness
                            AdjustTool.CONTRAST -> contrast
                            AdjustTool.SATURATION -> saturation
                            AdjustTool.SHARPNESS -> sharpness
                            AdjustTool.WARMTH -> warmth
                            AdjustTool.TINT -> tint
                            AdjustTool.VIGNETTE -> vignette
                        }
                        val range = when (selectedTool) {
                            AdjustTool.CONTRAST -> -100.0f..100.0f
                            AdjustTool.SHARPNESS -> 0.0f..1.0f
                            AdjustTool.VIGNETTE -> 0.0f..1.0f
                            else -> -1.0f..1.0f
                        }
                        Slider(
                            value = currentVal,
                            onValueChange = { v ->
                                when (selectedTool) {
                                    AdjustTool.BRIGHTNESS -> brightness = v
                                    AdjustTool.CONTRAST -> contrast = v
                                    AdjustTool.SATURATION -> saturation = v
                                    AdjustTool.SHARPNESS -> sharpness = v
                                    AdjustTool.WARMTH -> warmth = v
                                    AdjustTool.TINT -> tint = v
                                    AdjustTool.VIGNETTE -> vignette = v
                                }
                            },
                            valueRange = range,
                            colors = SliderDefaults.colors(thumbColor = Color(0xFF00E676), activeTrackColor = Color(0xFF00E676))
                        )
                        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            AdjustTool.entries.forEach { tool ->
                                Button(
                                    onClick = { selectedTool = tool },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = if (selectedTool == tool) Color(0xFF00E676) else Color(0xFF1E1E28))
                                ) {
                                    Text(tool.name, fontSize = 11.sp, color = if (selectedTool == tool) Color.Black else Color.White)
                                }
                            }
                        }
                    } else if (currentMode == ModeTab.HSL_MIX) {
                        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            colorsList.forEach { c ->
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(c.color)
                                        .border(if (selectedColorIndex == c.index) 3.dp else 0.dp, Color.White, CircleShape)
                                        .clickable { selectedColorIndex = c.index }
                                )
                            }
                        }
                        val hIdx = selectedColorIndex * 3
                        val sIdx = selectedColorIndex * 3 + 1
                        val lIdx = selectedColorIndex * 3 + 2

                        Text("HUE SHIFT", fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                        Slider(value = hslValues[hIdx], onValueChange = { hslValues[hIdx] = it }, valueRange = -1.0f..1.0f, colors = SliderDefaults.colors(thumbColor = Color(0xFF00E676), activeTrackColor = Color(0xFF00E676)))

                        Text("SATURATION", fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                        Slider(value = hslValues[sIdx], onValueChange = { hslValues[sIdx] = it }, valueRange = -1.0f..1.0f, colors = SliderDefaults.colors(thumbColor = Color(0xFF00E676), activeTrackColor = Color(0xFF00E676)))

                        Text("LUMINANCE", fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                        Slider(value = hslValues[lIdx], onValueChange = { hslValues[lIdx] = it }, valueRange = -1.0f..1.0f, colors = SliderDefaults.colors(thumbColor = Color(0xFF00E676), activeTrackColor = Color(0xFF00E676)))
                    } else if (currentMode == ModeTab.PRESETS) {
                        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            presets.forEach { preset ->
                                Button(
                                    onClick = { selectedPresetId = preset.id },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = if (selectedPresetId == preset.id) Color(0xFF00E676) else Color(0xFF22222E))
                                ) {
                                    Text(preset.name, fontSize = 12.sp, color = if (selectedPresetId == preset.id) Color.Black else Color.White)
                                }
                            }
                        }
                    } else { // TRANSFORM / CROP MODE
                        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                masterBitmap?.let {
                                    val m = Matrix().apply { postRotate(90f) }
                                    setupNewBitmap(Bitmap.createBitmap(it, 0, 0, it.width, it.height, m, true))
                                }
                            }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22222E))) {
                                Text("Rotate 90°", fontSize = 11.sp, color = Color.White)
                            }
                            Button(onClick = {
                                masterBitmap?.let {
                                    val m = Matrix().apply { postScale(-1f, 1f, it.width / 2f, it.height / 2f) }
                                    setupNewBitmap(Bitmap.createBitmap(it, 0, 0, it.width, it.height, m, true))
                                }
                            }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22222E))) {
                                Text("Flip (Mirror)", fontSize = 11.sp, color = Color.White)
                            }
                            Button(onClick = { cropToAspectRatio(masterBitmap, 1.0f)?.let { setupNewBitmap(it) } }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22222E))) {
                                Text("1:1 (Square)", fontSize = 11.sp, color = Color(0xFF00E676))
                            }
                            Button(onClick = { cropToAspectRatio(masterBitmap, 4.0f / 5.0f)?.let { setupNewBitmap(it) } }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22222E))) {
                                Text("4:5 (Insta)", fontSize = 11.sp, color = Color(0xFF00E676))
                            }
                            Button(onClick = { cropToAspectRatio(masterBitmap, 16.0f / 9.0f)?.let { setupNewBitmap(it) } }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22222E))) {
                                Text("16:9 (Landscape)", fontSize = 11.sp, color = Color(0xFF00E676))
                            }
                            Button(onClick = { cropToAspectRatio(masterBitmap, 9.0f / 16.0f)?.let { setupNewBitmap(it) } }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22222E))) {
                                Text("9:16 (Story)", fontSize = 11.sp, color = Color(0xFF00E676))
                            }
                        }
                    }
                }
            }
        }
    }
}

fun cropToAspectRatio(bmp: Bitmap?, targetRatio: Float): Bitmap? {
    val b = bmp ?: return null
    val currentRatio = b.width.toFloat() / b.height.toFloat()
    val cropWidth: Int
    val cropHeight: Int

    if (currentRatio > targetRatio) {
        cropHeight = b.height
        cropWidth = (cropHeight * targetRatio).toInt()
    } else {
        cropWidth = b.width
        cropHeight = (cropWidth / targetRatio).toInt()
    }

    val startX = (b.width - cropWidth) / 2
    val startY = (b.height - cropHeight) / 2
    return Bitmap.createBitmap(b, startX, startY, cropWidth, cropHeight)
}

fun autoRotateBitmap(stream: InputStream, bitmap: Bitmap): Bitmap {
    val exif = ExifInterface(stream)
    val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        else -> return bitmap
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
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
    uri?.let { context.contentResolver.openOutputStream(it)?.use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream) } }
}
