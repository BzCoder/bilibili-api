package com.hiczp.bilibili.rest

import com.hiczp.bilibili.rest.ktor.*
import com.hiczp.bilibili.rest.service.app.AppService
import com.hiczp.bilibili.rest.service.live.LiveService
import com.hiczp.bilibili.rest.service.orThrow
import com.hiczp.bilibili.rest.service.passport.*
import com.hiczp.bilibili.rest.utils.isLazyInitialized
import com.hiczp.caeruleum.create
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.features.UserAgent
import io.ktor.client.features.defaultRequest
import io.ktor.client.features.json.GsonSerializer
import io.ktor.client.features.json.JsonFeature
import io.ktor.client.request.headers
import io.ktor.http.ContentType
import io.ktor.util.KtorExperimentalAPI
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.getAndUpdate
import kotlinx.atomicfu.updateAndGet
import mu.KotlinLogging
import java.io.Closeable
import java.time.Instant

private val logger = KotlinLogging.logger { }

@Suppress("MemberVisibilityCanBePrivate", "unused")
class BilibiliClient(credential: Credential? = null) : Closeable {
    private val initTime = Instant.now().epochSecond

    val credential = atomic(credential)
    val loggedIn get() = credential.value != null

    /**
     * Login
     *
     * @return new credential
     */
    @Throws(LoginException::class)
    suspend fun login(
            username: String, password: String,
            //极验
            challenge: String? = null,
            secCode: String? = null,
            validate: String? = null
    ) = credential.updateAndGet {
        if (it != null) logger.warn { "Override credential: ${it.userId}" }
        val (hash, key) = PassportService.getKey().let { response ->
            response.hash to response.key.toDER()
        }
        val cipheredPassword = (hash + password).rsaEncrypt(key)
        PassportService.login(
                username, cipheredPassword,
                challenge, secCode, validate
        ).orThrow(::LoginException).toCredential().also {
            logger.debug { "Logged with userId ${it.userId}" }
        }
    }!!

    /**
     * Revoke credential
     *
     * @return return old credential, null if not logged in
     */
    @Throws(RevokeException::class)
    suspend fun revoke() = credential.getAndUpdate { oldCredential ->
        if (oldCredential != null) {
            PassportService.revoke(oldCredential.accessToken, oldCredential.cookieMap()).orThrow(::RevokeException)
            logger.debug { "Token revoked: ${oldCredential.userId}" }
        }
        null
    }

    /**
     * Get OAuth2 info
     *
     * @return null if not logged in
     */
    suspend fun oauth2Info() = credential.value?.let {
        PassportService.info(it.accessToken, it.cookieMap())
    }

    @UseExperimental(KtorExperimentalAPI::class)
    @Suppress("SpellCheckingInspection")
    private val commonClient by lazy {
        HttpClient(CIO) {
            install(UserAgent) {
                agent = BilibiliClientInherent.userAgent
            }
            defaultRequest {
                headers {
                    with(BilibiliClientInherent) {
                        appendMissing(
                                "Display-ID",
                                this@BilibiliClient.credential.value?.let {
                                    "${it.userId}-$initTime"
                                } ?: "$buvid-$initTime"
                        )
                        appendMissing("Display-ID", "$buvid-$initTime")
                        appendMissing("Buvid", buvid)
                        appendMissing("Device-ID", hardwareId)
                        appendMissing("env", env)
                        appendMissing("APP-KEY", platform)
                    }
                }
            }
            commonParams {
                this@BilibiliClient.credential.value?.let {
                    appendMissing("access_key", it.accessToken)
                }
                with(BilibiliClientInherent) {
                    appendMissing("actionKey", actionKey)
                    appendMissing("build", build)
                    appendMissing("buvid", buvid)
                    appendMissing("channel", channel)
                    appendMissing("device", platform)
                    appendMissing("device_name", mobileModel)
                    appendMissing("mobi_app", platform)
                    appendMissing("platform", platform)
                    appendMissing("statistics", statistics)
                    appendMissing("ts", Instant.now().epochSecond.toString())
                }
            }
            install(JsonFeature) {
                serializer = GsonSerializer()
                acceptContentTypes = listOf(
                        ContentType.Application.Json,
                        ContentType("text", "json")
                )
            }
            logging { }
        }
    }

    val appService by lazy { commonClient.create<AppService>() }
    val liveService by lazy { commonClient.create<LiveService>() }

    override fun close() {
        if (::commonClient.isLazyInitialized()) {
            commonClient.close()
        }
    }
}
