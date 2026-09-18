package com.swpp.wakeup.sensing

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * 위치 권한 확인.
 *
 * **배경 위치를 강제로 요청하지 않는다.** 추적은 알람 화면(앱이 보이는
 * 상태)에서 시작하므로 포그라운드 위치 권한 + 포그라운드 서비스만으로
 * 동작한다. 배경 위치 대화상자는 "항상 허용" 을 요구해 위협적이고, 없어도
 * 핵심 흐름이 돌아간다. 필요한 사용자만 설정에서 켜면 된다.
 */
object LocationPermissions {

    /** 런타임에 요청할 권한. 정밀 위치가 거부되면 대략 위치라도 받는다. */
    val REQUESTED = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    /**
     * 판별을 시작할 수 있는가.
     *
     * 정밀 위치를 요구한다. 대략 위치(셀 기반)는 오차가 수백 m 라서
     * [TripGeofence.MAX_ACCURACY_M] 필터를 통과하는 fix 가 거의 없다 —
     * 권한만 있고 판정은 영영 안 나는 상태가 된다.
     */
    fun granted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    /** 대략 위치만 있는 상태. 화면이 "정밀 위치가 필요하다" 고 안내할 때 쓴다. */
    fun coarseOnly(context: Context): Boolean =
        !granted(context) &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
}
