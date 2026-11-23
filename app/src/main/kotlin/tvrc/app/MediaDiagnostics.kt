package tvrc.app

import tvrc.core.http.*

/** Safe, structured transport observations. Never accepts an upstream URL or raw exception text. */
internal object MediaDiagnostics {
    data class Description(val action: String, val status: TraceStatus, val summary: String, val details: String)

    fun describe(e: MediaTransferEvent): Description {
        val action = when (e.phase) {
            MediaPhase.REQUEST -> "请求"
            MediaPhase.RESPONSE -> "上游响应"
            MediaPhase.FIRST_BYTE -> "首批数据"
            MediaPhase.PROGRESS -> "传输采样"
            MediaPhase.END -> "传输结束"
            MediaPhase.FAILURE -> "传输异常"
            MediaPhase.RETRY -> "有限重试"
        }
        val summary = when (e.phase) {
            MediaPhase.REQUEST -> "电视已请求媒体，正在连接来源"
            MediaPhase.RESPONSE -> "媒体来源响应 HTTP ${e.httpStatus ?: "未知"}"
            MediaPhase.FIRST_BYTE -> "首批媒体数据已发往电视（不代表首帧）"
            MediaPhase.PROGRESS -> "媒体数据正在传输（不代表画面流畅度）"
            MediaPhase.END -> if (e.kind == MediaKind.LIVE) "直播连接已结束，不代表正常下播" else "本次媒体请求传输结束，不代表整部视频播放结束"
            MediaPhase.RETRY -> "正在有限重试第 ${e.attempt} 次请求，尚未重新启动电视播放器"
            MediaPhase.FAILURE -> guidance(e)
        }
        val status = when {
            e.phase == MediaPhase.FAILURE && e.failure in listOf(MediaFailure.CLIENT_WRITE, MediaFailure.SERVER_CLOSED) -> TraceStatus.INFO
            e.phase == MediaPhase.FAILURE -> TraceStatus.ERROR
            e.phase == MediaPhase.RETRY -> TraceStatus.WARNING
            e.phase == MediaPhase.FIRST_BYTE -> TraceStatus.SUCCESS
            else -> TraceStatus.INFO
        }
        val details = "请求 #${e.requestId} · ${e.kind} · ${e.mediaIdentity}\n" +
            "HTTP：${e.httpStatus ?: "未知"}，尝试：${e.attempt}\n" +
            "已发送：${e.bytes} 字节，采样速度：${e.bytesPerSecond} 字节/秒，耗时：${e.elapsedMs} ms\n" +
            "错误分类：${e.failure ?: "无"}，异常类型：${e.exceptionType ?: "无"}\n" +
            "边界：统计的是手机代理到电视连接的数据；电视缓冲、解码、首帧和画面流畅度未知。短暂无数据不等于卡顿。"
        return Description(action, status, summary, details)
    }

    private fun guidance(e: MediaTransferEvent): String = when {
        e.failure == MediaFailure.CLIENT_WRITE -> "电视关闭了本次媒体连接（停止、拖动进度或切台均可能发生），不自动重开视频"
        e.failure == MediaFailure.SERVER_CLOSED -> "代理正在关闭，已取消传输，不自动恢复"
        e.httpStatus in listOf(401, 403, 410) -> "媒体地址失效或访问受限；有限刷新仍失败时，请稍后重试或更换内容"
        e.httpStatus == 429 -> "媒体来源请求过于频繁；请稍后再试，避免连续点击播放"
        e.failure == MediaFailure.RESOLVE -> "无法取得播放地址；请检查手机网络及内容是否可用，直播请确认仍在开播"
        e.failure == MediaFailure.UPSTREAM_READ -> "媒体来源读取中断；请检查手机网络。视频不会自动从头重开；直播可确认开播后手动重试"
        e.failure == MediaFailure.UPSTREAM_OPEN -> "无法连接媒体来源；有限重试后仍失败，请检查手机网络或稍后重试"
        e.httpStatus != null -> "媒体来源返回 HTTP ${e.httpStatus}；请稍后重试或更换内容，不无限重连"
        else -> "媒体传输发生异常；请导出诊断记录后重试，不自动从头重开视频"
    }
}
