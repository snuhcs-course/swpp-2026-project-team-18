package com.swpp.wakeup.ui.me

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.swpp.wakeup.data.remote.PasswordCheckResponse
import com.swpp.wakeup.data.remote.UserDto
import com.swpp.wakeup.data.repository.AuthRepository.AccountResult
import com.swpp.wakeup.data.repository.AuthRepository.EmailVerifyResult
import com.swpp.wakeup.data.repository.AuthRepository.PasswordChangeResult
import com.swpp.wakeup.data.repository.MeRepository
import com.swpp.wakeup.domain.model.SocialProvider
import com.swpp.wakeup.ui.auth.SocialSignIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class MeViewModelTest {
    private val valid = PasswordCheckResponse(true, true, emptyList())
    private fun user(methods: List<String>? = listOf("password"), email: String = "me@snu.ac.kr") =
        UserDto(7, email, "진호", loginMethods = methods, emailVerified = true)

    private class Fake : MeRepository {
        var check: suspend (String) -> Result<PasswordCheckResponse> = { Result.failure(IOException("조건 확인 실패")) }
        var change: suspend (String, String, String) -> PasswordChangeResult = { _, _, _ -> PasswordChangeResult.Failure("변경 실패") }
        var send: suspend () -> EmailVerifyResult = { EmailVerifyResult.Sent(600) }
        var confirm: suspend (String) -> EmailVerifyResult = { EmailVerifyResult.Failure("코드가 맞지 않아요") }
        var current: suspend () -> AccountResult = { AccountResult.Failure("계정 조회 실패") }
        var link: suspend (SocialProvider, String) -> AccountResult = { _, _ -> AccountResult.Failure("연결 실패") }
        var unlink: suspend (SocialProvider) -> AccountResult = { AccountResult.Failure("연결 해제 실패") }
        val checked = mutableListOf<String>()
        val changed = mutableListOf<Triple<String, String, String>>()
        val confirmed = mutableListOf<String>()
        val linked = mutableListOf<Pair<SocialProvider, String>>()
        val unlinked = mutableListOf<SocialProvider>()
        var sent = 0
        var reads = 0
        override suspend fun checkPassword(value: String): Result<PasswordCheckResponse> { checked += value; return check(value) }
        override suspend fun changePassword(current: String, password: String, confirm: String): PasswordChangeResult {
            changed += Triple(current, password, confirm); return change(current, password, confirm)
        }
        override suspend fun requestEmailVerify(): EmailVerifyResult { ++sent; return send() }
        override suspend fun confirmEmailVerify(code: String): EmailVerifyResult { confirmed += code; return confirm(code) }
        override suspend fun currentUser(): AccountResult { ++reads; return current() }
        override suspend fun linkSocial(provider: SocialProvider, token: String): AccountResult { linked += provider to token; return link(provider, token) }
        override suspend fun unlinkSocial(provider: SocialProvider): AccountResult { unlinked += provider; return unlink(provider) }
    }

    private class Events {
        val messages = mutableListOf<String>()
        val verified = mutableListOf<String?>()
        val users = mutableListOf<UserDto>()
        val accounts = mutableListOf<MeViewModel.AccountState>()
        var passwordOpened = 0
        var passwordClosed = 0
        var emailOpened = 0
        var emailClosed = 0
        fun hooks() = MeViewModel.Hooks(
            openPassword = { ++passwordOpened }, closePassword = { ++passwordClosed },
            openEmail = { ++emailOpened }, closeEmail = { ++emailClosed },
            verified = { verified += it }, userChanged = { users += it },
            accountChanged = { accounts += it }, notify = { messages += it },
        )
    }

    private fun TestScope.vm(repo: Fake, events: Events = Events()) = MeViewModel(repo, backgroundScope, events.hooks())

    private fun TestScope.fill(vm: MeViewModel, repo: Fake) {
        repo.check = { Result.success(valid) }
        vm.openPassword()
        vm.onCurrentPassword("old12345")
        vm.onNewPassword("new12345")
        vm.onPasswordConfirm("new12345")
        runCurrent()
        assertTrue(vm.password.value.canSubmit)
    }

    @Test fun `password check uses server result and empty input clears it without another request`() = runTest {
        val repo = Fake().apply { check = { Result.success(valid) } }
        val events = Events()
        val vm = vm(repo, events)
        vm.openPassword()
        vm.onNewPassword("new12345")
        runCurrent()
        assertEquals(valid, vm.password.value.passwordCheck)
        vm.onNewPassword("")
        runCurrent()
        assertEquals(listOf("new12345"), repo.checked)
        assertNull(vm.password.value.passwordCheck)
        assertEquals(1, events.passwordOpened)
    }

    @Test fun `failed password rule lookup explains why submission is unavailable`() = runTest {
        val repo = Fake()
        val vm = vm(repo)
        vm.onCurrentPassword("old12345")
        vm.onNewPassword("new12345")
        vm.onPasswordConfirm("new12345")
        runCurrent()
        vm.submitPassword()
        assertFalse(vm.password.value.canSubmit)
        assertEquals("조건 확인 실패", vm.password.value.passwordCheckError)
        assertTrue(repo.changed.isEmpty())
    }

    @Test fun `old cancellation ignoring password check cannot overwrite a newer password`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = Fake().apply { check = { text ->
            if (text == "old") withContext(NonCancellable) { gate.await(); Result.failure(IOException("old failure")) }
            else Result.success(valid)
        } }
        val vm = vm(repo)
        vm.onNewPassword("old")
        runCurrent()
        vm.onNewPassword("new12345")
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(valid, vm.password.value.passwordCheck)
        assertNull(vm.password.value.passwordCheckError)
    }

    @Test fun `opening password form clears hidden input and invalidates old validation`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = Fake().apply { check = { withContext(NonCancellable) { gate.await(); Result.success(valid) } } }
        val vm = vm(repo)
        vm.onCurrentPassword("secret")
        vm.onNewPassword("newsecret")
        vm.onPasswordConfirm("newsecret")
        runCurrent()
        vm.openPassword()
        gate.complete(Unit)
        runCurrent()
        assertEquals("", vm.password.value.current)
        assertEquals("", vm.password.value.confirm)
        assertNull(vm.password.value.passwordCheck)
    }

    @Test fun `invalid current password mismatch and unchanged password never submit`() = runTest {
        val repo = Fake()
        val vm = vm(repo)
        vm.submitPassword()
        fill(vm, repo)
        vm.onPasswordConfirm("different")
        vm.submitPassword()
        vm.onNewPassword("old12345")
        vm.onPasswordConfirm("old12345")
        runCurrent()
        assertTrue(vm.password.value.sameAsCurrent)
        vm.submitPassword()
        assertTrue(repo.changed.isEmpty())
    }

    @Test fun `password success clears secrets and announces token rotation then closes that flow`() = runTest {
        val repo = Fake().apply { change = { _, _, _ -> PasswordChangeResult.Success(true) } }
        val events = Events()
        val vm = vm(repo, events)
        fill(vm, repo)
        vm.submitPassword()
        runCurrent()
        assertEquals(listOf(Triple("old12345", "new12345", "new12345")), repo.changed)
        assertEquals("", vm.password.value.newPassword)
        assertFalse(vm.password.value.busy)
        assertEquals(1, events.passwordClosed)
        assertEquals("비밀번호를 바꿨어요. 다른 기기는 로그아웃됐어요", events.messages.single())
    }

    @Test fun `legacy password success without tokens explains need to sign in again`() = runTest {
        val repo = Fake().apply { change = { _, _, _ -> PasswordChangeResult.Success(false) } }
        val events = Events()
        val vm = vm(repo, events)
        fill(vm, repo)
        vm.submitPassword()
        runCurrent()
        assertTrue(events.messages.single().contains("다시 로그인"))
    }

    @Test fun `wrong current password stays in form and changing it clears its reason`() = runTest {
        val repo = Fake().apply { change = { _, _, _ -> PasswordChangeResult.Failure("지금 비밀번호가 맞지 않아요", true) } }
        val events = Events()
        val vm = vm(repo, events)
        fill(vm, repo)
        vm.submitPassword()
        runCurrent()
        assertTrue(vm.password.value.currentWrong)
        assertEquals("new12345", vm.password.value.newPassword)
        assertEquals(0, events.passwordClosed)
        vm.onCurrentPassword("correct123")
        assertFalse(vm.password.value.currentWrong)
        assertNull(vm.password.value.error)
    }

    @Test fun `generic password failure preserves input and confirmation change clears global reason`() = runTest {
        val repo = Fake()
        val vm = vm(repo)
        fill(vm, repo)
        vm.submitPassword()
        runCurrent()
        assertEquals("변경 실패", vm.password.value.error)
        assertFalse(vm.password.value.currentWrong)
        vm.onPasswordConfirm("new12345")
        assertNull(vm.password.value.error)
    }

    @Test fun `busy password rejects repeated submits edits and reopening cannot create another write`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = Fake().apply { change = { _, _, _ -> gate.await(); PasswordChangeResult.Success(true) } }
        val vm = vm(repo)
        fill(vm, repo)
        vm.submitPassword()
        vm.submitPassword()
        vm.onCurrentPassword("changed")
        vm.onNewPassword("changed")
        vm.onPasswordConfirm("changed")
        vm.openPassword()
        runCurrent()
        assertEquals(1, repo.changed.size)
        assertEquals("old12345", vm.password.value.current)
        assertTrue(vm.password.value.busy)
        gate.complete(Unit)
        runCurrent()
    }

    @Test fun `leaving password clears secrets but a submitted change can finish`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = Fake().apply { change = { _, _, _ -> gate.await(); PasswordChangeResult.Success(true) } }
        val events = Events()
        val vm = vm(repo, events)
        fill(vm, repo)
        vm.submitPassword()
        runCurrent()
        vm.leavePassword()
        assertEquals("", vm.password.value.current)
        assertTrue(vm.password.value.busy)
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, events.messages.size)
        assertFalse(vm.password.value.busy)
    }

    @Test fun `cancelled password request releases busy so retry is possible`() = runTest {
        val repo = Fake().apply { change = { _, _, _ -> throw CancellationException() } }
        val vm = vm(repo)
        fill(vm, repo)
        vm.submitPassword()
        runCurrent()
        assertFalse(vm.password.value.busy)
        assertTrue(vm.password.value.canSubmit)
    }

    @Test fun `email send opens input once and remembers the receiving account`() = runTest {
        val repo = Fake()
        val events = Events()
        val vm = vm(repo, events)
        vm.startEmail("me@snu.ac.kr")
        vm.startEmail("other@snu.ac.kr")
        runCurrent()
        assertEquals(1, repo.sent)
        assertEquals("me@snu.ac.kr", vm.email.value.sentTo)
        assertEquals(1, events.emailOpened)
        assertFalse(vm.email.value.sending)
    }

    @Test fun `already verified send updates account without opening code input`() = runTest {
        val repo = Fake().apply { send = { EmailVerifyResult.AlreadyVerified } }
        val events = Events()
        val vm = vm(repo, events)
        vm.startEmail("me@snu.ac.kr")
        runCurrent()
        assertEquals(listOf<String?>(null), events.verified)
        assertEquals(0, events.emailOpened)
        assertEquals("이미 인증된 이메일이에요", events.messages.single())
    }

    @Test fun `verified send response uses server email`() = runTest {
        val repo = Fake().apply { send = { EmailVerifyResult.Verified(user(email = "verified@snu.ac.kr")) } }
        val events = Events()
        val vm = vm(repo, events)
        vm.startEmail(null)
        runCurrent()
        assertEquals(listOf("verified@snu.ac.kr"), events.verified)
    }

    @Test fun `email send failure resets spinner reports reason and permits retry`() = runTest {
        val repo = Fake().apply { send = { EmailVerifyResult.Failure("메일을 보낼 수 없어요") } }
        val events = Events()
        val vm = vm(repo, events)
        vm.startEmail(null)
        runCurrent()
        assertFalse(vm.email.value.sending)
        assertEquals("메일을 보낼 수 없어요", events.messages.single())
        vm.startEmail(null)
        runCurrent()
        assertEquals(2, repo.sent)
    }

    @Test fun `email code accepts six ASCII digits and short code does not send confirmation`() = runTest {
        val repo = Fake()
        val vm = vm(repo)
        vm.onEmailCode("a12-3")
        vm.confirmEmail()
        assertTrue(repo.confirmed.isEmpty())
        vm.onEmailCode("a12-34567８")
        assertEquals("123456", vm.email.value.code)
    }

    @Test fun `resend success invalidates old code and preserves receiving address`() = runTest {
        val repo = Fake()
        val vm = vm(repo)
        vm.startEmail("me@snu.ac.kr")
        runCurrent()
        vm.onEmailCode("123456")
        vm.resendEmail()
        runCurrent()
        assertEquals("", vm.email.value.code)
        assertEquals("me@snu.ac.kr", vm.email.value.sentTo)
        assertTrue(vm.email.value.info!!.contains("이전 코드는 쓸 수 없어요"))
        vm.onEmailCode("654321")
        assertNull(vm.email.value.info)
    }

    @Test fun `resend failure keeps old input and editing clears reason`() = runTest {
        val repo = Fake().apply { send = { EmailVerifyResult.Failure("전송 실패") } }
        val vm = vm(repo)
        vm.onEmailCode("123456")
        vm.resendEmail()
        runCurrent()
        assertEquals("123456", vm.email.value.code)
        assertEquals("전송 실패", vm.email.value.error)
        vm.onEmailCode("654321")
        assertNull(vm.email.value.error)
    }

    @Test fun `resend already verified finishes flow and publishes verification`() = runTest {
        val repo = Fake().apply { send = { EmailVerifyResult.AlreadyVerified } }
        val events = Events()
        val vm = vm(repo, events)
        vm.resendEmail()
        runCurrent()
        assertEquals(1, events.emailClosed)
        assertEquals(listOf<String?>(null), events.verified)
    }

    @Test fun `resend verified publishes returned email`() = runTest {
        val repo = Fake().apply { send = { EmailVerifyResult.Verified(user()) } }
        val events = Events()
        val vm = vm(repo, events)
        vm.resendEmail()
        runCurrent()
        assertEquals(listOf("me@snu.ac.kr"), events.verified)
    }

    @Test fun `sending blocks resend confirmation editing and another initial send`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = Fake().apply { send = { gate.await(); EmailVerifyResult.Sent(600) } }
        val vm = vm(repo)
        vm.onEmailCode("123456")
        vm.resendEmail()
        vm.resendEmail()
        vm.confirmEmail()
        vm.startEmail("other")
        vm.onEmailCode("654321")
        runCurrent()
        assertEquals(1, repo.sent)
        assertTrue(repo.confirmed.isEmpty())
        assertEquals("123456", vm.email.value.code)
        gate.complete(Unit)
        runCurrent()
    }

    @Test fun `confirmation success clears code updates user and closes flow`() = runTest {
        val repo = Fake().apply { confirm = { EmailVerifyResult.Verified(user()) } }
        val events = Events()
        val vm = vm(repo, events)
        vm.onEmailCode("123456")
        vm.confirmEmail()
        runCurrent()
        assertEquals(listOf("123456"), repo.confirmed)
        assertEquals("", vm.email.value.code)
        assertEquals(listOf("me@snu.ac.kr"), events.verified)
        assertEquals("이메일을 인증했어요", events.messages.single())
        assertEquals(1, events.emailClosed)
    }

    @Test fun `confirmation already verified succeeds without inventing an email`() = runTest {
        val repo = Fake().apply { confirm = { EmailVerifyResult.AlreadyVerified } }
        val events = Events()
        val vm = vm(repo, events)
        vm.onEmailCode("123456")
        vm.confirmEmail()
        runCurrent()
        assertEquals(listOf<String?>(null), events.verified)
        assertEquals(1, events.emailClosed)
    }

    @Test fun `older account read cannot undo a newly verified email`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = Fake().apply {
            current = { withContext(NonCancellable) { gate.await(); AccountResult.Success(user().copy(emailVerified = false)) } }
            confirm = { EmailVerifyResult.Verified(user()) }
        }
        val events = Events()
        val vm = vm(repo, events)
        vm.loadLoginMethods()
        runCurrent()
        vm.onEmailCode("123456")
        vm.confirmEmail()
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("me@snu.ac.kr"), events.verified)
        assertTrue(events.users.isEmpty())
        assertEquals(listOf("password"), vm.account.value.loginMethods)
    }

    @Test fun `confirmation failure keeps code and does not mark verified`() = runTest {
        val repo = Fake()
        val events = Events()
        val vm = vm(repo, events)
        vm.onEmailCode("123456")
        vm.confirmEmail()
        runCurrent()
        assertEquals("코드가 맞지 않아요", vm.email.value.error)
        assertEquals("123456", vm.email.value.code)
        assertTrue(events.verified.isEmpty())
        assertFalse(vm.email.value.busy)
    }

    @Test fun `unexpected sent confirmation does not claim verification`() = runTest {
        val repo = Fake().apply { confirm = { EmailVerifyResult.Sent(600) } }
        val events = Events()
        val vm = vm(repo, events)
        vm.onEmailCode("123456")
        vm.confirmEmail()
        runCurrent()
        assertFalse(vm.email.value.busy)
        assertTrue(events.verified.isEmpty())
    }

    @Test fun `confirmation busy rejects duplicate confirm resend start and code editing`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = Fake().apply { confirm = { gate.await(); EmailVerifyResult.Failure("실패") } }
        val vm = vm(repo)
        vm.onEmailCode("123456")
        vm.confirmEmail()
        vm.confirmEmail()
        vm.resendEmail()
        vm.startEmail("other")
        vm.onEmailCode("654321")
        runCurrent()
        assertEquals(listOf("123456"), repo.confirmed)
        assertEquals(0, repo.sent)
        assertEquals("123456", vm.email.value.code)
        gate.complete(Unit)
        runCurrent()
    }

    @Test fun `leaving email ignores old initial send and cannot open a late screen`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = Fake().apply { send = { withContext(NonCancellable) { gate.await(); EmailVerifyResult.Sent(600) } } }
        val events = Events()
        val vm = vm(repo, events)
        vm.startEmail("old")
        runCurrent()
        vm.leaveEmail()
        gate.complete(Unit)
        runCurrent()
        assertEquals(0, events.emailOpened)
        assertNull(vm.email.value.sentTo)
        assertFalse(vm.email.value.sending)
    }

    @Test fun `leaving email ignores a cancellation ignoring confirmed result`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = Fake().apply { confirm = { withContext(NonCancellable) { gate.await(); EmailVerifyResult.Verified(user()) } } }
        val events = Events()
        val vm = vm(repo, events)
        vm.onEmailCode("123456")
        vm.confirmEmail()
        runCurrent()
        vm.leaveEmail()
        gate.complete(Unit)
        runCurrent()
        assertTrue(events.verified.isEmpty())
        assertEquals("", vm.email.value.code)
    }

    @Test fun `email cancellation releases request and confirm spinners`() = runTest {
        val repo = Fake().apply {
            send = { throw CancellationException() }
            confirm = { throw CancellationException() }
        }
        val vm = vm(repo)
        vm.startEmail(null)
        runCurrent()
        assertFalse(vm.email.value.sending)
        vm.resendEmail()
        runCurrent()
        assertFalse(vm.email.value.sending)
        vm.onEmailCode("123456")
        vm.confirmEmail()
        runCurrent()
        assertFalse(vm.email.value.busy)
    }

    @Test fun `account refresh publishes server user and missing login methods become empty`() = runTest {
        val repo = Fake().apply { current = { AccountResult.Success(user(null)) } }
        val events = Events()
        val vm = vm(repo, events)
        vm.loadLoginMethods()
        runCurrent()
        assertEquals(emptyList<String>(), vm.account.value.loginMethods)
        assertEquals(user(null), events.users.single())
        assertFalse(vm.account.value.loginMethodsFailed)
    }

    @Test fun `account failure marks first load but preserves a successfully cached list`() = runTest {
        val repo = Fake()
        val vm = vm(repo)
        vm.loadLoginMethods()
        runCurrent()
        assertTrue(vm.account.value.loginMethodsFailed)
        repo.current = { AccountResult.Success(user()) }
        vm.loadLoginMethods()
        runCurrent()
        repo.current = { AccountResult.Failure("offline") }
        vm.loadLoginMethods()
        runCurrent()
        assertFalse(vm.account.value.loginMethodsFailed)
        assertEquals(listOf("password"), vm.account.value.loginMethods)
    }

    @Test fun `social begin reserves one provider and ignores another provider outcome`() = runTest {
        val repo = Fake()
        val vm = vm(repo)
        assertTrue(vm.beginSocialLink(SocialProvider.KAKAO))
        assertFalse(vm.beginSocialLink(SocialProvider.GOOGLE))
        vm.onSocialOutcome(SocialProvider.GOOGLE, SocialSignIn.Outcome.Token("wrong"))
        vm.loadLoginMethods()
        runCurrent()
        assertTrue(repo.linked.isEmpty())
        assertEquals(0, repo.reads)
        assertEquals(SocialProvider.KAKAO, vm.account.value.socialLinkBusy)
    }

    @Test fun `social cancel unavailable and SDK failure clear busy with appropriate message`() = runTest {
        val repo = Fake()
        val events = Events()
        val vm = vm(repo, events)
        vm.beginSocialLink(SocialProvider.KAKAO)
        vm.onSocialOutcome(SocialProvider.KAKAO, SocialSignIn.Outcome.Cancelled)
        assertNull(vm.account.value.socialLinkBusy)
        assertTrue(events.messages.isEmpty())
        vm.beginSocialLink(SocialProvider.KAKAO)
        vm.onSocialOutcome(SocialProvider.KAKAO, SocialSignIn.Outcome.NotConfigured)
        assertTrue(events.messages.last().contains("쓸 수 없어요"))
        vm.beginSocialLink(SocialProvider.GOOGLE)
        vm.onSocialOutcome(SocialProvider.GOOGLE, SocialSignIn.Outcome.Failed("SDK 오류"))
        assertEquals("SDK 오류", events.messages.last())
        assertNull(vm.account.value.socialLinkBusy)
    }

    @Test fun `social token links once then uses server methods and user`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = Fake().apply { link = { _, _ -> gate.await(); AccountResult.Success(user(listOf("password", "kakao"))) } }
        val events = Events()
        val vm = vm(repo, events)
        vm.beginSocialLink(SocialProvider.KAKAO)
        vm.onSocialOutcome(SocialProvider.KAKAO, SocialSignIn.Outcome.Token("token"))
        vm.onSocialOutcome(SocialProvider.KAKAO, SocialSignIn.Outcome.Token("duplicate"))
        runCurrent()
        assertEquals(listOf(SocialProvider.KAKAO to "token"), repo.linked)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("password", "kakao"), vm.account.value.loginMethods)
        assertNull(vm.account.value.socialLinkBusy)
        assertTrue(events.users.isEmpty())
        assertTrue(events.messages.single().contains("연결했어요"))
    }

    @Test fun `failed social link reports reason and reconciles methods from server`() = runTest {
        val repo = Fake().apply { current = { AccountResult.Success(user(listOf("google"))) } }
        val events = Events()
        val vm = vm(repo, events)
        vm.beginSocialLink(SocialProvider.KAKAO)
        vm.onSocialOutcome(SocialProvider.KAKAO, SocialSignIn.Outcome.Token("token"))
        runCurrent()
        assertEquals(listOf("google"), vm.account.value.loginMethods)
        assertEquals("연결 실패", events.messages.single())
        assertEquals(1, repo.reads)
    }

    @Test fun `social mutation snapshot cannot undo an email verified while it was pending`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = Fake().apply {
            link = { _, _ -> withContext(NonCancellable) {
                gate.await(); AccountResult.Success(user(listOf("password", "google")).copy(emailVerified = false))
            } }
            confirm = { EmailVerifyResult.Verified(user()) }
        }
        val events = Events()
        val vm = vm(repo, events)
        vm.beginSocialLink(SocialProvider.GOOGLE)
        vm.onSocialOutcome(SocialProvider.GOOGLE, SocialSignIn.Outcome.Token("token"))
        runCurrent()
        vm.onEmailCode("123456")
        vm.confirmEmail()
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("me@snu.ac.kr"), events.verified)
        assertEquals(listOf("password", "google"), vm.account.value.loginMethods)
        assertTrue(events.users.isEmpty())
    }

    @Test fun `account lookup from before social mutation cannot overwrite linked methods`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = Fake().apply {
            current = { withContext(NonCancellable) { gate.await(); AccountResult.Success(user(listOf("password"))) } }
            link = { _, _ -> AccountResult.Success(user(listOf("password", "google"))) }
        }
        val vm = vm(repo)
        vm.loadLoginMethods()
        runCurrent()
        vm.beginSocialLink(SocialProvider.GOOGLE)
        vm.onSocialOutcome(SocialProvider.GOOGLE, SocialSignIn.Outcome.Token("token"))
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("password", "google"), vm.account.value.loginMethods)
    }

    @Test fun `unlink uses server permission and blocks duplicate operations while pending`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = Fake().apply { unlink = { gate.await(); AccountResult.Success(user(listOf("password"))) } }
        val events = Events()
        val vm = vm(repo, events)
        vm.unlinkSocial(SocialProvider.KAKAO)
        vm.unlinkSocial(SocialProvider.KAKAO)
        runCurrent()
        assertEquals(listOf(SocialProvider.KAKAO), repo.unlinked)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("password"), vm.account.value.loginMethods)
        assertTrue(events.messages.single().contains("연결을 끊었어요"))
    }

    @Test fun `unlink failure preserves methods until reconciliation rather than optimistic removal`() = runTest {
        val repo = Fake().apply { current = { AccountResult.Success(user(listOf("kakao"))) } }
        val events = Events()
        val vm = vm(repo, events)
        vm.loadLoginMethods()
        runCurrent()
        vm.unlinkSocial(SocialProvider.KAKAO)
        runCurrent()
        assertEquals(listOf("kakao"), vm.account.value.loginMethods)
        assertEquals("연결 해제 실패", events.messages.single())
        assertEquals(2, repo.reads)
    }

    @Test fun `social cancellation releases busy without claiming success`() = runTest {
        val repo = Fake().apply {
            link = { _, _ -> throw CancellationException() }
            unlink = { throw CancellationException() }
        }
        val events = Events()
        val vm = vm(repo, events)
        vm.beginSocialLink(SocialProvider.KAKAO)
        vm.onSocialOutcome(SocialProvider.KAKAO, SocialSignIn.Outcome.Token("token"))
        runCurrent()
        assertNull(vm.account.value.socialLinkBusy)
        vm.unlinkSocial(SocialProvider.GOOGLE)
        runCurrent()
        assertNull(vm.account.value.socialLinkBusy)
        assertTrue(events.messages.isEmpty())
    }

    @Test fun `close clears secrets and rejects cancellation ignoring replies and later callbacks`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = Fake().apply {
            check = { withContext(NonCancellable) { gate.await(); Result.success(valid) } }
            send = { withContext(NonCancellable) { gate.await(); EmailVerifyResult.Sent(600) } }
            current = { withContext(NonCancellable) { gate.await(); AccountResult.Success(user()) } }
        }
        val events = Events()
        val vm = vm(repo, events)
        vm.onCurrentPassword("old12345")
        vm.onNewPassword("new12345")
        vm.onPasswordConfirm("new12345")
        vm.startEmail("me@snu.ac.kr")
        vm.loadLoginMethods()
        runCurrent()
        vm.close()
        vm.close()
        val notifications = events.messages.size
        val publications = events.accounts.size
        gate.complete(Unit)
        runCurrent()
        vm.openPassword()
        vm.onCurrentPassword("secret")
        vm.onNewPassword("secret")
        vm.onPasswordConfirm("secret")
        vm.submitPassword()
        vm.leavePassword()
        vm.startEmail("new")
        vm.onEmailCode("123456")
        vm.resendEmail()
        vm.confirmEmail()
        vm.leaveEmail()
        vm.loadLoginMethods()
        assertFalse(vm.beginSocialLink(SocialProvider.GOOGLE))
        vm.onSocialOutcome(SocialProvider.GOOGLE, SocialSignIn.Outcome.Token("token"))
        vm.unlinkSocial(SocialProvider.GOOGLE)
        runCurrent()
        assertEquals("", vm.password.value.current)
        assertEquals("", vm.email.value.code)
        assertEquals(0, events.emailOpened)
        assertEquals(notifications, events.messages.size)
        assertEquals(publications, events.accounts.size)
        assertTrue(events.users.isEmpty())
    }

    @Test fun `owner close blocks a completed password write from notifying a later account`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = Fake().apply { change = { _, _, _ ->
            withContext(NonCancellable) { gate.await(); PasswordChangeResult.Success(true) }
        } }
        val events = Events()
        val vm = vm(repo, events)
        fill(vm, repo)
        vm.submitPassword()
        runCurrent()
        vm.close()
        gate.complete(Unit)
        runCurrent()
        assertTrue(events.messages.isEmpty())
        assertEquals(0, events.passwordClosed)
        assertFalse(vm.password.value.busy)
    }

    @Test fun `owner clear blocks a social mutation from publishing into a later account`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = Fake().apply { link = { _, _ -> withContext(NonCancellable) { gate.await(); AccountResult.Success(user()) } } }
        val events = Events()
        val vm = vm(repo, events)
        val store = ViewModelStore()
        val provider = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = vm as T
        })
        assertSame(vm, provider[MeViewModel::class.java])
        vm.beginSocialLink(SocialProvider.GOOGLE)
        vm.onSocialOutcome(SocialProvider.GOOGLE, SocialSignIn.Outcome.Token("token"))
        runCurrent()
        store.clear()
        gate.complete(Unit)
        runCurrent()
        assertTrue(events.messages.isEmpty())
        assertTrue(events.users.isEmpty())
        assertNull(vm.account.value.socialLinkBusy)
    }
}
