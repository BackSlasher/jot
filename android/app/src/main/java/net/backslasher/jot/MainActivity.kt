package net.backslasher.jot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import net.backslasher.jot.ui.entry.EntryScreen
import net.backslasher.jot.ui.entry.EntryViewModel
import net.backslasher.jot.ui.list.ListScreen
import net.backslasher.jot.ui.list.ListViewModel
import net.backslasher.jot.ui.theme.JotTheme
import java.time.LocalDate

@Serializable
data object ListRoute

/** Open the entry with [id], or the entry for [date] (ISO) creating it on first edit. */
@Serializable
data class EntryRoute(val id: String? = null, val date: String? = null)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as JotApplication).container
        setContent {
            JotTheme {
                JotNavHost(container)
            }
        }
    }
}

@Composable
private fun JotNavHost(container: AppContainer) {
    val navController = rememberNavController()
    NavHost(navController, startDestination = ListRoute) {
        composable<ListRoute> {
            val vm: ListViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { ListViewModel(container.repository, container.schema) }
                },
            )
            ListScreen(
                viewModel = vm,
                schema = container.schema,
                onOpenEntry = { id -> navController.navigate(EntryRoute(id = id)) },
                onNewEntry = { navController.navigate(EntryRoute(date = LocalDate.now().toString())) },
            )
        }
        composable<EntryRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<EntryRoute>()
            val vm: EntryViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        EntryViewModel(
                            repository = container.repository,
                            savedState = createSavedStateHandle(),
                            writeScope = container.writeScope,
                            requestedId = route.id,
                            requestedDate = route.date?.let(LocalDate::parse),
                        )
                    }
                },
            )
            EntryScreen(
                viewModel = vm,
                schema = container.schema,
                onClose = {
                    // Guard against a double pop if the event is delivered twice.
                    if (navController.currentBackStackEntry?.id == backStackEntry.id) navController.popBackStack()
                },
                onOpenEntry = { id ->
                    navController.navigate(EntryRoute(id = id)) {
                        popUpTo<EntryRoute> { inclusive = true }
                    }
                },
            )
        }
    }
}
