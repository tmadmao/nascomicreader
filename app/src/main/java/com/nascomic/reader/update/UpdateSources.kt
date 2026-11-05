package com.nascomic.reader.update

import com.nascomic.reader.settings.AppPrefs

/**
 * 清单地址。换托管平台只改这一个文件，下载与安装的代码不用动。
 *
 * 清单放在仓库根的 `update/` 下，单独一条路径：不跟源码混在一起，主分支多一个目录
 * 也不影响别的文件。仓库必须是公开的——公开仓库的 raw 小文件可以匿名读，超过 5MB
 * 才要求登录，所以清单必须保持小体积；安装包一律走 Release 附件，那条路不受这个限制。
 *
 * 以后要加备源，往 [DEFAULTS] 里加一项就行：按顺序试，前一个拿不到才轮到下一个。
 * 现在只放 Gitee 一条——挂一个没人同步的镜像地址，只会让失败时多等十秒。
 */
object UpdateSources {

    const val GITEE =
        "https://gitee.com/tmadmao/nas-comic-reader/raw/master/update/update.json"

    /** 按顺序试，前一个拿不到才轮到下一个 */
    private val DEFAULTS = listOf(GITEE)

    /**
     * 用户在设置里填了地址就只认那一个：平台整体失效时，这条是唯一还能自救的路，
     * 所以不能和内置源混着试——混着试用户就看不出自己填的到底通没通。
     */
    fun resolve(prefs: AppPrefs): List<String> {
        val custom = prefs.updateManifestUrl.trim()
        return if (custom.isEmpty()) DEFAULTS else listOf(custom)
    }
}
