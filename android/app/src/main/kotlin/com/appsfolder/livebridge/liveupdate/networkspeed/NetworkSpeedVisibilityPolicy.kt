package com.kakao.taxi.liveupdate.networkspeed

internal object NetworkSpeedVisibilityPolicy {
    fun allowPromotion(hideWhenLocked: Boolean, screenOff: Boolean, keyguardLocked: Boolean): Boolean =
        !hideWhenLocked || (!screenOff && !keyguardLocked)
}
