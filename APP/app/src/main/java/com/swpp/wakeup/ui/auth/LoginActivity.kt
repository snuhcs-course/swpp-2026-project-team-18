package com.swpp.wakeup.ui.auth

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.swpp.wakeup.MainActivity
import com.swpp.wakeup.R
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitTheme
import kotlinx.coroutines.launch

/**
 * 앱 진입 화면. Figma ⑦ 로그인 / ⑩ 회원가입 두 화면을 호스팅한다.
 *
 * 네비게이션 라이브러리를 넣지 않았다. 화면이 둘뿐이고 서로만 오가므로
 * [AuthViewModel.UiState.screen] 분기로 충분하다. 화면이 늘어나면
 * Navigation Compose 를 도입한다(front-spec 논의 항목).
 *
 * 로그인·가입이 성공하면 [MainActivity] 로 넘어가고 이 액티비티는 종료한다.
 * 뒤로 눌러 로그인 화면으로 되돌아오는 것을 막기 위해서다. 환영 문구는
 * 여기서 띄우지 않고 닉네임만 넘긴다. 여기서 스낵바를 띄우면 suspend 가
 * 끝날 때까지 화면 전환이 밀린다.
 */
class LoginActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 액티비티 전환을 페이드로 덮는다. 기본 전환은 창이 아래에서
        // 튀어오르는 모양이라 Compose 내부 전환과 결이 다르다. minSdk 34.
        overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, R.anim.activity_enter, R.anim.activity_exit)
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, R.anim.activity_enter, R.anim.activity_exit)

        // 배경이 항상 어두우므로 시스템 바 아이콘을 밝게 고정한다.
        // enableEdgeToEdge 는 시스템 다크모드 설정을 따라가므로, 지정하지 않으면
        // 라이트 모드 기기에서 아이콘이 검게 나와 배경에 묻힌다.
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        setContent {
            JitTheme {
                AuthHost(
                    onAuthenticated = ::goToMain,
                    onBrowse = { goToMain(null) },
                )
            }
        }
    }

    /** @param nickname 환영 문구에 쓸 이름. 둘러보기로 들어오면 null 이다. */
    private fun goToMain(nickname: String?) {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                if (nickname != null) putExtra(MainActivity.EXTRA_WELCOME_NICKNAME, nickname)
            }
        )
        finish()
    }
}

@Composable
private fun AuthHost(
    onAuthenticated: (String?) -> Unit,
    onBrowse: () -> Unit,
) {
    val viewModel: AuthViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val socialNotWired = stringResource(R.string.login_social_not_wired)

    // 인증 성공을 한 번만 소비한다. 재구성마다 화면이 뛰지 않게 한다.
    // 환영 문구는 MainActivity 가 띄운다. 여기서 스낵바를 await 하면
    // 그 시간 동안 화면이 그대로 멈춰 있어 반응이 느리게 느껴진다.
    LaunchedEffect(state.authenticatedNickname) {
        val nickname = state.authenticatedNickname
        if (nickname != null) {
            viewModel.consumeAuthenticated()
            onAuthenticated(nickname)
        }
    }

    Scaffold(
        containerColor = JitColor.Bg,
        contentWindowInsets = WindowInsets.systemBars,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        val showSocialNotice: () -> Unit = {
            scope.launch { snackbarHostState.showSnackbar(socialNotWired) }
        }

        AnimatedContent(
            targetState = state.screen,
            transitionSpec = {
                // 가입으로 갈 때는 오른쪽에서, 로그인으로 돌아올 때는 왼쪽에서.
                val direction = if (targetState == AuthViewModel.Screen.SIGNUP) 1 else -1
                val enter = slideInHorizontally(
                    animationSpec = tween(280, easing = FastOutSlowInEasing),
                ) { full -> direction * full / 5 } + fadeIn(tween(200))
                val exit = slideOutHorizontally(
                    animationSpec = tween(280, easing = FastOutSlowInEasing),
                ) { full -> -direction * full / 5 } + fadeOut(tween(160))
                enter togetherWith exit
            },
            label = "auth-nav",
        ) { screen ->
            when (screen) {
                AuthViewModel.Screen.LOGIN -> LoginScreen(
                    email = state.email,
                    password = state.password,
                    loading = state.loading,
                    error = state.error,
                    canSubmit = state.canSubmitLogin,
                    onEmailChange = viewModel::onEmailChange,
                    onPasswordChange = viewModel::onPasswordChange,
                    onLoginClick = viewModel::login,
                    onSignupClick = viewModel::goToSignup,
                    onGoogleClick = showSocialNotice,
                    onKakaoClick = showSocialNotice,
                    onBrowseClick = onBrowse,
                    modifier = Modifier.padding(innerPadding),
                    waking = state.waking
                )

                AuthViewModel.Screen.SIGNUP -> SignupScreen(
                    email = state.email,
                    nickname = state.nickname,
                    password = state.password,
                    passwordConfirm = state.passwordConfirm,
                    loading = state.loading,
                    error = state.error,
                    canSubmit = state.canSubmitSignup,
                    onEmailChange = viewModel::onEmailChange,
                    onNicknameChange = viewModel::onNicknameChange,
                    onPasswordChange = viewModel::onPasswordChange,
                    onPasswordConfirmChange = viewModel::onPasswordConfirmChange,
                    onSubmitClick = viewModel::signup,
                    onBackToLoginClick = viewModel::goToLogin,
                    modifier = Modifier.padding(innerPadding),
                    waking = state.waking
                )
            }
        }
    }
}
