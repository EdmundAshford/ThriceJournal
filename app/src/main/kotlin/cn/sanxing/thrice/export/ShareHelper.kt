package cn.sanxing.thrice.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import cn.sanxing.thrice.R
import java.io.File

/**
 * 文件分享：FileProvider + ACTION_SEND（本应用无网络权限，分享全走系统面板）。
 */
object ShareHelper {

    fun uriFor(context: Context, file: File) =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /** 分享文件（长图 image/png、ICS text/calendar、备份 application/json）。 */
    fun shareFile(context: Context, file: File, mime: String, chooserTitle: String): Intent {
        val uri = uriFor(context, file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
