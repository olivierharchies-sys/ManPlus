package com.olivier.manplus

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.olivier.manplus.data.Repo
import com.olivier.manplus.ui.BrowserScreen
import com.olivier.manplus.ui.ChatScreen
import com.olivier.manplus.ui.LibraryScreen
import com.olivier.manplus.ui.SearchScreen
import com.olivier.manplus.ui.SettingsScreen
import com.olivier.manplus.ui.ViewerScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.init(applicationContext)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { AppNav() }
            }
        }
    }
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "library") {
        composable("library") { LibraryScreen(nav) }
        composable("search") { SearchScreen(nav) }
        composable("settings") { SettingsScreen(nav) }
        composable(
            "browser?url={url}&p={p}",
            arguments = listOf(
                navArgument("url") { type = NavType.StringType; defaultValue = "" },
                navArgument("p") { type = NavType.StringType; defaultValue = "" }
            )
        ) { BrowserScreen(nav, it.arguments?.getString("url").orEmpty(), it.arguments?.getString("p").orEmpty()) }
        composable("chat/{id}") { ChatScreen(nav, it.arguments?.getString("id").orEmpty()) }
        composable(
            "viewer/{id}?page={page}&q={q}",
            arguments = listOf(
                navArgument("page") { type = NavType.IntType; defaultValue = 1 },
                navArgument("q") { type = NavType.StringType; defaultValue = "" }
            )
        ) {
            ViewerScreen(
                nav, it.arguments?.getString("id").orEmpty(),
                it.arguments?.getInt("page") ?: 1, it.arguments?.getString("q").orEmpty()
            )
        }
    }
}
