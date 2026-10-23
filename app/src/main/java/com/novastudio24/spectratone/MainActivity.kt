package com.novastudio24.spectratone

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novastudio24.spectratone.engine.NativeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.math.max

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SpectraToneStudioApp() }
    }
}

enum class ModeTab { ADJUST, HSL_MIX, PRESETS, TRANSFORM, AI_STUDIO }
enum class AdjustTool { BRIGHTNESS, CONTRAST, SATURATION, SKIN_SMOOTH, SHARPNESS, WARMTH, TINT, VIGNETTE }
data class FilterPreset(val id: Int, val name: String)
data class HslColorInfo(val index: Int, val name: String, val color: Color)
data class BgColorChoice(val name: String, val color: Int?, val isTransparent: Boolean = false)

@Composable
fun SpectraToneStudioApp() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var masterBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var previewOriginal by remember { mutableStateOf<Bitmap?>(null) }
    var previewEdited by remember { mutableStateOf<Bitmap?>(null) }
    var cutoutBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showOriginal by remember { mutableStateOf(false) }
    var isAiProcessing by remember { mutableStateOf(false) }
    var renderVersion by remember { mutableIntStateOf(0) }

    var currentMode by remember { mutableStateOf(ModeTab.ADJUST) }
    var selectedTool by remember { mutableStateOf(AdjustTool.BRIGHTNESS) }

    // Sliders
    var brightness by remember { mutableFloatStateOf(0.0f) }
    var contrast by remember { mutableFloatStateOf(0.0f) }
    var saturation by remember { mutableFloatStateOf(0.0f) }
    var skinSmooth by remember { mutableFloatStateOf(0.0f) }
    var sharpness by remember { mutableFloatStateOf(0.0f) }
    var warmth by remember { mutableFloatStateOf(0.0f) }
    var tint by remember { mutableFloatStateOf(0.0f) }
    var vignette by remember { mutableFloatStateOf(0.0f) }
    var selectedPresetId by remember { mutableIntStateOf(0) }

    // Cutout Styling State
    var activeBgColor by remember { mutableStateOf<Int?>(null) }
    var hasOutline by remember { mutableStateOf(false) }
    var hasDropShadow by remember { mutableStateOf(false) }

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

    val bgChoices = remember {
        listOf(
            BgColorChoice("Transparent", null, true),
            BgColorChoice("Passport White", android.graphics.Color.WHITE),
            BgColorChoice("Embassy Blue", android.graphics.Color.parseColor("#1565C0")),
            BgColorChoice("Soft Gray", android.graphics.Color.parseColor("#E0E0E0")),
            BgColorChoice("Studio Red", android.graphics.Color.parseColor("#B71C1C")),
            BgColorChoice("Pitch Black", android.graphics.Color.BLACK)
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

    fun rebuildCutoutComposite() {
        val base = cutoutBitmap ?: return
        val styled = applyCutoutStyling(
            base,
            bgColor = activeBgColor,
            strokeColor = if (hasOutline) android.graphics.Color.WHITE else null,
            strokeWidth = if (hasOutline) 12f else 0f,
            hasShadow = hasDropShadow
        )
        setupNewBitmap(styled)
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
                brightness = 0.0f; contrast = 0.0f; saturation = 0.0f; skinSmooth = 0.0f; sharpness = 0.0f
                warmth = 0.0f; tint = 0.0f; vignette = 0.0f; selectedPresetId = 0
                for (i in 0 until 24) hslValues[i] = 0.0f
                cutoutBitmap = null
                hasOutline = false
                hasDropShadow = false
                activeBgColor = null
                setupNewBitmap(bmp)
            } catch (e: Exception) {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(brightness, contrast, saturation, skinSmooth, sharpness, warmth, tint, vignette, selectedPresetId, hslValues.toList(), previewOriginal) {
        val src = previewOriginal ?: return@LaunchedEffect
        val dst = previewEdited ?: return@LaunchedEffect

        withContext(Dispatchers.Default) {
            NativeEngine.applyAdjustments(
                src, dst,
                brightness, contrast, saturation,
                warmth, tint, vignette, sharpness, skinSmooth,
                selectedPresetId, hslValues.toFloatArray()
            )
        }
        renderVersion++
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF09090D)) {
        Column(modifier = Modifier.fillMaxSize()) {

            // Top Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF111118))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF00E676)))
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text("SpectraTone", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                        Text("PRO MASTER STUDIO", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00E676), letterSpacing = 1.sp)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { galleryLauncher.launch("image/*") },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E1E2A)),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(if (previewOriginal == null) "Open Photo" else "Change", fontSize = 12.sp, color = Color.White)
                    }

                    if (masterBitmap != null) {
                        Button(
                            onClick = {
                                val fullOriginal = masterBitmap!!
                                val fullOutput = fullOriginal.copy(Bitmap.Config.ARGB_8888, true)
                                NativeEngine.applyAdjustments(
                                    fullOriginal, fullOutput,
                                    brightness, contrast, saturation,
                                    warmth, tint, vignette, sharpness, skinSmooth,
                                    selectedPresetId, hslValues.toFloatArray()
                                )
                                saveBitmapToGallery(context, fullOutput)
                                Toast.makeText(context, "Saved in 100% Ultra Quality!", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text("Export", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                        }
                    }
                }
            }

            // Canvas & Preview
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF101017)),
                contentAlignment = Alignment.Center
            ) {
                if (previewOriginal == null) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(Brush.radialGradient(listOf(Color(0xFF00E676).copy(0.3f), Color.Transparent))),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF171724))
                                    .border(2.dp, Color(0xFF00E676), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("+", fontSize = 28.sp, color = Color(0xFF00E676), fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Text("No Photo Loaded", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Select a photo to unleash C++ Color Science & AI Cutout Engine", fontSize = 12.sp, color = Color.Gray, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Spacer(modifier = Modifier.height(24.dp))

                        Button(
                            onClick = { galleryLauncher.launch("image/*") },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
                        ) {
                            Text("Import Photo from Gallery", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .drawBehind {
                                val checkSize = 24f
                                val numCols = (size.width / checkSize).toInt() + 1
                                val numRows = (size.height / checkSize).toInt() + 1
                                val c1 = androidx.compose.ui.graphics.Color(0xFF181822)
                                val c2 = androidx.compose.ui.graphics.Color(0xFF12121A)

                                for (c in 0 until numCols) {
                                    for (r in 0 until numRows) {
                                        drawRect(
                                            color = if ((c + r) % 2 == 0) c1 else c2,
                                            topLeft = Offset(c * checkSize, r * checkSize),
                                            size = Size(checkSize, checkSize)
                                        )
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        key(renderVersion, showOriginal) {
                            val displayBmp = if (showOriginal) previewOriginal else previewEdited
                            displayBmp?.let {
                                Image(
                                    bitmap = it.asImageBitmap(),
                                    contentDescription = "Preview",
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .pointerInput(Unit) {
                                            detectTapGestures(onPress = { showOriginal = true; tryAwaitRelease(); showOriginal = false })
                                        }
                                )
                            }
                        }

                        Surface(
                            color = Color.Black.copy(0.7f),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(10.dp)
                        ) {
                            Text(
                                text = if (showOriginal) "SHOWING ORIGINAL" else "HOLD TO COMPARE",
                                color = if (showOriginal) Color(0xFFFFD54F) else Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }

                        if (isAiProcessing) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.82f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = Color(0xFF00E676))
                                    Spacer(modifier = Modifier.height(14.dp))
                                    Text("Nvidia RTX GPU Cutting Background...", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text("Edge Feathering in progress", color = Color.Gray, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }

            // Bottom Controls
            if (previewOriginal != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF101016))
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ModeTab.values().forEach { tab ->
                            val isSelected = currentMode == tab
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) Color(0xFF00E676) else Color(0xFF1B1B26))
                                    .clickable { currentMode = tab }
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = tab.name.replace("_", " "),
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) Color.Black else Color(0xFFB0B0C0)
                                )
                            }
                        }
                    }

                    when (currentMode) {
                        ModeTab.ADJUST -> {
                            val currentVal = when (selectedTool) {
                                AdjustTool.BRIGHTNESS -> brightness
                                AdjustTool.CONTRAST -> contrast
                                AdjustTool.SATURATION -> saturation
                                AdjustTool.SKIN_SMOOTH -> skinSmooth
                                AdjustTool.SHARPNESS -> sharpness
                                AdjustTool.WARMTH -> warmth
                                AdjustTool.TINT -> tint
                                AdjustTool.VIGNETTE -> vignette
                            }
                            val range = when (selectedTool) {
                                AdjustTool.CONTRAST -> -100.0f..100.0f
                                AdjustTool.SKIN_SMOOTH, AdjustTool.SHARPNESS, AdjustTool.VIGNETTE -> 0.0f..1.0f
                                else -> -1.0f..1.0f
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(selectedTool.name.replace("_", " "), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00E676))
                                Text(
                                    String.format("%.0f", if (selectedTool == AdjustTool.CONTRAST) currentVal else currentVal * 100),
                                    fontSize = 11.sp,
                                    color = Color.White
                                )
                            }

                            Slider(
                                value = currentVal,
                                onValueChange = { v ->
                                    when (selectedTool) {
                                        AdjustTool.BRIGHTNESS -> brightness = v
                                        AdjustTool.CONTRAST -> contrast = v
                                        AdjustTool.SATURATION -> saturation = v
                                        AdjustTool.SKIN_SMOOTH -> skinSmooth = v
                                        AdjustTool.SHARPNESS -> sharpness = v
                                        AdjustTool.WARMTH -> warmth = v
                                        AdjustTool.TINT -> tint = v
                                        AdjustTool.VIGNETTE -> vignette = v
                                    }
                                },
                                valueRange = range,
                                colors = SliderDefaults.colors(thumbColor = Color(0xFF00E676), activeTrackColor = Color(0xFF00E676))
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                AdjustTool.values().forEach { tool ->
                                    val isToolSel = selectedTool == tool
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (isToolSel) Color(0xFF00E676).copy(0.2f) else Color(0xFF171720))
                                            .border(1.dp, if (isToolSel) Color(0xFF00E676) else Color.Transparent, RoundedCornerShape(6.dp))
                                            .clickable { selectedTool = tool }
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text(tool.name.replace("_", " "), fontSize = 10.sp, color = if (isToolSel) Color(0xFF00E676) else Color.White)
                                    }
                                }
                            }
                        }

                        ModeTab.HSL_MIX -> {
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                colorsList.forEach { c ->
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
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

                            Text("HUE SHIFT", fontSize = 9.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                            Slider(value = hslValues[hIdx], onValueChange = { hslValues[hIdx] = it }, valueRange = -1.0f..1.0f, colors = SliderDefaults.colors(thumbColor = Color(0xFF00E676), activeTrackColor = Color(0xFF00E676)))

                            Text("SATURATION", fontSize = 9.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                            Slider(value = hslValues[sIdx], onValueChange = { hslValues[sIdx] = it }, valueRange = -1.0f..1.0f, colors = SliderDefaults.colors(thumbColor = Color(0xFF00E676), activeTrackColor = Color(0xFF00E676)))

                            Text("LUMINANCE", fontSize = 9.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                            Slider(value = hslValues[lIdx], onValueChange = { hslValues[lIdx] = it }, valueRange = -1.0f..1.0f, colors = SliderDefaults.colors(thumbColor = Color(0xFF00E676), activeTrackColor = Color(0xFF00E676)))
                        }

                        ModeTab.PRESETS -> {
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                presets.forEach { preset ->
                                    val isPreSel = selectedPresetId == preset.id
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isPreSel) Color(0xFF00E676) else Color(0xFF1A1A24))
                                            .clickable { selectedPresetId = preset.id }
                                            .padding(horizontal = 16.dp, vertical = 10.dp)
                                    ) {
                                        Text(preset.name, fontSize = 12.sp, fontWeight = if (isPreSel) FontWeight.Bold else FontWeight.Medium, color = if (isPreSel) Color.Black else Color.White)
                                    }
                                }
                            }
                        }

                        ModeTab.TRANSFORM -> {
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(onClick = {
                                    masterBitmap?.let {
                                        val m = Matrix().apply { postRotate(90f) }
                                        setupNewBitmap(Bitmap.createBitmap(it, 0, 0, it.width, it.height, m, true))
                                    }
                                }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A1A24))) {
                                    Text("Rotate 90°", fontSize = 11.sp, color = Color.White)
                                }
                                Button(onClick = {
                                    masterBitmap?.let {
                                        val m = Matrix().apply { postScale(-1f, 1f, it.width / 2f, it.height / 2f) }
                                        setupNewBitmap(Bitmap.createBitmap(it, 0, 0, it.width, it.height, m, true))
                                    }
                                }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A1A24))) {
                                    Text("Flip (Mirror)", fontSize = 11.sp, color = Color.White)
                                }
                                Button(onClick = { cropToAspectRatio(masterBitmap, 1.0f)?.let { setupNewBitmap(it) } }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A1A24))) {
                                    Text("1:1 (Square)", fontSize = 11.sp, color = Color(0xFF00E676))
                                }
                                Button(onClick = { cropToAspectRatio(masterBitmap, 4.0f / 5.0f)?.let { setupNewBitmap(it) } }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A1A24))) {
                                    Text("4:5 (Insta)", fontSize = 11.sp, color = Color(0xFF00E676))
                                }
                                Button(onClick = { cropToAspectRatio(masterBitmap, 16.0f / 9.0f)?.let { setupNewBitmap(it) } }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A1A24))) {
                                    Text("16:9 (Landscape)", fontSize = 11.sp, color = Color(0xFF00E676))
                                }
                            }
                        }

                        ModeTab.AI_STUDIO -> {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Button(
                                        onClick = {
                                            masterBitmap?.let { bmp ->
                                                isAiProcessing = true
                                                coroutineScope.launch {
                                                    val cutout = callGradioAiCutout(bmp)
                                                    if (cutout != null) {
                                                        cutoutBitmap = cutout
                                                        rebuildCutoutComposite()
                                                        Toast.makeText(context, "AI Cutout Successful!", Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        Toast.makeText(context, "Connecting to GPU... Try once more.", Toast.LENGTH_LONG).show()
                                                    }
                                                    isAiProcessing = false
                                                }
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("AI Cutout (GPU)", fontSize = 11.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                                    }

                                    if (cutoutBitmap != null) {
                                        // Outline Stroke Toggle (PicsArt style)
                                        Button(
                                            onClick = {
                                                hasOutline = !hasOutline
                                                rebuildCutoutComposite()
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = if (hasOutline) Color(0xFF00E676) else Color(0xFF1F1F2C)),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text("White Outline", fontSize = 11.sp, color = if (hasOutline) Color.Black else Color.White, fontWeight = FontWeight.Bold)
                                        }

                                        // Drop Shadow Toggle
                                        Button(
                                            onClick = {
                                                hasDropShadow = !hasDropShadow
                                                rebuildCutoutComposite()
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = if (hasDropShadow) Color(0xFF00E676) else Color(0xFF1F1F2C)),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text("Drop Shadow", fontSize = 11.sp, color = if (hasDropShadow) Color.Black else Color.White, fontWeight = FontWeight.Bold)
                                        }

                                        // Background Choices
                                        bgChoices.forEach { choice ->
                                            Button(
                                                onClick = {
                                                    activeBgColor = choice.color
                                                    rebuildCutoutComposite()
                                                },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = when (choice.name) {
                                                        "Passport White" -> Color.White
                                                        "Embassy Blue" -> Color(0xFF1565C0)
                                                        "Studio Red" -> Color(0xFFB71C1C)
                                                        "Soft Gray" -> Color(0xFFE0E0E0)
                                                        else -> Color(0xFF22222E)
                                                    }
                                                ),
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Text(
                                                    choice.name,
                                                    fontSize = 11.sp,
                                                    color = if (choice.name == "Passport White" || choice.name == "Soft Gray") Color.Black else Color.White,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// Applies Background Color, Outline Stroke & Drop Shadow around Cutout (PicsArt/Thumbnail engine)
fun applyCutoutStyling(
    cutout: Bitmap,
    bgColor: Int?,
    strokeColor: Int?,
    strokeWidth: Float,
    hasShadow: Boolean
): Bitmap {
    val result = Bitmap.createBitmap(cutout.width, cutout.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(result)

    bgColor?.let { canvas.drawColor(it) }

    // Drop Shadow
    if (hasShadow) {
        val shadowAlpha = cutout.extractAlpha()
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(160, 0, 0, 0)
            maskFilter = BlurMaskFilter(18f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawBitmap(shadowAlpha, 0f, 14f, shadowPaint)
    }

    // Outline Stroke (PicsArt White/Neon Outline)
    if (strokeColor != null && strokeWidth > 0f) {
        val alpha = cutout.extractAlpha()
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = strokeColor
            maskFilter = BlurMaskFilter(strokeWidth, BlurMaskFilter.Blur.SOLID)
        }
        canvas.drawBitmap(alpha, 0f, 0f, strokePaint)
    }

    canvas.drawBitmap(cutout, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG))
    return result
}

suspend fun callGradioAiCutout(bitmap: Bitmap): Bitmap? = withContext(Dispatchers.IO) {
    try {
        val stream = ByteArrayOutputStream()
        val maxDim = 1024
        val scale = if (max(bitmap.width, bitmap.height) > maxDim) maxDim.toFloat() / max(bitmap.width, bitmap.height) else 1.0f
        val scaled = if (scale < 1.0f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
        scaled.compress(Bitmap.CompressFormat.JPEG, 90, stream)
        val imageBytes = stream.toByteArray()

        val client = OkHttpClient.Builder()
            .connectTimeout(90, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()

        val uploadUrl = "https://mdparvez5-spectratone-ai.hf.space/gradio_api/upload"
        val uploadBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("files", "input.jpg", imageBytes.toRequestBody("image/jpeg".toMediaTypeOrNull()))
            .build()

        val uploadRequest = Request.Builder().url(uploadUrl).post(uploadBody).build()
        val uploadResp = client.newCall(uploadRequest).execute()
        if (!uploadResp.isSuccessful) return@withContext null

        val uploadJson = JSONArray(uploadResp.body?.string() ?: "[]")
        if (uploadJson.length() == 0) return@withContext null
        val filePath = uploadJson.getString(0)

        val predictUrl = "https://mdparvez5-spectratone-ai.hf.space/gradio_api/call/predict"
        val payload = JSONObject().apply {
            put("data", JSONArray().put(JSONObject().apply {
                put("path", filePath)
            }))
        }

        val predictReq = Request.Builder()
            .url(predictUrl)
            .post(payload.toString().toRequestBody("application/json".toMediaTypeOrNull()))
            .build()

        val predictResp = client.newCall(predictReq).execute()
        val eventId = JSONObject(predictResp.body?.string() ?: "{}").optString("event_id", "")
        if (eventId.isEmpty()) return@withContext null

        val resultUrl = "https://mdparvez5-spectratone-ai.hf.space/gradio_api/call/predict/$eventId"
        val resultReq = Request.Builder().url(resultUrl).get().build()
        val streamResp = client.newCall(resultReq).execute()

        var resultPath = ""
        streamResp.body?.source()?.let { source ->
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (line.startsWith("data:")) {
                    val dataJson = line.substring(5).trim()
                    if (dataJson.startsWith("[")) {
                        val arr = JSONArray(dataJson)
                        if (arr.length() > 0) {
                            val obj = arr.optJSONObject(0)
                            if (obj != null && obj.has("path")) {
                                resultPath = obj.getString("path")
                            } else if (arr.optString(0).isNotEmpty()) {
                                resultPath = arr.getString(0)
                            }
                        }
                    }
                }
            }
        }

        if (resultPath.isNotEmpty()) {
            val fileDownloadUrl = if (resultPath.startsWith("http")) resultPath else "https://mdparvez5-spectratone-ai.hf.space/gradio_api/file=$resultPath"
            val fileReq = Request.Builder().url(fileDownloadUrl).get().build()
            val fileResp = client.newCall(fileReq).execute()
            if (fileResp.isSuccessful) {
                fileResp.body?.byteStream()?.use { BitmapFactory.decodeStream(it) }
            } else null
        } else null
    } catch (e: Exception) {
        null
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
