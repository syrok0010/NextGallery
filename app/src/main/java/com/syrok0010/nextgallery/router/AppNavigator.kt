package com.syrok0010.nextgallery.router

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

internal sealed interface NavigationCommand {
    data class Open(val route: NextGalleryRoute) : NavigationCommand
    data class Select(val route: NextGalleryRoute) : NavigationCommand

    data object Back : NavigationCommand
}

internal data class PendingNavigation(val id: Long, val command: NavigationCommand)

/** Process-lived commands; the composition alone owns and saves its back stack. */
internal class AppNavigator {
    private var nextId = 0L
    private val pending = MutableStateFlow<List<PendingNavigation>>(emptyList())
    val commands = pending.asStateFlow()

    fun open(route: NextGalleryRoute) = enqueue(NavigationCommand.Open(route))
    fun select(route: NextGalleryRoute) = enqueue(NavigationCommand.Select(route))
    fun back() = enqueue(NavigationCommand.Back)
    fun follow(link: Uri) {
        linkRoute(link)?.let(::open)
    }

    @Synchronized
    private fun enqueue(command: NavigationCommand) {
        pending.value = pending.value + PendingNavigation(++nextId, command)
    }

    /** Mutation and acknowledgement share one non-suspending turn; stale hosts cannot replay. */
    @Synchronized
    fun consume(id: Long, action: (NavigationCommand) -> Unit) {
        val head = pending.value.firstOrNull()?.takeIf { it.id == id } ?: return
        action(head.command)
        pending.value = pending.value.drop(1)
    }
}

internal fun routerModule() = module { single<AppNavigator>() }

internal fun applyNavigationCommand(
    stack: List<NextGalleryRoute>,
    command: NavigationCommand,
): List<NextGalleryRoute> =
    when (command) {
        is NavigationCommand.Open ->
            if (stack.lastOrNull()?.navContentKey() == command.route.navContentKey()) {
                stack
            } else {
                stack + command.route
            }

        is NavigationCommand.Select -> destinationBackStack(stack, command.route)

        NavigationCommand.Back -> if (stack.size > 1) stack.dropLast(1) else stack
    }
