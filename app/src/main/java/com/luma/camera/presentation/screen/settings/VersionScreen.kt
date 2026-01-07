package com.luma.camera.presentation.screen.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luma.camera.R
import com.luma.camera.presentation.theme.LumaGold
import kotlin.random.Random

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VersionScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val githubUrl = "https://github.com/Doryoku1223/LumaCamera"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关于 LumaCamera", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black
                ),
                modifier = Modifier.statusBarsPadding()
            )
        },
        containerColor = Color.Black
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            ConfettiOverlay()

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.White.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_launcher_foreground),
                        contentDescription = "LumaCamera",
                        modifier = Modifier.size(64.dp)
                    )
                }

                Text(
                    text = "LumaCamera",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = "v1.0.2",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )

                SectionCard(title = "版本更新") {
                    BulletText("优化 LUT 拖动体验，手感更稳")
                    BulletText("修复调色盘与滤镜联动显示问题")
                    BulletText("调整设置界面结构与描述")
                }

                SectionCard(title = "感谢信") {
                    Text(
                        text = "作者：Yuqian",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "感谢你的使用与反馈，帮助 LumaCamera 变得更好。",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 13.sp
                    )
                }

                SectionCard(title = "开源须知") {
                    Text(
                        text = "欢迎提出建议或提交改进，让更多摄影爱好者受益。",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 13.sp
                    )
                }

                SectionCard(title = "项目地址") {
                    RowHeader(icon = Icons.Outlined.Link, title = "GitHub")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = githubUrl,
                        color = LumaGold,
                        fontSize = 13.sp,
                        modifier = Modifier.clickable {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl))
                            context.startActivity(intent)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(16.dp)
    ) {
        RowHeader(icon = Icons.Outlined.Info, title = title)
        Spacer(modifier = Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun RowHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = LumaGold
        )
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun BulletText(text: String) {
    Text(
        text = "• $text",
        color = Color.White.copy(alpha = 0.9f),
        fontSize = 14.sp,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
    )
}

private data class ConfettiItem(
    val x: Float,
    val size: Float,
    val speed: Float,
    val offset: Float,
    val rotation: Float,
    val color: Color
)

@Composable
private fun ConfettiOverlay() {
    val colors = listOf(
        Color(0xFFFFD166),
        Color(0xFF06D6A0),
        Color(0xFF118AB2),
        Color(0xFFEF476F),
        Color(0xFFF4A261)
    )
    val confetti = remember {
        List(28) {
            ConfettiItem(
                x = Random.nextFloat(),
                size = Random.nextInt(6, 14).toFloat(),
                speed = Random.nextFloat() * 0.6f + 0.4f,
                offset = Random.nextFloat(),
                rotation = Random.nextFloat() * 360f,
                color = colors[Random.nextInt(colors.size)]
            )
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "confetti")
    val progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6000, easing = LinearEasing)
        ),
        label = "confettiProgress"
    )

    androidx.compose.foundation.Canvas(
        modifier = Modifier.fillMaxSize()
    ) {
        val width = size.width
        val height = size.height

        for (item in confetti) {
            val y = ((progress * item.speed + item.offset) % 1f) * (height + item.size) - item.size
            val x = item.x * width
            rotate(item.rotation) {
                drawRect(
                    color = item.color,
                    topLeft = androidx.compose.ui.geometry.Offset(x, y),
                    size = androidx.compose.ui.geometry.Size(item.size, item.size * 1.6f)
                )
            }
        }
    }
}
