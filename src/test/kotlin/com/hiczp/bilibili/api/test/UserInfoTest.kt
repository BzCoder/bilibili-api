package com.hiczp.bilibili.api.test

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class UserInfoTest {
    @Test
    fun info() {
        runBlocking {
            bilibiliClient.appAPI.myInfo().await()
        }
    }
}
