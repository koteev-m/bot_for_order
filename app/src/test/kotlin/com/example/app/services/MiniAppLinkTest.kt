package com.example.app.services

import com.example.bots.startapp.DirectLink
import com.example.bots.startapp.MiniAppMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class MiniAppLinkTest : StringSpec({
    "link builder uses opaque token and hides internal params" {
        val itemId = "item-123"
        val variantId = "variant-456"
        val price = "99900"
        val url = DirectLink.forMiniApp(
            botUsername = "ShopBot",
            appName = "buyer",
            token = "opaque-token",
            mode = MiniAppMode.COMPACT
        )

        url.shouldContain("startapp=")
        url.shouldNotContain(itemId)
        url.shouldNotContain(variantId)
        url.shouldNotContain(price)
    }

    "link builder adds compact mode only when requested" {
        val compactUrl = DirectLink.forMiniApp(
            botUsername = "ShopBot",
            appName = "buyer",
            token = "opaque-token",
            mode = MiniAppMode.COMPACT
        )
        val defaultUrl = DirectLink.forMiniApp(
            botUsername = "ShopBot",
            appName = "buyer",
            token = "opaque-token",
            mode = MiniAppMode.DEFAULT
        )

        compactUrl.shouldContain("mode=compact")
        defaultUrl.shouldNotContain("mode=")
    }
})
