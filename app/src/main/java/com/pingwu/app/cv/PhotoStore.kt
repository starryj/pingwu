package com.pingwu.app.cv

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 照片落盘：只存 App 私有目录，不上传（隐私原则见《02》§7）。 */
object PhotoStore {

    fun evidenceDir(context: Context): File =
        File(context.filesDir, "evidence").apply { mkdirs() }

    fun newEvidenceFile(context: Context, doseId: Long, now: Long = System.currentTimeMillis()): File {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))
        val dir = File(evidenceDir(context), day).apply { mkdirs() }
        val stamp = SimpleDateFormat("HHmmss", Locale.US).format(Date(now))
        return File(dir, "dose-" + doseId + "-" + stamp + ".jpg")
    }

    /** 登记照：每件物品一张封面，重拍就覆盖，不占多余空间。 */
    fun itemCoverFile(context: Context, itemId: Long): File {
        val dir = File(context.filesDir, "items").apply { mkdirs() }
        return File(dir, "item-" + itemId + "-cover.jpg")
    }

    /**
     * 一件物品登记的第 [index] 块「认得的样子」。
     * 每块一个文件，重登记时整组覆盖（文件名不变，旧的会被后面的覆盖）。
     */
    fun itemSubjectFile(context: Context, itemId: Long, index: Int): File {
        val dir = File(context.filesDir, "items").apply { mkdirs() }
        return File(dir, "item-" + itemId + "-subject-" + index + ".jpg")
    }

    fun saveJpeg(bitmap: Bitmap, file: File, quality: Int = 90): File {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
        return file
    }
}
