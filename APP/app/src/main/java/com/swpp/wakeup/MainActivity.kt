package com.swpp.wakeup

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.swpp.wakeup.calendar.DeviceCalendar
import com.swpp.wakeup.data.remote.ApiClient
import com.swpp.wakeup.domain.model.ArrivalOutlook
import com.swpp.wakeup.domain.model.RouteProgress
import com.swpp.wakeup.domain.model.TripEta
import com.swpp.wakeup.domain.model.TripStage
import com.swpp.wakeup.ui.alarm.AlarmDecisionScreen
import com.swpp.wakeup.ui.alarm.arrivalClockLabel
import com.swpp.wakeup.ui.alarm.freshnessLabel
import com.swpp.wakeup.ui.calendar.CalendarHelpActions
import com.swpp.wakeup.ui.calendar.CalendarImportScreen
import com.swpp.wakeup.ui.calendar.CalendarLinkScreen
import com.swpp.wakeup.ui.common.durationLabel
import com.swpp.wakeup.ui.alarm.RiskChoiceScreen
import com.swpp.wakeup.ui.auth.LoginActivity
import com.swpp.wakeup.ui.events.AddEventScreen
import com.swpp.wakeup.ui.events.HomeSetupScreen
import com.swpp.wakeup.ui.events.PrepOnboardingScreen
import com.swpp.wakeup.ui.events.RouteChoiceScreen
import com.swpp.wakeup.ui.home.HomeScreen
import com.swpp.wakeup.ui.home.HomeViewModel
import com.swpp.wakeup.ui.morning.MorningProgressScreen
import com.swpp.wakeup.sensing.LocationPermissions
import com.swpp.wakeup.sensing.TripGeofence
import com.swpp.wakeup.ui.nav.AppRoute
import com.swpp.wakeup.ui.places.MapPickScreen
import com.swpp.wakeup.ui.report.ReportDayScreen
import com.swpp.wakeup.ui.report.WeeklyReportScreen
import com.swpp.wakeup.ui.settings.SettingsScreen
import com.swpp.wakeup.ui.routines.BlockDraftSheet
import com.swpp.wakeup.data.local.SessionState
import com.swpp.wakeup.ui.routines.RoutineEditorScreen
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitTheme
import kotlinx.coroutines.launch

/**
 * 로그인 후 본 화면. Figma ③ 홈을 시작점으로 ④⑤⑫ 를 호스팅한다.
 *
 * 화면 이동은 [HomeViewModel] 의 스택으로 관리하고 [AnimatedContent] 로
 * 전환한다. 파고들 때는 오른쪽에서 밀려 들어오고 돌아올 때는 반대다.
 */
class MainActivity : ComponentActivity() {

