package app.hopnet.services

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * A service offered through Hopnet. [id] is the `service` string used with Hopnet.send(...)
 * and matched by a handler of the same id on the server (server/hopnet_server/services/).
 */
data class HopService(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val available: Boolean,
)

/**
 * Services shown on the home screen. To add one (e.g. food ordering): flip `available`,
 * add its screens under services/<id>/ and a route in ui/HopnetRoot.kt, and add a handler
 * with the same id on the server. The hopping layer underneath needs no changes.
 */
object ServiceCatalog {
    val messages = HopService("chat", "Messages", "Text anyone on Hopnet, even with no signal", Icons.Filled.Email, available = true)
    val findMe = HopService("findme", "Find Me", "Lost in a crowd? Show people nearby where you are", Icons.Filled.LocationOn, available = true)
    val liveInfo = HopService("info", "Live Info", "Weather, official alerts, news and the web, with no signal", Icons.Filled.Info, available = true)

    val all = listOf(
        messages,
        findMe,
        liveInfo,
        HopService("food", "Food ordering", "Order from nearby restaurants, pay on delivery", Icons.Filled.ShoppingCart, available = false),
        HopService("tickets", "Tickets", "Book bus and event tickets", Icons.Filled.DateRange, available = false),
    )
}
