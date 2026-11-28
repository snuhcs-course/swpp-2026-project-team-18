package com.swpp.wakeup.integration

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.swpp.wakeup.ui.home.HomeViewModel
import com.swpp.wakeup.ui.nav.AppRoute
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 실제 Android 저장소와 초기화 순서를 검사해요. 개인 앱을 실행하는 테스트는 건너뛰어요. */
@RunWith(AndroidJUnit4::class)
class HomeViewModelStartupTest {
    @Test fun coordinatorCanStartNavigateAndCloseWithRealAndroidStores() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as Application
        assumeTrue(application.packageName.endsWith(".qa"))
        instrumentation.runOnMainSync {
            val store = ViewModelStore()
            try {
                val provider = ViewModelProvider(store, object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(application) as T
                })
                val home = provider[HomeViewModel::class.java]
                assertNotNull(home.state.value)
                home.openPermissions()
                assertEquals(AppRoute.Permissions, home.nav.value.current)
                home.goBack()
                assertNotEquals(AppRoute.Permissions, home.nav.value.current)
            } finally { store.clear() }
        }
    }
}
