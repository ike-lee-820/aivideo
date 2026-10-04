package com.ikelee.aivideo

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File

object FfmpegHelper {

    /** 从视频提取最后一帧，输出 JPG */
    fun extractLastFrame(video: File, output: File): Boolean {
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()
        val cmd = "-y -sseof -0.15 -i \"${video.absolutePath}\" -vsync 0 -frames:v 1 -q:v 2 \"${output.absolutePath}\""
        val session = FFmpegKit.execute(cmd)
        return ReturnCode.isSuccess(session.returnCode) && output.exists() && output.length() > 0
    }

    /** 拼接多个视频片段 */
    fun concat(segments: List<File>, output: File, workDir: File): Boolean {
        if (segments.isEmpty()) return false
        if (output.exists()) output.delete()

        if (segments.size == 1) {
            segments[0].copyTo(output, overwrite = true)
            return true
        }

        workDir.mkdirs()
        val listFile = File(workDir, "concat_list.txt")
        listFile.writeText(
            segments.joinToString("\n") { "file '${it.absolutePath}'" }
        )

        // 先尝试直接复制流（快）
        val cmd1 = "-y -f concat -safe 0 -i \"${listFile.absolutePath}\" -c copy \"${output.absolutePath}\""
        val s1 = FFmpegKit.execute(cmd1)
        if (ReturnCode.isSuccess(s1.returnCode) && output.exists() && output.length() > 0) {
            return true
        }

        // 失败则转码拼接
        val cmd2 = "-y -f concat -safe 0 -i \"${listFile.absolutePath}\" -c:v libx264 -preset ultrafast -c:a aac \"${output.absolutePath}\""
        val s2 = FFmpegKit.execute(cmd2)
        return ReturnCode.isSuccess(s2.returnCode) && output.exists() && output.length() > 0
    }
}