    private var openMorning by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 액티비티 전환을 부드럽게. minSdk 34 이므로 바로 쓸 수 있다.
        overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, R.anim.activity_enter, R.anim.activity_exit)
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, R.anim.activity_enter, R.anim.activity_exit)

        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        // 로그인 직후에만 환영 문구를 띄운다. 앱을 다시 열 때는 띄우지 않는다.
        val welcomeNickname = intent.getStringExtra(EXTRA_WELCOME_NICKNAME)
        // 알람을 해제하고 넘어온 경우. 아침 기록 화면으로 바로 들어간다.
        openMorning = intent.getBooleanExtra(EXTRA_OPEN_MORNING, false)

        setContent {
            JitTheme {
                MainHost(
                    welcomeNickname = welcomeNickname,
                    openMorning = openMorning,
                    onMorningOpened = {
                        openMorning = false
                        intent.removeExtra(EXTRA_OPEN_MORNING)
                    },
                    onLoggedOut = ::backToLogin,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 알람 해제는 CLEAR_TOP + SINGLE_TOP으로 이미 열린 홈을 재사용한다.
        openMorning = intent.getBooleanExtra(EXTRA_OPEN_MORNING, false)
    }

    private fun backToLogin() {
        startActivity(
            Intent(this, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finish()
    }

    companion object {
        /** 로그인 직후 환영 문구에 쓸 닉네임. 없으면 문구를 띄우지 않는다. */
        const val EXTRA_WELCOME_NICKNAME = "welcome_nickname"

        /** 알람 해제 후 아침 기록 화면으로 바로 들어갈지. */
        const val EXTRA_OPEN_MORNING = "open_morning"

        /**
         * 알람 해제 직후 아침 기록으로 들어가는 인텐트.
         *
         * `CLEAR_TOP` 과 `SINGLE_TOP` 을 함께 준다. 앱이 이미 떠 있으면 새
         * 인스턴스를 만들지 않고 기존 태스크를 앞으로 가져온다 — 알람 화면은
         * 별도 태스크라서 여기서 스택을 쌓으면 뒤로 가기가 이상해진다.
         */
        fun morningIntent(context: android.content.Context, eventId: Long): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_MORNING, true)
                // eventId 는 지금 쓰지 않는다. 세션이 디스크에 하나뿐이라
                // 화면이 그것을 읽으면 된다. 로그에서 짝을 맞추려고 남긴다.
                .putExtra("morning_event_id", eventId)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

// Partly AI-generated (Claude)
@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun MainHost(
    welcomeNickname: String?,
    openMorning: Boolean,
    onMorningOpened: () -> Unit,
    onLoggedOut: () -> Unit,
) {
    val viewModel: HomeViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val nav by viewModel.nav.collectAsStateWithLifecycle()
    val plan by viewModel.plan.collectAsStateWithLifecycle()
    val risk by viewModel.risk.collectAsStateWithLifecycle()
    val routeMap by viewModel.routeMap.collectAsStateWithLifecycle()
    // 추적 서비스가 내보내는 실시간 위치. 추적 중이 아니면 null 이다.
    val tripLive by viewModel.tripLive.collectAsStateWithLifecycle()
    val tripArrival by viewModel.tripArrival.collectAsStateWithLifecycle()
    val addState by viewModel.add.collectAsStateWithLifecycle()
    val eventTags by viewModel.eventTags.collectAsStateWithLifecycle()
    val homeSetupState by viewModel.homeSetup.collectAsStateWithLifecycle()
    val prepOnboardingState by viewModel.prepOnboarding.collectAsStateWithLifecycle()
    val routeState by viewModel.routeChoice.collectAsStateWithLifecycle()
    val mapPickState by viewModel.mapPick.collectAsStateWithLifecycle()
    val routineState by viewModel.routine.collectAsStateWithLifecycle()
    val importState by viewModel.calendarImport.collectAsStateWithLifecycle()
    val calendarLinkState by viewModel.calendarLink.collectAsStateWithLifecycle()
    val calendarOnboardingState by viewModel.calendarOnboarding.collectAsStateWithLifecycle()
    val reportState by viewModel.report.collectAsStateWithLifecycle()
    val morningSession by viewModel.morning.collectAsStateWithLifecycle()

    // Partly AI-generated (Claude)
    // ⑧ 의 스크롤 위치. 화면 전환(AnimatedContent)이 ⑧ 을 컴포지션에서 빼므로 화면 안에
    // 두면 ⑨ 에서 돌아올 때마다 맨 위로 튄다. 여기 두고 넘긴다. 주가 바뀌거나 홈에서
    // 새로 열면 맨 위로 보낸다.
    val reportScroll = rememberScrollState()
    LaunchedEffect(reportState.weekly?.weekStart, reportState.openCount) {
        reportScroll.scrollTo(0)
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    /** 개발 빌드의 서버 확인 결과. 설정 화면이 읽는다 */
    var serverStatus by remember { mutableStateOf<String?>(null) }

    /**
     * 카카오맵 장소 페이지를 연다.
     *
     * **평점·사진·영업시간이 있는 유일한 곳이다.** 카카오 로컬 API 응답에는 그
     * 값들이 없어서 우리 화면에 별을 그릴 수 없다. 지어내는 대신 원본으로 보낸다.
     */
    val activityContext = LocalContext.current
    val openPlaceUrl: (String) -> Unit = remember(activityContext) {
        { url ->
            // 브라우저가 없는 기기도 있다. 열지 못해도 앱이 죽어서는 안 된다.
            runCatching {
                activityContext.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
            }
        }
    }

    // 세션이 끝나면 로그인 화면으로 되돌린다.
    //
    // **이것이 없으면 로그인 유지가 함정이 된다.** refresh 가 만료되면
    // authenticator 가 토큰을 지우는데, 화면은 그것을 모르고 홈에 남아 "다시
    // 로그인해야 한다" 만 반복해서 보여준다. 앱을 다시 열어도 자동으로 들어오므로
    // 사용자는 로그인 화면을 볼 방법을 스스로 찾아야 한다.
    //
    // 네트워크 실패로는 켜지지 않는다 — 그 판정은 RefreshOutcome 이 한다.
    val sessionExpired by SessionState.expired.collectAsStateWithLifecycle()
    LaunchedEffect(sessionExpired) {
        if (sessionExpired) onLoggedOut()
    }

    // 알림·위치 권한을 한 번 요청한다.
    //
    // 알림 권한은 알람의 전제다 — 전체화면 인텐트가 알림을 타고 뜨기 때문에
    // 거부되면 잠금화면에서 알람 화면이 올라오지 못한다.
    // 위치 권한은 출발·도착 판별의 전제다. 없으면 알람은 울리지만 기록이 없다.
    // 배경 위치는 요청하지 않는다 — 추적은 알람 화면에서 시작하므로 필요 없다.
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* 결과는 화면에서 다시 확인한다. 거부해도 앱은 동작한다 */ }

    LaunchedEffect(Unit) {
        val missing = buildList {
            if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (!LocationPermissions.granted(context)) {
                addAll(LocationPermissions.REQUESTED)
            }
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    // 환영 문구. 로그인에서 넘어온 경우에만 한 번 띄운다.
    val welcomeText = welcomeNickname?.let { stringResource(R.string.auth_welcome, it) }
    var welcomeShown by remember { mutableStateOf(false) }
    LaunchedEffect(welcomeText) {
        if (welcomeText != null && !welcomeShown) {
            welcomeShown = true
            snackbarHostState.showSnackbar(welcomeText)
        }
    }

    // 안드로이드 뒤로 버튼을 화면 스택에 연결한다. 스택이 하나면 기본 동작
    // (앱 종료)에 맡긴다.
    //
    // 블록 편집 폼은 같은 경로 안에서 겹쳐 뜨므로 스택에 없다. 폼이 열려
    // 있으면 먼저 닫는다 — 그러지 않으면 뒤로가 폼과 목록을 한꺼번에 건너뛴다.
    //
    // 경로 지도 미리보기도 같은 방식이다(⑬ 안에서 겹쳐 그림). 열려 있으면 미리보기만
    // 닫고 경로 목록으로 돌아간다.
    val draftOpen = routineState.editing != null
    val routePreviewOpen = nav.current == AppRoute.RouteChoice && routeState?.previewKey != null
    BackHandler(enabled = nav.canGoBack || draftOpen || routePreviewOpen) {
        when {
            draftOpen -> viewModel.dismissBlockDraft()
            routePreviewOpen -> viewModel.closeRoutePreview()
            else -> viewModel.goBack()
        }
    }

    // 일정 추가·집 설정이 끝나면 홈으로 되돌린다.
    LaunchedEffect(addState.done) {
        if (addState.done) {
            viewModel.goBack()
            viewModel.resetAdd()
            snackbarHostState.showSnackbar("일정을 추가했습니다")
        }
    }
    LaunchedEffect(homeSetupState.done) {
        if (homeSetupState.done) {
            // 건너뛴 경우에는 저장한 것이 없다. 준비 시간 온보딩과 같은 이유로
            // 확인 문구를 띄우지 않는다 — 저장되지 않은 것을 저장했다고 말한다.
            val saved = homeSetupState.saved
            viewModel.goBack()
            viewModel.resetHomeSetup()
            if (saved) snackbarHostState.showSnackbar("집 주소를 저장했습니다")
        }
    }
    LaunchedEffect(prepOnboardingState.done) {
        if (prepOnboardingState.done) {
            // 건너뛴 경우에는 저장한 값이 없으므로 확인 문구를 띄우지 않는다.
            // "저장했습니다" 를 띄우면 저장되지 않은 것을 저장했다고 말한다.
            val saved = prepOnboardingState.parsed
            viewModel.goBack()
            viewModel.resetPrepOnboarding()
            if (saved != null) {
                // "1시간 5분" 처럼 다이얼과 같은 표기. "분"·"간" 모두 받침이 있어 "으로" 고정.
                snackbarHostState.showSnackbar("평소 준비 시간을 ${durationLabel(saved)}으로 저장했습니다")
            }
        }
    }
    LaunchedEffect(importState.done) {
        if (importState.done) {
            val label = importState.resultLabel
            viewModel.goBack()
            viewModel.resetCalendarImport()
            snackbarHostState.showSnackbar(label ?: "캘린더에서 가져왔습니다")
        }
    }
    // Partly AI-generated (Claude) — ⑱ 캘린더 연동. 건너뛰었으면 저장한 것이 없으므로 말하지 않는다.
    LaunchedEffect(calendarOnboardingState.done) {
        if (calendarOnboardingState.done) {
            val saved = calendarOnboardingState.savedCount
            viewModel.goBack()
            viewModel.resetCalendarOnboarding()
            if (saved != null) snackbarHostState.showSnackbar("캘린더 ${saved}개를 연동했습니다")
        }
    }
    // 기기 설정에서 캘린더 권한을 켜고 돌아왔을 수 있다. 바뀐 경우에만 다시 읽는다.
    LifecycleResumeEffect(Unit) {
        viewModel.onCalendarScreenResumed()
        onPauseOrDispose { }
    }

    // 알람을 해제하고 넘어왔으면 아침 기록으로 바로 들어간다. 한 번만 한다 —
    // 사용자가 뒤로 나갔는데 다시 밀어 넣으면 화면을 벗어날 수 없다.
    LaunchedEffect(openMorning) {
        if (openMorning) {
            viewModel.openMorning()
            onMorningOpened()
        }
    }

    // 캘린더 권한은 **사용자가 가져오기를 누를 때만** 요청한다. 앱을 처음 열
    // 때 함께 묶어 요청하면 무엇에 쓰는지 모르는 상태로 거절하게 된다.
    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> viewModel.onCalendarPermissionResult(granted) }

    // Partly AI-generated (Claude)
    // ⑱ · ⑯ · 가져오기가 같이 쓴다. 한 번 거절한 뒤 시스템이 대화상자를 더 띄우지 않으면
    // ("다시 묻지 않음") 요청해도 바로 거절로 끝난다. 그때는 앱 권한 화면을 연다.
    val requestCalendarPermission: () -> Unit = {
        val activity = activityContext as? Activity
        val blocked = viewModel.calendarPermissionDenied && activity != null &&
            !activity.shouldShowRequestPermissionRationale(DeviceCalendar.PERMISSION)
        if (blocked) {
            runCatching {
                activityContext.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        "package:${activityContext.packageName}".toUri(),
                    )
                )
            }
        } else {
            calendarPermissionLauncher.launch(DeviceCalendar.PERMISSION)
        }
    }

    // Partly AI-generated (Claude)
    // "캘린더가 안 보이면" 안내의 버튼. 돌아오면 onCalendarScreenResumed 가 목록을 다시 읽는다.
    // 구글 캘린더 앱이 없으면 그 버튼은 그리지 않는다(매니페스트 <queries> 로 확인).
    val googleCalendarLaunch = remember(activityContext) {
        activityContext.packageManager.getLaunchIntentForPackage(DeviceCalendar.GOOGLE_CALENDAR_PACKAGE)
    }
    val calendarHelp = remember(activityContext, googleCalendarLaunch) {
        CalendarHelpActions(
            openSyncSettings = {
                // 계정 동기화 화면이 없는 기기도 있다. 그때는 설정 첫 화면이라도 연다.
                val opened = runCatching {
                    activityContext.startActivity(Intent(Settings.ACTION_SYNC_SETTINGS))
                }.isSuccess
                if (!opened) runCatching { activityContext.startActivity(Intent(Settings.ACTION_SETTINGS)) }
            },
            openGoogleCalendar = googleCalendarLaunch?.let { intent ->
                { runCatching { activityContext.startActivity(intent) } }
            },
        )
    }

    Scaffold(
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        containerColor = JitColor.Bg,
        // 키보드(ime)도 포함한다. edge-to-edge 에서는 adjustResize 가 창을 줄여 주지
        // 않으므로, 빼면 키보드가 아래 칸을 덮고 스크롤로도 닿지 않는다.
        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.ime),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        AnimatedContent(
            targetState = nav,
            transitionSpec = {
                // 파고들 때는 오른쪽에서, 돌아올 때는 왼쪽에서 들어온다.
                val direction = if (targetState.forward) 1 else -1
                val enter = slideInHorizontally(
                    animationSpec = tween(280, easing = FastOutSlowInEasing),
                ) { full -> direction * full / 5 } + fadeIn(tween(200))
                val exit = slideOutHorizontally(
                    animationSpec = tween(280, easing = FastOutSlowInEasing),
                ) { full -> -direction * full / 5 } + fadeOut(tween(160))
                enter togetherWith exit
            },
            contentKey = { it.current.toString() },
            label = "main-nav",
        ) { target ->
            when (val route = target.current) {
                AppRoute.Home -> HomeScreen(
                    state = state,
                    avatarInitials = viewModel.avatarInitials(),
                    onAvatarClick = viewModel::openSettings,
                    onEventClick = { viewModel.openAlarmDecision(it.id) },
                    onAlarmToggle = viewModel::setAlarmEnabled,
                    onAddEventClick = {
                        viewModel.resetAdd()
                        viewModel.openAddEvent()
                    },
                    onSetHomeClick = {
                        viewModel.resetHomeSetup()
                        viewModel.openHomeSetup()
                    },
                    onRoutineClick = viewModel::openRoutineEditor,
                    onCalendarClick = viewModel::openCalendarImport,
                    onReportClick = viewModel::openReport,
                    onMorningClick = viewModel::openMorning,
                    onRetry = viewModel::refresh,
                    modifier = Modifier.padding(innerPadding),
                    onMissedTrackClick = viewModel::startMissedAlarmTracking,
                    onMissedDismiss = viewModel::dismissMissedAlarm,
                )

                is AppRoute.AlarmDecision -> {
                    // 진행률은 계획(경로)과 추적 위치가 **둘 다** 있어야 낼 수
                    // 있다. 하나라도 없으면 null 로 두고 화면이 이유를 말한다.
                    val nowMillis = System.currentTimeMillis()
                    val live = tripLive?.takeIf {
                        it.eventId == route.eventId &&
                            it.accuracyM.isFinite() &&
                            it.accuracyM <= TripGeofence.MAX_ACCURACY_M &&
                            nowMillis - it.atMillis in 0..2 * 60_000L
                    }
                    val progress = plan
                        ?.takeIf { it.hasRoutePath }
                        ?.let { p -> live?.let { RouteProgress.of(p.routePath, it.point) } }
                    val arrival = tripArrival?.takeIf { it.eventId == route.eventId }
                    val stage = plan?.let { viewModel.stageOf(it, arrival, nowMillis) }
                        ?: TripStage.BEFORE_ALARM

                    // 지각 전망. 진행 바의 색이 이것으로 정해진다.
                    //
                    // 알람 전에는 내지 않는다 — 아직 시작하지 않은 여정을 두고
                    // 늦는다/안 늦는다 할 것이 없다. 기록이 없거나(지난 일정·알람
                    // 꺼짐) 어디 있는지 모를 때(출발 확인 안 됨)도 마찬가지다.
                    // 경로를 벗어났을 때도 내지 않는다: 다른 길을 가는 중이면 계획
                    // 속도로 환산한 값이 아무 뜻이 없다.
                    val outlook = plan?.takeIf {
                        stage.canJudgeLateness && progress?.onRoute != false
                    }?.let { p ->
                        if (stage == TripStage.ARRIVED) {
                            (arrival?.atMillis ?: live?.atMillis)?.let {
                                ArrivalOutlook.arrived(it, p.startAtMillis)
                            }
                        } else {
                            val now = System.currentTimeMillis()
                            val onRoute = progress?.takeIf { it.onRoute }
                            // 이동 중이면 관측 속도를 섞는다. 이동 시작 시각은
                            // 서비스가 들고 있다 — 화면에서 처음 본 좌표를
                            // 기준으로 삼으면 늦게 화면을 연 사용자가 "방금
                            // 출발했다" 로 계산된다.
                            val movingSince = live?.movingSinceMillis
                            val remaining = if (onRoute != null && movingSince != null) {
                                TripEta.remainingMinutes(
                                    travelMinutes = p.travelMinutes,
                                    totalM = onRoute.totalM,
                                    traveledM = onRoute.traveledM,
                                    movingMinutes = (now - movingSince) / 60_000.0,
                                )
                            } else {
                                TripEta.plannedRemainingMinutes(
                                    travelMinutes = p.travelMinutes,
                                    ratio = onRoute?.ratio ?: 0f,
                                )
                            }
                            ArrivalOutlook.of(p.startAtMillis, remaining, now)
                        }
                    }
                    AlarmDecisionScreen(
                        plan = plan,
                        onBack = viewModel::goBack,
                        onChangeRisk = { viewModel.openRiskChoice(route.eventId) },
                        onEditBlocks = { viewModel.openEventBlocks(route.eventId) },
                        onRecompute = { viewModel.recomputePlan(route.eventId) },
                        onDelete = { viewModel.deleteEvent(route.eventId) },
                        modifier = Modifier.padding(innerPadding),
                        nickname = state.nickname,
                        initials = viewModel.avatarInitials(),
                        stage = stage,
                        progress = progress,
                        outlook = outlook,
                        arrivalClock = outlook?.let { arrivalClockLabel(it.predictedMillis) },
                        freshness = live?.let { freshnessLabel(it.atMillis) },
                        routeMap = routeMap?.takeIf { it.eventId == route.eventId },
                        onRouteMapViewport = viewModel::loadRouteMapImage,
                        onRouteMapFit = viewModel::fitRouteMap,
                        onRouteMapGestureEnd = viewModel::onRouteMapGestureEnd,
                        onRouteMapRecenter = viewModel::onRouteMapRecenter,
                    )
                }

                is AppRoute.RiskChoice -> RiskChoiceScreen(
                    plan = plan?.takeIf { it.eventId == route.eventId },
                    onBack = viewModel::goBack,
                    modifier = Modifier.padding(innerPadding),
                    riskOptions = risk.options?.takeIf { risk.eventId == route.eventId },
                    loading = risk.eventId != route.eventId || risk.loading,
                    saving = risk.eventId == route.eventId && risk.saving,
                    error = risk.error?.takeIf { risk.eventId == route.eventId },
                    onRetry = { viewModel.loadRiskOptions(route.eventId) },
                    onContinue = { viewModel.saveRiskOption(route.eventId, it) },
                )

                AppRoute.AddEvent -> AddEventScreen(
                    state = addState,
                    hasHome = state.hasHome,
                    homePlace = state.homePlace,
                    tags = eventTags,
                    onTitleChange = viewModel::onAddTitleChange,
                    onDateChange = viewModel::onAddDateChange,
                    onTimeChange = viewModel::onAddTimeChange,
                    onRepeatDayToggle = viewModel::onAddRepeatDayToggle,
                    onTagChange = viewModel::onAddTagChange,
                    onQueryChange = viewModel::onAddQueryChange,
                    onSearch = viewModel::searchPlaces,
                    onPlaceSelect = viewModel::onAddPlaceSelected,
                    onDestinationHome = viewModel::onAddDestinationHome,
                    onLoadMore = viewModel::loadMoreAddPlaces,
                    onSortChange = viewModel::onAddSortChange,
                    onOpenMap = {
                        viewModel.openMapPick(HomeViewModel.MapTarget.DESTINATION)
                    },
                    onOpenPlaceUrl = openPlaceUrl,
                    onOriginQueryChange = viewModel::onAddOriginQueryChange,
                    onOriginSearch = viewModel::searchAddOriginPlaces,
                    onOriginSelect = viewModel::onAddOriginSelected,
                    onOriginLoadMore = viewModel::loadMoreAddOriginPlaces,
                    onOriginSortChange = viewModel::onAddOriginSortChange,
                    onOriginOpenMap = {
                        viewModel.openMapPick(HomeViewModel.MapTarget.ORIGIN)
                    },
                    onUseCurrentLocation = viewModel::useCurrentLocationForAddOrigin,
                    onSetHome = {
                        viewModel.resetHomeSetup()
                        viewModel.openHomeSetup()
                    },
                    onPickRoute = viewModel::openRouteChoice,
                    onSubmit = viewModel::submitAdd,
                    onBack = viewModel::goBack,
                    modifier = Modifier.padding(innerPadding),
                )

                AppRoute.RouteChoice -> RouteChoiceScreen(
                    state = routeState,
                    onSelect = viewModel::onRouteSelected,
                    onConfirm = viewModel::confirmRoute,
                    onRetry = viewModel::retryRouteChoice,
                    onBack = viewModel::goBack,
                    modifier = Modifier.padding(innerPadding),
                    // 일정 시작 시각 = 도착 목표. 카드의 출발~도착 시각을 만든다.
                    arriveBy = addState.date.atTime(addState.hour, addState.minute),
                    loadMap = viewModel::loadCandidateMap,
                    onPreview = viewModel::openRoutePreview,
                    onClosePreview = viewModel::closeRoutePreview,
                )

                AppRoute.MapPick -> mapPickState?.let { map ->
                    MapPickScreen(
                        state = map,
                        onViewport = viewModel::loadMapImage,
                        onGestureEnd = viewModel::onMapGestureEnd,
                        onRecenter = viewModel::onMapRecenter,
                        onResearch = viewModel::researchMapArea,
                        onSelect = viewModel::onMapPlaceSelected,
                        onConfirm = viewModel::confirmMapPick,
                        onBack = {
                            viewModel.closeMapPick()
                            viewModel.goBack()
                        },
                        onOpenPlaceUrl = openPlaceUrl,
                        modifier = Modifier.padding(innerPadding),
                    )
                }

                AppRoute.PrepOnboarding -> PrepOnboardingScreen(
                    state = prepOnboardingState,
                    onMinutesChange = viewModel::onPrepOnboardingChange,
                    onSubmit = viewModel::submitPrepOnboarding,
                    onSkip = viewModel::skipPrepOnboarding,
                    modifier = Modifier.padding(innerPadding),
                )

                AppRoute.CalendarLink -> CalendarLinkScreen(
                    link = calendarLinkState,
                    state = calendarOnboardingState,
                    onToggle = viewModel::toggleCalendarOnboarding,
                    onRequestPermission = requestCalendarPermission,
                    onSubmit = viewModel::submitCalendarOnboarding,
                    onSkip = viewModel::skipCalendarOnboarding,
                    modifier = Modifier.padding(innerPadding),
                    help = calendarHelp,
                )

                AppRoute.HomeSetup -> HomeSetupScreen(
                    state = homeSetupState,
                    onQueryChange = viewModel::onHomeQueryChange,
                    onSearch = viewModel::searchHomePlaces,
                    onSelect = viewModel::onHomePlaceSelected,
                    onLoadMore = viewModel::loadMoreHomePlaces,
                    onSortChange = viewModel::onHomeSortChange,
                    onOpenMap = { viewModel.openMapPick(HomeViewModel.MapTarget.HOME) },
                    onOpenPlaceUrl = openPlaceUrl,
                    onSubmit = viewModel::submitHomeSetup,
                    onSkip = viewModel::skipHomeSetup,
                    onBack = viewModel::goBack,
                    modifier = Modifier.padding(innerPadding),
                )

                AppRoute.Settings -> SettingsScreen(
                    state = state,
                    avatarInitials = viewModel.avatarInitials(),
                    locationGranted = LocationPermissions.granted(context),
                    onChangeHome = {
                        viewModel.resetHomeSetup()
                        viewModel.openHomeSetup()
                    },
                    onChangePrep = viewModel::openPrepOnboarding,
                    onLogout = {
                        viewModel.logout()
                        onLoggedOut()
                    },
                    onBack = viewModel::goBack,
                    modifier = Modifier.padding(innerPadding),
                    calendarLink = calendarLinkState,
                    onCalendarToggle = viewModel::setCalendarLinked,
                    onRequestCalendarPermission = requestCalendarPermission,
                    calendarHelp = calendarHelp,
                    // 빌드에 박힌 값이 아니라 실제로 쓰는 주소를 보여준다.
                    // 에뮬레이터면 10.0.2.2 로 바뀌어 있다.
                    devInfo = if (BuildConfig.DEV_TOOLS) {
                        buildString {
                            append("서버 = ${ApiClient.baseUrl}")
                            serverStatus?.let { append("\n$it") }
                        }
                    } else {
                        null
                    },
                    onDevCheck = if (BuildConfig.DEV_TOOLS) {
                        {
                            scope.launch {
                                serverStatus = try {
                                    val res = ApiClient.health.health()
                                    "서버 연결 성공  ok=${res.ok}  version=${res.version ?: "-"}"
                                } catch (e: Exception) {
                                    "서버 연결 실패  ${e.javaClass.simpleName}"
                                }
                            }
                        }
                    } else {
                        null
                    },
                )

                // 정의 편집과 일정별 체크가 같은 화면을 쓴다. 상태의 eventId 로
                // 갈리고, 저장 동작이 다르다는 사실은 화면이 문구로 밝힌다.
                AppRoute.RoutineEditor, is AppRoute.EventBlocks -> RoutineHost(
                    state = routineState,
                    viewModel = viewModel,
                    modifier = Modifier.padding(innerPadding),
                )

                AppRoute.CalendarImport -> CalendarImportScreen(
                    state = importState,
                    onBack = viewModel::goBack,
                    onToggle = viewModel::toggleImportCandidate,
                    onRequestPermission = requestCalendarPermission,
                    onRetry = viewModel::loadCalendarCandidates,
                    onImport = viewModel::submitCalendarImport,
                    modifier = Modifier.padding(innerPadding),
                    link = calendarLinkState,
                    onCalendarToggle = viewModel::setCalendarLinked,
                    help = calendarHelp,
                )

                AppRoute.WeeklyReport -> WeeklyReportScreen(
                    report = reportState.weekly,
                    longTerm = reportState.longTerm,
                    loading = reportState.loading,
                    error = reportState.error,
                    onBack = viewModel::goBack,
                    onPreviousWeek = { viewModel.shiftReportWeek(-1) },
                    onNextWeek = { viewModel.shiftReportWeek(1) },
                    onRetry = { viewModel.loadReport(reportState.week) },
                    onOpenDay = viewModel::openReportDay,
                    modifier = Modifier.padding(innerPadding),
                    scrollState = reportScroll,
                )

                // Partly AI-generated (Claude)
                is AppRoute.ReportDay -> ReportDayScreen(
                    report = reportState.weekly,
                    longTerm = reportState.longTerm,
                    date = route.date,
                    initialEventId = route.eventId,
                    onBack = viewModel::goBack,
                    modifier = Modifier.padding(innerPadding),
                )

                AppRoute.MorningProgress -> MorningProgressScreen(
                    session = morningSession,
                    onBack = viewModel::goBack,
                    onMarkDone = viewModel::markBlockDone,
                    onUndo = viewModel::undoLastBlock,
                    onFinish = viewModel::finishMorning,
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }
    }

}

/**
 * 루틴 블록 화면 호스트.
 *
 * 목록과 편집 폼을 **같은 경로 안에서** 바꿔 그린다. 폼을 별도 경로로 올리면
 * 저장 후 목록으로 돌아가는 길에 pop 을 두 번 해야 하고, 항목을 연달아 고칠 때
 * 화면 전환 애니메이션이 반복돼 거슬린다.
 *
 * 뒤로 처리는 `MainHost` 의 [BackHandler] 가 담당한다 — 폼이 열려 있으면
 * 폼만 닫는다.
 */
@Composable
private fun RoutineHost(
    state: com.swpp.wakeup.domain.model.RoutineEditorState,
    viewModel: HomeViewModel,
    modifier: Modifier = Modifier,
) {
    val draft = state.editing
    if (draft != null) {
        BlockDraftSheet(
            draft = draft,
            saving = state.saving,
            onName = viewModel::onDraftName,
            onMin = viewModel::onDraftMin,
            onMax = viewModel::onDraftMax,
            onDropCost = viewModel::onDraftDropCost,
            onParallel = viewModel::onDraftParallel,
            onIncluded = viewModel::onDraftIncluded,
            onSave = viewModel::saveBlockDraft,
            onDelete = viewModel::deleteBlock,
            onDismiss = viewModel::dismissBlockDraft,
            modifier = modifier,
        )
        return
    }

    RoutineEditorScreen(
        state = state,
        onBack = viewModel::goBack,
        onToggleChecked = viewModel::toggleBlockChecked,
        onToggleIncludedByDefault = viewModel::toggleIncludedByDefault,
        onEditBlock = viewModel::startEditBlock,
        onNewBlock = viewModel::startNewBlock,
        onSaveEventBlocks = viewModel::saveEventBlocks,
        onDismissMessages = viewModel::clearRoutineMessages,
        modifier = modifier,
    )
}
