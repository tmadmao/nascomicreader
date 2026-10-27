package com.nascomic.reader.settings

import android.app.Activity
import android.content.pm.ActivityInfo

/**
 * 把「屏幕方向」偏好落到当前 Activity 上。
 *
 * 用 requestedOrientation 而不是在每个 Activity 里写死，是为了改设置后回到界面就生效——
 * 三个页面都在 onResume 时调一次。
 */
fun Activity.applyOrientation(prefs: AppPrefs) {
    requestedOrientation = when (AppPrefs.Orientation.of(prefs.orientation)) {
        AppPrefs.Orientation.SYSTEM -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        AppPrefs.Orientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        AppPrefs.Orientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }
}
