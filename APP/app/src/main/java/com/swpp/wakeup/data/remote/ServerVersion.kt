package com.swpp.wakeup.data.remote

/**
 * 앱과 서버의 버전을 비교한다.
 *
 * ## 왜 필요한가
 *
 * 배포가 뒤처지면 새 엔드포인트가 404 로 돌아오고, 앱은 그것을 **평범한 실패와
 * 구별하지 못한다.** 화면에는 "불러오지 못했습니다" 만 뜬다. 그 증상은 앱 버그와
 * 똑같이 보여서, 실제로 팀이 그 방향으로 시간을 썼다 — 새로 추가한
 * `/api/events/import` 가 404 였고 코드가 틀린 것처럼 보였지만 코드는 맞았고
 * 서버가 옛것이었다.
 *
 * `/api/health` 는 처음부터 `version` 을 돌려주고 있었다. 앱이 그 값을 버리고
 * 있었을 뿐이다. 받아서 비교하면 "배포가 안 됐다" 를 그 자리에서 말할 수 있다.
 *
 * ## 왜 판정을 따로 떼는가
 *
 * 버전 비교는 틀리기 쉽다. 문자열로 비교하면 `"0.10.0" < "0.9.0"` 이 되고, 자리
 * 수가 다르면(`0.3` vs `0.3.0`) 다른 버전으로 본다. 그 실수는 **틀린 경고**를
 * 만들어 신뢰를 깎는다 — 한 번 거짓 경고를 보면 다음 진짜 경고도 무시된다.
 * 그래서 네트워크와 분리해 단위 테스트로 고정한다.
 */
object ServerVersion {

    /**
     * 서버가 앱보다 **오래됐는가.**
     *
     * 판정할 수 없으면 false 다. 모르는 것을 경고하지 않는다 — 형식이 예상과
     * 다른 버전 문자열(개발 빌드, 커밋 해시)에 대고 "배포하세요" 라고 말하면
     * 그 경고는 소음이 된다.
     *
     * 서버가 **더 새로운** 경우도 false 다. 그것은 정상 배포 순서다(서버를 먼저
     * 올리고 앱을 배포한다). 알려야 할 것은 그 반대뿐이다.
     */
    fun isServerBehind(appVersion: String?, serverVersion: String?): Boolean {
        val app = parse(appVersion) ?: return false
        val server = parse(serverVersion) ?: return false
        return compare(server, app) < 0
    }

    /**
     * `"0.3.0"` → `[0, 3, 0]`. 읽을 수 없으면 null.
     *
     * 뒤에 붙은 꼬리표는 떼고 본다(`"0.3.0-rc1"` → `[0, 3, 0]`). 숫자 부분이
     * 같으면 같은 기능 집합이라고 보는 것이 이 판정의 목적에 맞다.
     */
    fun parse(raw: String?): List<Int>? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val core = trimmed.substringBefore('-').substringBefore('+')
        val parts = core.split('.')
        if (parts.isEmpty() || parts.size > 4) return null

        val numbers = parts.map { part ->
            part.trim().toIntOrNull()?.takeIf { it >= 0 } ?: return null
        }
        return numbers
    }

    /**
     * 자리 수가 달라도 같게 본다. `0.3` 과 `0.3.0` 은 같은 버전이다.
     *
     * 짧은 쪽을 0 으로 채운다 — 채우지 않고 길이로 먼저 비교하면 `0.3` 이
     * `0.3.0` 보다 오래된 것이 되어 없는 문제를 만든다.
     */
    private fun compare(left: List<Int>, right: List<Int>): Int {
        val size = maxOf(left.size, right.size)
        for (index in 0 until size) {
            val a = left.getOrElse(index) { 0 }
            val b = right.getOrElse(index) { 0 }
            if (a != b) return a.compareTo(b)
        }
        return 0
    }

    /**
     * 사람에게 보여 줄 한 줄. **무엇을 해야 하는지까지 적는다.**
     *
     * "버전이 다릅니다" 만으로는 아무도 행동하지 않는다. 고치는 곳을 같이 적어야
     * 다음 사람이 대시보드를 찾아 헤매지 않는다.
     */
    fun behindMessage(appVersion: String?, serverVersion: String?): String =
        "서버가 구버전입니다 (서버 ${serverVersion ?: "알 수 없음"} · 앱 ${appVersion ?: "알 수 없음"}). " +
            "새 기능이 404 로 실패합니다. Render 대시보드 > Manual Deploy > " +
            "Deploy latest commit 으로 배포하세요."
}
