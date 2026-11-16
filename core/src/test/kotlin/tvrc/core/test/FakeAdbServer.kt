package tvrc.core.test

import tvrc.core.adb.AdbPacket
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

/** 一条假 adb 连接：读写都复用同一对缓冲流，避免二次包装吃掉字节。 */
class FakeAdbConn(socket: Socket) {
    private val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
    private val output = BufferedOutputStream(socket.getOutputStream())

    fun read(): AdbPacket = AdbPacket.read(input)

    fun write(packet: AdbPacket) {
        output.write(packet.encode())
        output.flush()
    }

    fun writeRaw(bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }
}

/**
 * 进程内的假 adb 设备：监听 127.0.0.1 随机端口，按脚本走一遍协议。
 * 不需要真机、不需要 Android —— 用来验证「我们这一侧」的协议实现。
 */
class FakeAdbServer(handler: (FakeAdbConn) -> Unit) : Closeable {

    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val failure = AtomicReference<Throwable?>(null)
    private val thread = Thread {
        try {
            server.accept().use { socket ->
                socket.soTimeout = 5_000
                handler(FakeAdbConn(socket))
            }
        } catch (t: Throwable) {
            failure.set(t)
        }
    }.apply { isDaemon = true; name = "fake-adb-server" }

    val port: Int get() = server.localPort

    fun start(): FakeAdbServer {
        thread.start()
        return this
    }

    /** 关掉监听并等脚本线程收尾；脚本里抛的异常在这里冒出来。 */
    fun stopAndCheck() {
        runCatching { server.close() }
        thread.join(5_000)
        val t = failure.get()
        if (t != null) throw AssertionError("fake server failed: " + t.message, t)
    }

    override fun close() {
        runCatching { server.close() }
    }
}
