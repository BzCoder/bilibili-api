package com.hiczp.bilibili.api.live.websocket

import com.github.salomonbrys.kotson.obj
import com.google.gson.JsonObject
import com.hiczp.bilibili.api.BilibiliClient
import com.hiczp.bilibili.api.jsonParser
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.features.websocket.WebSockets
import io.ktor.client.features.websocket.wss
import io.ktor.http.cio.websocket.CloseReason
import io.ktor.http.cio.websocket.WebSocketSession
import io.ktor.util.InternalAPI
import io.ktor.util.KtorExperimentalAPI
import io.ktor.util.decodeString
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.io.core.Closeable
import kotlinx.io.errors.IOException

/**
 * 直播客户端
 * 注意该类是有状态的
 *
 * @param maybeShortRoomId 可能为短房间号的房间号
 * @param fetchRoomId 是否在连接前先获取房间号(长号)
 * @param fetchDanmakuConfig 是否在连接前先获取弹幕推送服务器地址
 * @param doEntryRoomAction 是否产生直播间观看历史记录
 * @param sendUserOnlineHeart 是否发送 rest 心跳包, 这会增加观看直播的时长, 用于服务端统计(与弹幕推送无关)
 * @param onConnect 回调函数, 连接成功时触发
 * @param onPopularityPacket 回调函数, 接收到人气值数据包时触发
 * @param onCommandPacket 回调函数, 接收到 Command 数据包时触发
 * @param onClose 回调函数, 连接断开时触发
 */
@Suppress("CanBeParameter")
class LiveClient(
        private val bilibiliClient: BilibiliClient,
        private val maybeShortRoomId: Long,
        private val fetchRoomId: Boolean = true,
        private val fetchDanmakuConfig: Boolean = true,
        private val doEntryRoomAction: Boolean = false,
        private val sendUserOnlineHeart: Boolean = false,
        private val onConnect: (LiveClient) -> Unit,
        private val onPopularityPacket: (LiveClient, Int) -> Unit,
        private val onCommandPacket: (LiveClient, JsonObject) -> Unit,
        private val onClose: (LiveClient, CloseReason?) -> Unit
) : Closeable {
    private val liveAPI = bilibiliClient.liveAPI
    private var websocketSession: WebSocketSession? = null

    var roomId = maybeShortRoomId
        private set

    /**
     * 开启连接
     * 注意此方法将 suspend 所在协程直到连接关闭
     */
    @UseExperimental(KtorExperimentalAPI::class, ObsoleteCoroutinesApi::class, InternalAPI::class)
    suspend fun start() {
        //得到原始房间号和主播的用户ID
        var anchorUserId = 0L
        if (fetchRoomId) {
            liveAPI.mobileRoomInit(maybeShortRoomId).await().data.also {
                roomId = it.roomId
                anchorUserId = it.uid
            }
        }

        //获得 wss 地址和端口(推荐服务器)
        @Suppress("SpellCheckingInspection")
        var host = "broadcastlv.chat.bilibili.com"
        var port = 443
        if (fetchDanmakuConfig) {
            liveAPI.getDanmakuConfig(roomId).await().data.also { data ->
                host = data.host
                data.hostServerList.find { it.host == host }?.wssPort?.also {
                    port = it
                }
            }
        }

        //产生历史记录
        @Suppress("DeferredResultUnused")
        if (doEntryRoomAction && bilibiliClient.isLogin) liveAPI.roomEntryAction(roomId)

        //开启 websocket
        HttpClient(CIO).config { install(WebSockets) }.wss(host = host, port = port, path = "/sub") {
            websocketSession = this
            pingIntervalMillis = -1

            //发送进房数据包
            send(PresetPacket.enterRoomPacket(anchorUserId, roomId))
            if (incoming.receive().toPackets()[0].packetType == PacketType.ENTER_ROOM_RESPONSE) {
                try {
                    onConnect(this@LiveClient)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                //impossible
                close(IOException("Receive unreadable server response"))
            }

            //发送 rest 心跳包
            //五分钟一次
            val restHeartBeatJob = if (sendUserOnlineHeart && bilibiliClient.isLogin) {
                launch {
                    val scale = bilibiliClient.billingClientProperties.scale
                    while (true) {
                        @Suppress("DeferredResultUnused")
                        liveAPI.userOnlineHeart(roomId, scale)
                        delay(300_000)
                    }
                }
            } else {
                null
            }

            //发送 websocket 心跳包
            //30 秒一次
            val websocketHeartBeatJob = launch {
                while (true) {
                    send(PresetPacket.heartbeatPacket())
                    delay(30_000)
                }
            }

            //如果被 cancel, 那么这里将抛出异常
            try {
                incoming.consumeEach { frame ->
                    frame.toPackets().forEach {
                        try {
                            @Suppress("NON_EXHAUSTIVE_WHEN")
                            when (it.packetType) {
                                PacketType.POPULARITY -> onPopularityPacket(
                                        this@LiveClient,
                                        it.content.int
                                )
                                PacketType.COMMAND -> onCommandPacket(
                                        this@LiveClient,
                                        jsonParser.parse(it.content.decodeString()).obj
                                )
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            } finally {
                //无论是连接关闭还是抛出异常, 都要清理掉两个子任务
                restHeartBeatJob?.cancel()
                websocketHeartBeatJob.cancel()
            }

            //如果上面抛出了异常, 那么这里就不会被执行
            launch {
                val closeReason = closeReason.await()
                try {
                    onClose(this@LiveClient, closeReason)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    /**
     * 关闭连接
     */
    override fun close() {
        websocketSession?.run {
            websocketSession = null
            //client 不能使用 close(), 因为 WebsocketSession 本体执行完毕时会自动执行一次 close(), 这会导致多次关闭
            incoming.cancel()
        }
    }

    /**
     * 发送弹幕
     */
    fun sendMessage(message: String) =
            liveAPI.sendMessage(cid = roomId, mid = bilibiliClient.userId ?: 0, message = message)
}
