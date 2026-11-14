package com.pingwu.app.util

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 证据照片保留策略（见 docs/02 §7.4）：通过/失败照片保留 180 天，过期自动清理；
 * 记录（EVIDENCE 行）本身不清，历史页会显示「照片已不在」。
 * 导出 CSV 的缓存保留 7 天。
 */
object EvidenceCleaner {
    private const val TAG = "PwClean"
    const val KEEP_DAYS = 180L
    const val EXPORT_KEEP_DAYS = 7L
    private const val DAY_MS = 24L * 60L * 60L * 1000L

    /** 对账时跳过「刚拍下不久」的照片，避免把还在落库路上的那张删掉。 */
    private const val FRESH_GRACE_MS = 60_000L

    /** 本机凭物照片的数量与占用（用于「我的 → 凭物数据」）。 */
    data class PhotoStats(val count: Int, val bytes: Long)

    fun stats(context: Context): PhotoStats {
        var count = 0
        var bytes = 0L
        val root = context.filesDir.resolve("evidence")
        if (root.isDirectory) {
            root.walkTopDown().forEach { file ->
                if (file.isFile) {
                    count++
                    bytes += file.length()
                }
            }
        }
        return PhotoStats(count, bytes)
    }

    /** @return 删除的文件数 */
    fun cleanup(
        context: Context,
        extraFiles: Collection<String> = emptyList(),
        now: Long = System.currentTimeMillis(),
    ): Int {
        var removed = deleteFiles(context, extraFiles)
        removed += purge(context.filesDir.resolve("evidence"), now - KEEP_DAYS * DAY_MS)
        removed += purge(context.cacheDir.resolve("exports"), now - EXPORT_KEEP_DAYS * DAY_MS)
        return removed
    }

    /**
     * 只删指定照片（彻底删除某几条记录时用）。
     * 只删 App 自己证据目录下的绝对路径，防止误删资源名或外部路径。
     *
     * @return 实际删掉的文件数
     */
    fun deleteFiles(context: Context, keys: Collection<String>): Int {
        if (keys.isEmpty()) {
            Log.d(TAG, "deleteFiles: no keys")
            return 0
        }
        val evidenceRoot = context.filesDir.resolve("evidence")
        val rootPath = evidenceRoot.absolutePath
        val rootCanonical = runCatching { evidenceRoot.canonicalPath }.getOrDefault(rootPath)
        Log.d(TAG, "deleteFiles start keys=${keys.size} root=$rootPath canonical=$rootCanonical")
        var removed = 0
        keys.forEach { key ->
            val file = resolveInside(context, key)
            if (file == null) {
                Log.w(TAG, "deleteFiles skip not-inside-evidence key=$key")
                return@forEach
            }
            val existed = file.isFile
            val deleted = existed && file.delete()
            if (deleted) removed++
            Log.d(TAG, "deleteFiles key=$key existed=$existed deleted=$deleted")
        }
        Log.d(TAG, "deleteFiles done removed=$removed")
        return removed
    }

    /**
     * 对账：把证据目录里「没有任何记录引用」的照片删掉。
     *
     * 为什么需要它：老版本的「清空全部凭物」「删掉物品」只删记录、不删文件，
     * 磁盘上就留下一堆谁都指不到的孤儿照片。它们不显示在界面上，却会被
     * [stats] 统计进去，于是「本机照片 30 张」看起来像删不掉。
     *
     * @param keepKeys 当前仍然有效的 photoKey（来自 pw_evidence）
     * @return 删掉的文件数
     */
    fun purgeOrphans(
        context: Context,
        keepKeys: Collection<String>,
        now: Long = System.currentTimeMillis(),
    ): Int {
        val root = context.filesDir.resolve("evidence")
        if (!root.isDirectory) return 0
        val keep = keepKeys.mapNotNull { key ->
            val file = resolveInside(context, key) ?: return@mapNotNull null
            runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
        }.toMutableSet()
        var removed = 0
        var fresh = 0
        root.walkTopDown().forEach { file ->
            if (!file.isFile) return@forEach
            // 刚拍下、还没写进数据库的照片先留着：判定和落库之间有时差，
            // 对账不能把「正在存的那一张」当成孤儿。
            if (now - file.lastModified() < FRESH_GRACE_MS) {
                fresh++
                return@forEach
            }
            val path = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
            if (path !in keep && file.delete()) removed++
        }
        root.walkBottomUp().forEach { dir ->
            if (dir != root && dir.isDirectory && dir.listFiles()?.isEmpty() == true) dir.delete()
        }
        Log.d(TAG, "purgeOrphans keep=${keep.size} removed=$removed fresh_skipped=$fresh")
        return removed
    }

    /**
     * 把记录里的 photoKey 解析成证据目录下的文件；不在目录里就返回 null（宁可不删）。
     *
     * 老记录可能存的是 `/data/user/0/<pkg>/files/evidence/...`，也可能只存相对路径
     * `2026-11-14/dose-1-080102.jpg`（Csv/迁移遗留），两种都认。
     */
    private fun resolveInside(context: Context, key: String): File? {
        val root = context.filesDir.resolve("evidence")
        val rootPath = root.absolutePath
        val rootCanonical = runCatching { root.canonicalPath }.getOrDefault(rootPath)
        val candidates = buildList {
            if (key.startsWith("/")) {
                add(File(key))
                // /data/data/<pkg>/... 与 /data/user/0/<pkg>/... 互相兜底
                val pkg = context.packageName
                if (key.startsWith("/data/data/$pkg/")) {
                    add(File(key.replaceFirst("/data/data/$pkg/", "/data/user/0/$pkg/")))
                } else if (key.startsWith("/data/user/0/$pkg/")) {
                    add(File(key.replaceFirst("/data/user/0/$pkg/", "/data/data/$pkg/")))
                }
                add(File(root, File(key).name))
            } else {
                add(File(root, key))
            }
        }
        return candidates.firstOrNull { file ->
            val canonical = runCatching { file.canonicalPath }.getOrNull() ?: return@firstOrNull false
            val path = file.absolutePath
            val inside = canonical.startsWith(rootCanonical + File.separator) ||
                path.startsWith(rootPath + File.separator)
            if (inside) {
                Log.d(TAG, "resolveInside key=$key -> $path")
            }
            inside
        }
    }

    private fun purge(dir: File, olderThan: Long): Int {
        if (!dir.isDirectory) return 0
        var removed = 0
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                removed += purge(child, olderThan)
                if (child.listFiles()?.isEmpty() == true) child.delete()
            } else if (child.lastModified() < olderThan) {
                if (child.delete()) removed++
            }
        }
        return removed
    }
}
