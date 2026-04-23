package com.focusguard.ui.compose.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.ui.compose.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntruderLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var photos by remember { mutableStateOf<List<File>>(emptyList()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val dir = context.getExternalFilesDir(null)
            if (dir != null && dir.exists()) {
                val files = dir.listFiles { file -> file.name.endsWith(".jpg") }
                if (files != null) {
                    photos = files.sortedByDescending { it.lastModified() }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Registro de Intrusos", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Voltar", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface)
            )
        },
        containerColor = DarkBg
    ) { paddingValues ->
        if (photos.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                Text("Nenhum intruso registrado.", color = TextHint, fontSize = 16.sp)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize().padding(paddingValues)
            ) {
                items(photos) { photoFile ->
                    IntruderPhotoCard(photoFile)
                }
            }
        }
    }
}

@Composable
fun IntruderPhotoCard(file: File) {
    val bitmap = remember(file.absolutePath) {
        BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        modifier = Modifier.fillMaxWidth().aspectRatio(0.75f)
    ) {
        Column {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "Intruso",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                )
            } else {
                Box(modifier = Modifier.weight(1f).fillMaxWidth().background(DarkCardElevated))
            }
            Box(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                val nameWithoutExt = file.nameWithoutExtension
                val formattedName = nameWithoutExt.replace("-", "/")
                Text(formattedName, fontSize = 10.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
            }
        }
    }
}
