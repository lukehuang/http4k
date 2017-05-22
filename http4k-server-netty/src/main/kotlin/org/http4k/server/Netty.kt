package org.http4k.server


import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.Unpooled.wrappedBuffer
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.DefaultHttpRequest
import io.netty.handler.codec.http.HttpHeaderNames.CONNECTION
import io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH
import io.netty.handler.codec.http.HttpHeaderValues.KEEP_ALIVE
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpResponseStatus.CONTINUE
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.HttpUtil.is100ContinueExpected
import io.netty.handler.codec.http.HttpUtil.isKeepAlive
import io.netty.handler.codec.http.HttpVersion.HTTP_1_1
import org.http4k.core.Body
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Uri
import org.http4k.core.then
import org.http4k.filter.ServerFilters
import java.nio.ByteBuffer

/**
 * Exposed to allow for insertion into a customised Netty server instance
 */
class Http4kChannelHandler(handler: HttpHandler) : ChannelInboundHandlerAdapter() {

    private val safeHandler = ServerFilters.CatchAll().then(handler)

    override fun channelRead(ctx: ChannelHandlerContext, request: Any) {
        if (request is DefaultHttpRequest) {
            if (is100ContinueExpected(request)) {
                ctx.write(DefaultFullHttpResponse(HTTP_1_1, CONTINUE))
            }

            val res = safeHandler(request.asRequest()).asNettyResponse()

            if (isKeepAlive(request)) {
                res.headers().set(CONNECTION, KEEP_ALIVE)
                ctx.write(res)
            } else {
                ctx.write(res).addListener(ChannelFutureListener.CLOSE)
            }
        }
    }

    override fun channelReadComplete(ctx: ChannelHandlerContext) {
        ctx.flush()
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        ctx.close()
    }

    private fun Response.asNettyResponse(): DefaultFullHttpResponse {
        val res = DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus(status.code, status.description),
            body.let { (payload) -> wrappedBuffer(payload) }
        )
        headers.forEach { (key, value) -> res.headers().set(key, value) }
        res.headers().set(CONTENT_LENGTH, res.content().readableBytes())
        return res
    }

    private fun DefaultHttpRequest.asRequest(): Request =
        headers().fold(Request(Method.valueOf(method().name()), Uri.Companion.of(uri()))) {
            memo, next ->
            memo.header(next.key, next.value)
        }.body(
            when (this) {
                is DefaultFullHttpRequest -> Body(ByteBuffer.wrap(this.content().array()))
                else -> Body.EMPTY
            }
        )
}

data class Netty(val port: Int = 8000) : ServerConfig {
    override fun toServer(handler: HttpHandler): Http4kServer {
        return object : Http4kServer {
            private val masterGroup = NioEventLoopGroup()
            private val workerGroup = NioEventLoopGroup()
            private var closeFuture: ChannelFuture? = null

            override fun start(): Http4kServer {
                val bootstrap = ServerBootstrap()
                bootstrap.group(masterGroup, workerGroup)
                    .channel(NioServerSocketChannel::class.java)
                    .childHandler(object : ChannelInitializer<SocketChannel>() {
                        public override fun initChannel(ch: SocketChannel) {
                            ch.pipeline().addLast("codec", HttpServerCodec())
                            ch.pipeline().addLast("handler", Http4kChannelHandler(handler))
                        }
                    })
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)

                closeFuture = bootstrap.bind(port).sync().channel().closeFuture()
                return this
            }

            override fun block(): Http4kServer {
                closeFuture?.sync()
                return this
            }

            override fun stop() {
                // FIXME is this correct??!
                closeFuture?.cancel(false)
                workerGroup.shutdownGracefully()
                masterGroup.shutdownGracefully()
            }
        }
    }
}