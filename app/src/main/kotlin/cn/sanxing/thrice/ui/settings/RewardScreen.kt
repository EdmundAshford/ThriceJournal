@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)

package cn.sanxing.thrice.ui.settings

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cn.sanxing.thrice.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream

/**
 * 打赏作者页：展示作者支付宝收款码。
 * 长按图片可保存到系统相册（API 29+ 走 MediaStore，无需存储权限），
 * 随后可用支付宝「扫一扫 - 相册」选图付款。
 */
@Composable
fun RewardScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.reward_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.reward_encouragement),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            Image(
                painter = painterResource(R.drawable.alipay_reward_qr),
                contentDescription = stringResource(R.string.reward_qr_cd),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 380.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .combinedClickable(
                        onClick = {},
                        onLongClick = {
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.reward_save_unsupported),
                                    Toast.LENGTH_LONG
                                ).show()
                                return@combinedClickable
                            }
                            scope.launch {
                                val saved = withContext(Dispatchers.IO) {
                                    runCatching {
                                        saveRewardQrToGallery(context)
                                    }.isSuccess
                                }
                                Toast.makeText(
                                    context,
                                    context.getString(
                                        if (saved) R.string.reward_save_success
                                        else R.string.reward_save_failed
                                    ),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    )
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.reward_alipay_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** 将收款码写入系统相册 Pictures/叁省手账（仅 API 29+ 调用）；失败抛异常由调用方兜底。 */
private fun saveRewardQrToGallery(context: Context) {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(
            MediaStore.Images.Media.DISPLAY_NAME,
            "sanxing-reward-${System.currentTimeMillis()}.jpg"
        )
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(
            MediaStore.Images.Media.RELATIVE_PATH,
            "${Environment.DIRECTORY_PICTURES}/叁省手账"
        )
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val collection: Uri = MediaStore.Images.Media
        .getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val item = resolver.insert(collection, values)
        ?: error("MediaStore insert 返回 null")
    var out: OutputStream? = null
    try {
        val bitmap: Bitmap =
            BitmapFactory.decodeResource(context.resources, R.drawable.alipay_reward_qr)
                ?: error("收款码图片解码失败")
        out = resolver.openOutputStream(item) ?: error("无法打开输出流")
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(item, values, null, null)
    } catch (e: Exception) {
        runCatching { resolver.delete(item, null, null) }
        throw e
    } finally {
        runCatching { out?.close() }
    }
}
