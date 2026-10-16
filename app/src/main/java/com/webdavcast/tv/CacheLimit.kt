package com.webdavcast.tv

import android.content.Context
import android.os.StatFs
import java.io.File

/**
 * 缓存上限自适应: 按"机器总容量"分档, 再用"剩余空间"收紧。
 *
 * 不再写死 —— 上限由本机自己算:
 *   总容量 ≥4G → 1GB(当前天花板)    更小的机器 → 512MB
 *   再用剩余空间做安全阀: 缓存最多吃掉"剩余空间 + 现有缓存"的一半,
 *   也就是磁盘永远至少留一半空余。
 *   最终夹在 下限 128MB ~ 天花板 1GB 之间。
 *
 * 为什么用 "剩余 + 现有缓存" 而不是只用剩余: 缓存自己会占掉剩余空间,
 * 若只用剩余算, 上限会随缓存增长不断变小 → 反复删了又下(抖动)。
 * 用两者之和则是一个稳定值, 上限只随"机器还有多少可用"变化。
 */
object CacheLimit {
    private const val MB = 1024L * 1024L
    private const val GB = 1024L
    /** 缓存目录名(与 VideoCache 保持一致) */
    private const val DIR = "video_cache"

    /** 绝对下限(MB): 剩余空间再紧也留这么多, 缓存不至于完全失效 */
    const val MIN_MB = 128L
    /** 绝对上限(天花板, MB): 当前 1GB —— 所有机器最多缓存 1GB。
     *  想给大容量机器更大缓存, 只改这一个数(如 4096)即可, 下面的分档会自动生效。 */
    const val MAX_MB = 1024L
    /** 最小档(MB): 总容量小于 4G 的机器 */
    private const val FLOOR_MB = 512L

    /** 总容量(MB) 达到阈值 → 缓存上限(MB), 从大到小匹配。
     *  注意: 当前天花板 1GB, 所以 ≥4G 的机器都被夹到 1GB; 提高 MAX_MB 后这张表才逐级生效。 */
    private val TIERS = listOf(
        64 * GB to 8 * 1024L,
        32 * GB to 6 * 1024L,
        16 * GB to 4 * 1024L,
        8 * GB to 2 * 1024L,
        4 * GB to 1024L,
    )

    /** 现有缓存占用(MB)。取不到返回 0。 */
    private fun cachedMb(ctx: Context): Long = try {
        File(ctx.cacheDir, DIR).walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() } / MB
    } catch (e: Exception) {
        0L
    }

    /** 本机当前应给的缓存上限(MB)。取不到磁盘信息时退回 1GB。 */
    fun limitMb(ctx: Context): Long {
        val st = try {
            StatFs(ctx.cacheDir.absolutePath)
        } catch (e: Exception) {
            null
        } ?: return 1024L
        val totalMb = st.totalBytes / MB
        val availMb = st.availableBytes / MB
        val tier = TIERS.firstOrNull { totalMb >= it.first }?.second ?: FLOOR_MB
        // 安全阀: 缓存最多吃掉(剩余 + 现有缓存)的一半 → 磁盘始终留一半
        val guardMb = (availMb + cachedMb(ctx)) / 2
        return minOf(tier, guardMb).coerceIn(MIN_MB, MAX_MB)
    }

    /** 本机当前应给的缓存上限(字节) */
    fun limitBytes(ctx: Context): Long = limitMb(ctx) * MB

    /** 人类可读: 1024MB → 1.0GB */
    fun describe(mb: Long): String =
        if (mb >= 1024) "%.1fGB".format(mb / 1024.0) else "${mb}MB"

    /** 一行说明, 用于日志/界面: "上限 4.0GB (总24.0GB/剩17.6GB)" */
    fun debugLine(ctx: Context): String {
        val mb = limitMb(ctx)
        val st = try {
            StatFs(ctx.cacheDir.absolutePath)
        } catch (e: Exception) {
            null
        }
        val t = (st?.totalBytes ?: 0L) / MB
        val a = (st?.availableBytes ?: 0L) / MB
        return "上限 ${describe(mb)} (总${describe(t)}/剩${describe(a)})"
    }
}
