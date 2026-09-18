package com.swpp.wakeup

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.swpp.wakeup.data.remote.ApiClient
import com.swpp.wakeup.ui.alarm.AlarmDecisionScreen
import com.swpp.wakeup.ui.alarm.RiskChoiceScreen
import com.swpp.wakeup.ui.auth.LoginActivity
import com.swpp.wakeup.ui.events.AddEventScreen
import com.swpp.wakeup.ui.events.HomeSetupScreen
import com.swpp.wakeup.ui.events.RouteChoiceScreen
import com.swpp.wakeup.ui.home.HomeScreen
import com.swpp.wakeup.ui.home.HomeViewModel
import com.swpp.wakeup.ui.nav.AppRoute
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

        setContent {
            JitTheme {
                MainHost(
                    welcomeNickname = welcomeNickname,
                    onLoggedOut = ::backToLogin,
                )
            }
        }
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
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun MainHost(
    welcomeNickname: String?,
    onLoggedOut: () -> Unit,
) {
    val viewModel: HomeViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val nav by viewModel.nav.collectAsStateWithLifecycle()
    val plan by viewModel.plan.collectAsStateWithLifecycle()
    val addState by viewModel.add.collectAsStateWithLifecycle()
    val homeSetupState by viewModel.homeSetup.collectAsStateWithLifecycle()
    val routeState by viewModel.routeChoice.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showAccountDialog by remember { mutableStateOf(false) }
    var serverStatus by remember { mutableStateOf<String?>(null) }

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
    BackHandler(enabled = nav.canGoBack) { viewModel.goBack() }

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
            viewModel.goBack()
            viewModel.resetHomeSetup()
            snackbarHostState.showSnackbar("집 위치를 저장했습니다")
        }
    }

    Scaffold(
        containerColor = JitColor.Bg,
        contentWindowInsets = WindowInsets.systemBars,
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
                    onAvatarClick = { showAccountDialog = true },
                    onEventClick = { viewModel.openAlarmDecision(it.id) },
                    onAddEventClick = {
                        viewModel.resetAdd()
                        viewModel.openAddEvent()
                    },
                    onSetHomeClick = {
                        viewModel.resetHomeSetup()
                        viewModel.openHomeSetup()
                    },
                    onRetry = viewModel::refresh,
                    modifier = Modifier.padding(innerPadding),
                )

                is AppRoute.AlarmDecision -> AlarmDecisionScreen(
                    plan = plan,
                    onBack = viewModel::goBack,
                    onChangeRisk = { viewModel.openRiskChoice(route.eventId) },
                    onDelete = { viewModel.deleteEvent(route.eventId) },
                    modifier = Modifier.padding(innerPadding),
                )

                is AppRoute.RiskChoice -> RiskChoiceScreen(
                    plan = plan,
                    onBack = viewModel::goBack,
                    modifier = Modifier.padding(innerPadding),
                )

                AppRoute.AddEvent -> AddEventScreen(
                    state = addState,
                    hasHome = state.hasHome,
                    onTitleChange = viewModel::onAddTitleChange,
                    onDateChange = viewModel::onAddDateChange,
                    onTimeChange = viewModel::onAddTimeChange,
                    onTagChange = viewModel::onAddTagChange,
                    onQueryChange = viewModel::onAddQueryChange,
                    onSearch = viewModel::searchPlaces,
                    onPlaceSelect = viewModel::onAddPlaceSelected,
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
                )

                AppRoute.HomeSetup -> HomeSetupScreen(
                    state = homeSetupState,
                    onQueryChange = viewModel::onHomeQueryChange,
                    onSearch = viewModel::searchHomePlaces,
                    onSelect = viewModel::onHomePlaceSelected,
                    onPrepChange = viewModel::onHomePrepChange,
                    onSubmit = viewModel::submitHomeSetup,
                    onBack = viewModel::goBack,
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }
    }

    if (showAccountDialog) {
        AlertDialog(
            onDismissRequest = { showAccountDialog = false },
            containerColor = JitColor.Surface,
            title = { Text(state.nickname, color = JitColor.TextPrimary) },
            text = {
                Text(
                    buildString {
                        append("일정 ${state.totalCount}개")
                        state.homeLabel?.let { append("\n집: $it") }
                        if (!state.hasHome) append("\n집 위치 미설정")
                        if (BuildConfig.DEV_TOOLS) {
                            append("\n\nBASE_URL = ${BuildConfig.BASE_URL}")
                            serverStatus?.let { append("\n$it") }
                        }
                    },
                    color = JitColor.TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.logout()
                    showAccountDialog = false
                    onLoggedOut()
                }) {
                    Text("로그아웃", color = JitColor.Red)
                }
            },
            dismissButton = {
                if (BuildConfig.DEV_TOOLS) {
                    TextButton(onClick = {
                        scope.launch {
                            serverStatus = try {
                                val res = ApiClient.health.health()
                                "서버 연결 성공  ok=${res.ok}  version=${res.version ?: "-"}"
                            } catch (e: Exception) {
                                "서버 연결 실패  ${e.javaClass.simpleName}"
                            }
                        }
                    }) {
                        Text("서버 확인", color = JitColor.TextSecondary)
                    }
                } else {
                    TextButton(onClick = { showAccountDialog = false }) {
                        Text("닫기", color = JitColor.TextSecondary)
                    }
                }
            },
        )
    }
}
