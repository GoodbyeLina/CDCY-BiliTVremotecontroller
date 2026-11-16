package tvrc.core.tools

import tvrc.core.http.HttpUrlConnectionFetcher
import tvrc.core.http.MiniHttpServer

/**
 * 本地代理跑起来的极简入口（P2-4 验收脚本用；不进 App 主流程）。
 * 用法：gradle :core:runProxy --args="--port 8100"
 * 只给自测用：开启 allowDirectUrl，因此 /live?url= 与 /video?url= 在回环上可用；
 * 生产（App 内）必须保持 allowDirectUrl=false，只接受 /live?room= 与 /video?bvid=。
 */
fun main(args: Array<String>) {
    var port = 8100
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--port" -> { port = args.getOrNull(i + 1)?.toIntOrNull() ?: port; i += 2 }
            else -> i++
        }
    }
    val server = MiniHttpServer(
        port = port,
        bindAddress = "127.0.0.1",
        fetcher = HttpUrlConnectionFetcher(baseHeaders = emptyMap()),
        allowDirectUrl = true
    )
    val bound = server.start()
    println("PROXY_READY port=" + bound)
    System.out.flush()
    Runtime.getRuntime().addShutdownHook(Thread { server.close() })
    Thread.currentThread().join()
}
