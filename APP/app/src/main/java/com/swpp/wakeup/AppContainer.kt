package com.swpp.wakeup

import android.content.Context
import com.swpp.wakeup.data.local.OfflineCache
import com.swpp.wakeup.data.local.TokenStore
import com.swpp.wakeup.data.repository.AccountActions
import com.swpp.wakeup.data.repository.AccountActionsRepository
import com.swpp.wakeup.data.repository.AuthRepository
import com.swpp.wakeup.data.repository.EventRepository
import com.swpp.wakeup.data.repository.EventTripPlanRepository
import com.swpp.wakeup.data.repository.ReportRepository
import com.swpp.wakeup.data.repository.RoutineRepository
import com.swpp.wakeup.data.repository.SavedPlacesRepository
import com.swpp.wakeup.data.repository.TripPlanRepository
import com.swpp.wakeup.data.repository.AlarmDecisionRepository
import com.swpp.wakeup.data.repository.EventAlarmDecisionRepository
import com.swpp.wakeup.data.repository.MeRepository
import com.swpp.wakeup.data.repository.AuthMeRepository
import com.swpp.wakeup.data.repository.EventEditorRepository
import com.swpp.wakeup.data.repository.EventEditorDataRepository

/** 수동 DI 진입점. 앱 전역 토큰 저장소와 로그인 화면별 의존성 수명을 분리한다. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val tokenStore = TokenStore(appContext)

    /** 로그인 당시 소유자를 고정한 새 캐시를 만든다. 이전 계정의 저장소를 재사용하지 않는다. */
    fun homeRepositories(): HomeRepositories {
        val cache = OfflineCache(appContext, tokenStore.email)
        val events = EventRepository(cache = cache)
        val auth = AuthRepository(tokenStore = tokenStore)
        val accounts = AccountActionsRepository()
        return HomeRepositories(
            cache = cache,
            events = events,
            routines = RoutineRepository(cache = cache),
            auth = auth,
            reports = ReportRepository(),
            savedPlaces = SavedPlacesRepository(),
            trips = EventTripPlanRepository(events),
            accounts = accounts,
            alarmDecisions = EventAlarmDecisionRepository(events),
            me = AuthMeRepository(auth),
            editor = EventEditorDataRepository(events, accounts),
        )
    }

    class HomeRepositories internal constructor(
        val cache: OfflineCache,
        val events: EventRepository,
        val routines: RoutineRepository,
        val auth: AuthRepository,
        val reports: ReportRepository,
        val savedPlaces: SavedPlacesRepository,
        val trips: TripPlanRepository,
        val accounts: AccountActions,
        val alarmDecisions: AlarmDecisionRepository,
        val me: MeRepository,
        val editor: EventEditorRepository,
    )
}
