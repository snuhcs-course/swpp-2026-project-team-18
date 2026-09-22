package com.swpp.wakeup.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 저장소 소유자 판정 규칙.
 *
 * 네 경우 중 **셋이 "막지 않는다"** 이고 그 셋이 각각 다른 이유로 그렇다. 규칙을
 * 조이는 쪽으로 고치면 알람이 울리지 않고, 느슨하게 고치면 남의 기록이 보인다.
 * 양쪽 실수를 다 잡으려고 네 경우를 전부 적어 둔다.
 */
class PrefsOwnerRuleTest {

    @Test
    fun `소유자가 다르면 막는다`() {
        // 이것이 유일하게 막는 경우다. A 로그아웃 → B 로그인 경로.
        assertTrue(isForeignOwner(stored = "a@snu.ac.kr", current = "b@snu.ac.kr"))
    }

    @Test
    fun `소유자가 같으면 쓴다`() {
        assertFalse(isForeignOwner(stored = "a@snu.ac.kr", current = "a@snu.ac.kr"))
    }

    @Test
    fun `로그인 정보가 없으면 막지 않는다`() {
        // refresh 토큰이 만료되면 AuthInterceptor 가 토큰을 지운다. 그 상태에서
        // 재부팅하면 BootReceiver 가 알람을 다시 등록해야 한다. 여기서 막으면
        // **세션이 끊긴 사용자의 아침 알람이 울리지 않는다.** 9시 수업은 그대로다.
        assertFalse(isForeignOwner(stored = "a@snu.ac.kr", current = null))
        assertFalse(isForeignOwner(stored = "a@snu.ac.kr", current = ""))
        assertFalse(isForeignOwner(stored = "a@snu.ac.kr", current = "   "))
    }

    @Test
    fun `소유자 표시가 없는 구버전 데이터는 막지 않는다`() {
        // 구버전이 저장한 JSON 에는 소유자 칸이 없다. 막으면 업그레이드 직후
        // 알람이 사라진다 — withDiskDefaults 에서 고친 것과 같은 종류의 사고다.
        assertFalse(isForeignOwner(stored = null, current = "b@snu.ac.kr"))
        assertFalse(isForeignOwner(stored = "", current = "b@snu.ac.kr"))
    }

    @Test
    fun `양쪽 다 모르면 막지 않는다`() {
        assertFalse(isForeignOwner(stored = null, current = null))
    }

    // --- 정규화 -----------------------------------------------------------

    @Test
    fun `대소문자 차이로 남의 것이 되지 않는다`() {
        // 서버가 이메일을 소문자로 정규화하지 않는 경로가 있으면 같은 사람이
        // 로그인할 때마다 저장소가 초기화된다. 진행 중인 아침 기록이 사라진다.
        assertFalse(isForeignOwner(stored = "A@SNU.ac.kr", current = "a@snu.ac.kr"))
    }

    @Test
    fun `앞뒤 공백 차이로 남의 것이 되지 않는다`() {
        assertFalse(isForeignOwner(stored = " a@snu.ac.kr ", current = "a@snu.ac.kr"))
    }

    @Test
    fun `정규화는 빈 값을 없는 것으로 다룬다`() {
        assertNull(normalizeOwner(null))
        assertNull(normalizeOwner(""))
        assertNull(normalizeOwner("  "))
        assertEquals("a@snu.ac.kr", normalizeOwner("  A@SNU.AC.KR  "))
    }

    @Test
    fun `다른 계정 판정은 정규화 뒤에 한다`() {
        // 같은 사람의 다른 표기는 같게, 정말 다른 계정은 다르게.
        assertFalse(isForeignOwner(stored = "  Kim@SNU.ac.kr", current = "kim@snu.ac.kr  "))
        assertTrue(isForeignOwner(stored = "kim@snu.ac.kr", current = "kim2@snu.ac.kr"))
    }
}
