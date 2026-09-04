package example;

import com.fentai.arcmenu.api.ArcMenuApi;
import com.fentai.arcmenu.api.ArcMenuActivateEvent;
import com.fentai.arcmenu.api.ArcMenuApplicationContext;
import com.fentai.arcmenu.api.ArcMenuApplicationHandle;
import com.fentai.arcmenu.api.ArcMenuApplicationOptions;
import com.fentai.arcmenu.api.ArcMenuApplicationSession;
import com.fentai.arcmenu.api.ArcMenuScrollEvent;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/** Java addon showing both the light route API and the hosted application API. */
public final class ExampleArcMenuAddon extends JavaPlugin {
    private ArcMenuApi arcMenu;
    private ArcMenuApplicationHandle application;

    @Override
    public void onEnable() {
        arcMenu = Bukkit.getServicesManager().load(ArcMenuApi.class);
        if (arcMenu == null) {
            throw new IllegalStateException("ArcMenu API service is unavailable");
        }
        arcMenu.registerRoute(this, "myaddon:shop/main", (player, arguments) ->
            arcMenu.open(player, "m5-animation", arguments)
        );
        application = arcMenu.registerApplication(
            this,
            "myaddon:shop",
            ArcMenuApplicationOptions.builder().captureMouseScroll(true).build(),
            ShopSession::new
        );
    }

    @Override
    public void onDisable() {
        if (application != null) application.unregister();
        if (arcMenu != null) arcMenu.unregisterRoutes(this);
    }

    private static final class ShopSession extends ArcMenuApplicationSession {
        private final ArcMenuApplicationContext context;

        private ShopSession(ArcMenuApplicationContext context) {
            this.context = context;
        }

        @Override
        public void onActivate(ArcMenuActivateEvent event) {
            context.getPlayer().sendMessage("ArcMenu app click at " + event.getPoint());
        }

        @Override
        public void onScroll(ArcMenuScrollEvent event) {
            context.getPlayer().sendMessage("Scroll steps: " + event.getSteps());
        }
    }
}
