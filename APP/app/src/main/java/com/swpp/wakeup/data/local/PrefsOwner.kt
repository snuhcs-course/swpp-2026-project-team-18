package com.swpp.wakeup.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit

/**
 * SharedPreferences 저장소에 **소유자**를 붙인다.
 *
 * ## 무엇이 새고 있었나
 *
 * Room 캐시([JitDatabase])는 모든 행에 소유자 칸이 있고 **모든 조회가 그 값을
 * 요구한다.** 지우는 호출을 한 곳에서 빠뜨려도 앞 사용자의 데이터가 보이지
 * 않게 하려는 설계다. 그런데 뒤에 추가한 SharedPreferences 저장소들은 그
 * 규칙 밖에 있었다.
 *
 * 실제 경로는 이렇다. A 가 로그아웃하고 같은 기기에서 B 가 로그인하면
 * `HomeViewModel.init` 이 `reloadMorning()` 을 부르고, 그 자리에서 **B 가 A 의
 * 아침 기록을 본다** — 블록 이름("샤워", "약 먹기")과 진행 상황까지. 로그아웃이
 * Room 캐시는 지웠지만 이 파일들은 건드리지 않았다.
 *
 * ## 판정 규칙
 *
 * ```
 * 저장된 소유자 == 지금 로그인한 계정   → 쓴다
 * 저장된 소유자 != 지금 로그인한 계정   → 없는 것으로 다루고 지운다
 * 지금 로그인한 계정을 모른다           → 막지 않는다
 * 저장된 소유자가 없다(구버전 데이터)    → 막지 않는다
 * ```
 *
 * **마지막 두 줄이 중요하다.** 교차 노출은 "다른 계정이 로그인해 있다" 는 것이
 * 확인될 때만 일어난다. 로그인 정보가 없다고 막으면 두 가지가 깨진다.
 *
 * - refresh 토큰이 만료되면 [com.swpp.wakeup.data.remote.AuthInterceptor] 가
 *   토큰을 지운다. 그 상태에서 재부팅하면 [com.swpp.wakeup.alarm.BootReceiver]
 *   가 알람을 다시 등록해야 하는데, 막으면 **그 아침 알람이 울리지 않는다.**
 *   세션이 만료됐어도 9시 수업은 그대로 있다.
 * - 구버전이 저장한 데이터에는 소유자 칸이 없다. 막으면 업그레이드 직후
 *   알람이 사라진다 — [withDiskDefaults] 에서 고친 것과 같은 사고다.
 *
 * 남는 구멍은 "구버전 데이터가 계정 전환을 넘어 한 번 보일 수 있다" 뿐이고,
 * 그것은 로그아웃·로그인 양쪽에서 지우는 것으로 덮는다. 아침 기록은 6시간 뒤
 * 만료되기도 한다.
 */
private const val KEY_OWNER = "_owner"

private const val TAG = "PrefsOwner"

/** 계정 이름 정규화. 대소문자·공백 차이로 남의 것이 되면 안 된다. */
internal fun normalizeOwner(raw: String?): String? =
    raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

/** 지금 로그인한 계정. */
private fun currentOwner(context: Context): String? =
    normalizeOwner(TokenStore(context.applicationContext).email)

/**
 * 판정 규칙 본체. **Context 없이 검사할 수 있도록 분리했다.**
 *
 * 네 경우 중 셋이 "막지 않는다" 이고, 그 셋이 각각 다른 이유로 그렇다. 규칙을
 * 확장 함수 안에 두면 `Context` 없이 검사할 수 없어 그 이유를 테스트로 고정할 수
 * 없다. `PrefsOwnerRuleTest` 가 네 경우를 전부 본다.
 *
 * @param stored 저장소에 적힌 소유자. 구버전 데이터면 null.
 * @param current 지금 로그인한 계정. 로그아웃·세션 만료면 null.
 */
internal fun isForeignOwner(stored: String?, current: String?): Boolean {
    val normalizedCurrent = normalizeOwner(current) ?: return false
    val normalizedStored = normalizeOwner(stored) ?: return false
    return normalizedStored != normalizedCurrent
}

/**
 * 이 저장소가 **다른 계정의 것으로 확인되는가.**
 *
 * 확인되지 않으면 false 다. 모호한 경우를 막지 않는 이유는 위 문서에 있다.
 */
internal fun SharedPreferences.isForeignOwner(context: Context): Boolean {
    val foreign = isForeignOwner(getString(KEY_OWNER, null), currentOwner(context))
    if (foreign) Log.w(TAG, "다른 계정의 저장소다. 없는 것으로 다룬다")
    return foreign
}

/**
 * 지금 로그인한 계정을 소유자로 적는다. **쓰기마다 부른다.**
 *
 * 로그아웃 상태에서는 적지 않는다 — 소유자를 모르는 채 아무 값이나 박으면
 * 다음 로그인이 전부 남의 것으로 보인다.
 */
internal fun SharedPreferences.stampOwner(context: Context) {
    val owner = currentOwner(context) ?: return
    if (getString(KEY_OWNER, null) != owner) {
        edit { putString(KEY_OWNER, owner) }
    }
}

/**
 * 다른 계정의 저장소면 비우고 true 를 돌려준다.
 *
 * 남의 데이터를 들고 있을 이유가 없다. 큐에 쌓인 관측은 **그 사용자의 토큰이
 * 없으면 영원히 올릴 수 없으므로** 보관해도 얻는 것이 없고 기기에 남는 위험만
 * 커진다.
 */
internal fun SharedPreferences.discardIfForeign(context: Context): Boolean {
    if (!isForeignOwner(context)) return false
    edit { clear() }
    return true
}
