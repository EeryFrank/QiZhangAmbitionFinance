package qizhang.cozyservices.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import java.net.URI;
import java.text.NumberFormat;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Vector2i;

/** Client presentation only: every balance, price, inventory change and payout is server-authoritative. */
public final class CozyMenuScreen extends Screen {
    private static final int PAPER = 0xFFFFF9EF, INK = 0xFF493B32, MUTED = 0xFF968570;
    private static final int RAIL = 0xFF785640, ACCENT = 0xFFAA7048, LINE = 0xFFE8DBC8;
    private static final int GREEN = 0xFF607B55, RAIL_WIDTH = 42;
    private static final NumberFormat NUMBERS = NumberFormat.getIntegerInstance(Locale.SIMPLIFIED_CHINESE);
    private static final URI MAP_URL = URI.create("https://map.eeryfrank.online/");
    private enum Page { HOME, HOME_POINT, TRAVEL, SOCIAL, ECONOMY, SHOP, PRODUCT, WITHDRAW, MAIL, VIP, SERVICES, GUIDE, CHECKIN, CHECKIN_CONFIRM, SETTINGS }
    private final List<BodyLine> lines = new ArrayList<>();
    private final List<CalendarHeading> calendarHeadings = new ArrayList<>();
    private final List<CozyButton> allButtons = new ArrayList<>();
    private final List<CozyButton> bodyButtons = new ArrayList<>();
    private final List<Product> products = new ArrayList<>();
    private Page page = Page.HOME;
    private int panelWidth, contentX, contentWidth, bodyTop, bodyBottom, contentHeight, scroll;
    private String category = "building", feedback = "", selectedProductId = "";
    private JsonObject data = new JsonObject();
    private long responseId = Long.MIN_VALUE, pendingAt, receivedAt, delayedQuoteAt, travelRefreshAt, quoteExpiresNanos = Long.MAX_VALUE, serviceQuoteExpiresNanos = Long.MAX_VALUE;
    private JsonObject serviceQuote = new JsonObject();
    private boolean serviceQuoteExpiredDisplayed;
    private JsonObject checkinCalendar = new JsonObject(), checkinQuote = new JsonObject();
    private String checkinMonth = "", selectedCheckinDate = "";
    private long checkinExpiresNanos = Long.MAX_VALUE, vipExpiresNanos = Long.MAX_VALUE;
    private boolean checkinExpiryDisplayed, vipExpiryDisplayed;
    private String pendingAction = "";
    private boolean pending, pendingExpired, quoteExpiryDisplayed;
    private JsonObject currentQuote = new JsonObject();
    private final long openedNanos = System.nanoTime();
    private long pageNanos = openedNanos, lastFrameNanos = openedNanos, closingNanos;
    private float displayedScroll, frameSeconds = 1F / 60F;
    private int panelOffset, pageOffset;
    private boolean closing;

    public CozyMenuScreen() { super(t("title")); }

    public static void openMenu() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (mc.gui.screen() instanceof CozyMenuScreen menu) menu.requestClose();
        else if (mc.gui.screen() == null) {
            CozyMenuScreen menu = new CozyMenuScreen();
            mc.gui.setScreen(menu);
            menu.sendRequest("open", "", 0);
        }
    }

    @Override protected void init() {
        panelWidth = Math.min(Math.max(220, width - 26), Math.max(254, Math.min(400, Math.round(width * .45F))));
        contentX = RAIL_WIDTH + 13;
        contentWidth = panelWidth - contentX - 15;
        bodyBottom = height - 63;
        acceptSnapshot(false);
        rebuildContent();
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean isInGameUi() { return true; }
    @Override public boolean shouldCloseOnEsc() { return false; }
    @Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // Keep the live world visible; Screen's default blur is deliberately not requested.
    }

    @Override public void tick() {
        if (minecraft.player == null || minecraft.level == null) { minecraft.gui.setScreen(null); return; }
        if (closing) {
            if (System.nanoTime() - closingNanos >= 140_000_000L) minecraft.gui.setScreen(null);
            return;
        }
        acceptSnapshot(true);
        if ((page == Page.SOCIAL || page == Page.HOME_POINT) && !pending && System.currentTimeMillis() >= travelRefreshAt) {
            travelRefreshAt = System.currentTimeMillis() + 2_000L;
            sendRequest("open", "", 0);
        }
        if (page == Page.PRODUCT && !pending && !quoteExpiryDisplayed && currentQuote.has("id") && quoteExpired(currentQuote)) {
            quoteExpiryDisplayed = true;
            feedback = t("quote_expired").getString();
            rebuildContent();
        }
        if (page == Page.SERVICES && !pending && !serviceQuoteExpiredDisplayed && serviceQuote.has("id") && serviceQuoteExpired()) {
            serviceQuoteExpiredDisplayed = true;
            feedback = t("service_quote_expired").getString();
            rebuildContent();
        }
        if (page == Page.CHECKIN_CONFIRM && !pending && !checkinExpiryDisplayed && checkinQuoteExpired()) {
            checkinExpiryDisplayed = true;
            feedback = t("checkin.quote_expired").getString();
            rebuildContent();
        }
        if (page == Page.VIP && !pending && !vipExpiryDisplayed && vipOfferExpired()) {
            vipExpiryDisplayed = true;
            feedback = t("vip_quote_expired").getString();
            rebuildContent();
        }
        if (!pending && delayedQuoteAt > 0 && System.currentTimeMillis() >= delayedQuoteAt) {
            delayedQuoteAt = 0;
            if (page == Page.PRODUCT && !selectedProductId.isBlank()) sendRequest("quote", selectedProductId, 0);
            else if (page == Page.VIP) sendRequest("vip_quote", "", 0);
        }
        if (pending && !pendingExpired && System.currentTimeMillis() - pendingAt > 10_000L) {
            pendingExpired = true;
            feedback = t("pending_timeout").getString();
            rebuildContent();
        }
    }

    private void acceptSnapshot(boolean rebuild) {
        JsonObject next = CozyClientBridge.snapshot();
        if (next == null) return;
        long nextResponse = number(next, "response_id", Long.MIN_VALUE);
        if (nextResponse == responseId) return;
        data = next;
        responseId = nextResponse;
        receivedAt = System.currentTimeMillis();
        travelRefreshAt = receivedAt + 2_000L;
        feedback = string(next, "message", "");
        String completedAction = pendingAction;
        String automaticCheckinToken = "";
        pending = false;
        pendingExpired = false;
        pendingAction = "";
        if (completedAction.equals("home_go") && bool(homeData(), "pending", false)) {
            requestClose();
            return;
        }
        if (next.has("quote") && next.get("quote").isJsonObject()) {
            currentQuote = next.getAsJsonObject("quote"); quoteExpiryDisplayed = false;
            long serverTime = number(next, "server_time", System.currentTimeMillis());
            long remainingMillis = Math.max(0, Math.min(60_000, number(currentQuote, "expires_at", serverTime) - serverTime));
            quoteExpiresNanos = System.nanoTime() + remainingMillis * 1_000_000L;
        }
        else if (completedAction.equals("trade") || completedAction.equals("quote")) {
            currentQuote = new JsonObject();
            if (completedAction.equals("trade")) delayedQuoteAt = System.currentTimeMillis() + 350;
        }
        if (completedAction.equals("trade") && page == Page.VIP && !next.has("vip_offer")) delayedQuoteAt = System.currentTimeMillis() + 350;
        if (next.has("vip_offer")) {
            vipExpiryDisplayed = false;
            vipExpiresNanos = expiryDeadline(object(next, "vip_offer"), next);
        }
        if (next.has("service_quote") && next.get("service_quote").isJsonObject()) {
            serviceQuote = next.getAsJsonObject("service_quote"); serviceQuoteExpiredDisplayed = false;
            long serverTime = number(next, "server_time", System.currentTimeMillis());
            long remainingMillis = Math.max(0, Math.min(60_000, number(serviceQuote, "expires_at", serverTime) - serverTime));
            serviceQuoteExpiresNanos = System.nanoTime() + remainingMillis * 1_000_000L;
        } else if (completedAction.equals("service_quote") || completedAction.equals("service_buy")) {
            serviceQuote = new JsonObject(); serviceQuoteExpiresNanos = Long.MAX_VALUE;
        }
        if (next.has("checkin_calendar") && next.get("checkin_calendar").isJsonObject()) {
            checkinCalendar = next.getAsJsonObject("checkin_calendar");
            checkinMonth = string(checkinCalendar, "month", "");
        }
        if (next.has("checkin_quote") && next.get("checkin_quote").isJsonObject()) {
            checkinQuote = next.getAsJsonObject("checkin_quote");
            checkinExpiryDisplayed = false;
            checkinExpiresNanos = expiryDeadline(checkinQuote, next);
        } else if (completedAction.startsWith("checkin_")) {
            checkinQuote = new JsonObject(); checkinExpiresNanos = Long.MAX_VALUE;
        }
        if (completedAction.equals("checkin_prepare") && page == Page.CHECKIN
            && selectedCheckinDate.equals(string(checkinQuote, "date", ""))) {
            if ("makeup".equals(string(checkinQuote, "kind", ""))) {
                page = Page.CHECKIN_CONFIRM; scroll = 0; displayedScroll = 0; pageNanos = System.nanoTime();
            } else if ("today".equals(string(checkinQuote, "kind", "")) && bool(checkinQuote, "enabled", false)
                && checkinQuote.has("expires_at") && !checkinQuoteExpired()) {
                automaticCheckinToken = string(checkinQuote, "token", "");
            }
        }
        if (completedAction.equals("checkin_confirm") && page == Page.CHECKIN_CONFIRM && next.has("checkin_calendar")) {
            page = Page.CHECKIN; scroll = 0; displayedScroll = 0; pageNanos = System.nanoTime();
        }
        products.clear();
        if (next.has("products") && next.get("products").isJsonArray()) {
            for (JsonElement element : next.getAsJsonArray("products")) {
                if (!element.isJsonObject() || products.size() >= 512) continue;
                JsonObject p = element.getAsJsonObject();
                String id = string(p, "id", "");
                if (id.isBlank()) continue;
                products.add(new Product(id, string(p, "item", "minecraft:barrier"),
                    string(p, "name", ""), canonicalCategory(string(p, "category", "misc")),
                    number(p, "buy", -1), number(p, "sell", -1)));
            }
        }
        if (rebuild) rebuildContent();
        // Only this newly completed matching prepare can trigger today's single confirmation.
        // Duplicate snapshots are rejected by responseId before reaching this branch.
        if (!automaticCheckinToken.isBlank()) confirmCheckin(automaticCheckinToken);
    }

    private void navigate(Page target) {
        if (closing) return;
        page = target; scroll = 0; displayedScroll = 0; pageNanos = System.nanoTime(); rebuildContent();
        if (target == Page.SOCIAL && !pending) sendRequest("open", "", 0);
    }
    private void requestClose() { if (!closing) { closing = true; closingNanos = System.nanoTime(); } }
    private void back() {
        if (pending && pendingAction.equals("checkin_confirm")) return;
        if (page == Page.CHECKIN_CONFIRM) cancelCheckin();
        else if (page == Page.CHECKIN) navigate(Page.GUIDE);
        else if (page == Page.PRODUCT) navigate(Page.SHOP);
        else if (page == Page.SHOP || page == Page.WITHDRAW || page == Page.MAIL || page == Page.VIP || page == Page.SERVICES) navigate(Page.ECONOMY);
        else if (page == Page.HOME_POINT) navigate(Page.TRAVEL);
        else if (page != Page.HOME) navigate(Page.HOME);
        else requestClose();
    }
    @Override public void onClose() { back(); }
    @Override public boolean keyPressed(KeyEvent event) {
        if (closing) return true;
        if (CozyClientEvents.MENU.matches(event)) { requestClose(); return true; }
        if (event.key() == InputConstants.KEY_ESCAPE) { back(); return true; }
        if (event.key() == InputConstants.KEY_PAGEDOWN) { setScroll(scroll + bodyBottom - bodyTop - 12); return true; }
        if (event.key() == InputConstants.KEY_PAGEUP) { setScroll(scroll - bodyBottom + bodyTop + 12); return true; }
        return super.keyPressed(event);
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (closing) return true;
        if (x >= RAIL_WIDTH + panelOffset && x <= panelWidth + panelOffset && y >= bodyTop && y < bodyBottom) {
            setScroll(scroll - (int) (vertical * 28)); return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }
    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return closing || super.mouseClicked(event, doubleClick);
    }

    private void rebuildContent() {
        // Screen.clearWidgets() removes children but does not clear container focus.
        setFocused(null); setDragging(false);
        clearWidgets(); lines.clear(); calendarHeadings.clear(); bodyButtons.clear(); allButtons.clear(); contentHeight = 0;
        bodyTop = 85;
        button(10, 13, 24, 22, t("close"), "", ItemStack.EMPTY, this::requestClose, false, false, true);
        int slot = height < 280 ? 32 : 42;
        nav(Page.HOME, "home", Items.CLOCK.getDefaultInstance(), 49, slot);
        nav(Page.TRAVEL, "travel", Items.COMPASS.getDefaultInstance(), 49 + slot, slot);
        nav(Page.ECONOMY, "economy", coin("copper"), 49 + slot * 2, slot);
        nav(Page.SOCIAL, "social", Items.ENDER_PEARL.getDefaultInstance(), 49 + slot * 3, slot);
        nav(Page.GUIDE, "guide", Items.WRITABLE_BOOK.getDefaultInstance(), 49 + slot * 4, slot);
        nav(Page.SETTINGS, "settings", Items.COMPARATOR.getDefaultInstance(), 49 + slot * 5, slot);
        button(contentX, height - 41, contentWidth, 19, t(page == Page.HOME ? "close_short" : "back"),
            "", ItemStack.EMPTY, this::back, false, false, false);
        button(panelWidth - 50, 63, 35, 15, t("refresh"), "", ItemStack.EMPTY, this::refresh, false, false, false);
        switch (page) {
            case HOME -> home();
            case HOME_POINT -> homePoint();
            case TRAVEL -> travel();
            case SOCIAL -> social();
            case ECONOMY -> economy();
            case SHOP -> shop();
            case PRODUCT -> productPage();
            case WITHDRAW -> withdraw();
            case MAIL -> mail();
            case VIP -> vip();
            case SERVICES -> services();
            case GUIDE -> guide();
            case CHECKIN -> checkinCalendarPage();
            case CHECKIN_CONFIRM -> checkinConfirmation();
            case SETTINGS -> settings();
        }
        setScroll(scroll);
    }

    private void nav(Page destination, String key, ItemStack icon, int y, int slot) {
        boolean selected = page == destination || destination == Page.ECONOMY &&
            (page == Page.SHOP || page == Page.PRODUCT || page == Page.WITHDRAW || page == Page.MAIL || page == Page.VIP || page == Page.SERVICES)
            || destination == Page.TRAVEL && page == Page.HOME_POINT
            || destination == Page.GUIDE && (page == Page.CHECKIN || page == Page.CHECKIN_CONFIRM);
        button(5, y, RAIL_WIDTH - 10, slot - 4, t(key), "", icon, () -> navigate(destination), false, selected, true);
    }

    private void home() {
        line(0, t("menu_hint"), ACCENT);
        int columns = contentWidth >= 210 ? 4 : 3;
        section(25, "economy");
        tile(0, columns, 43, "wallet_short", "wallet_desc", coin("copper"), () -> navigate(Page.ECONOMY));
        tile(1, columns, 43, "shop_label", "shop_description", Items.CHEST.getDefaultInstance(), () -> navigate(Page.SHOP));
        tile(2, columns, 43, "exchange_label", "exchange_description", coin("gold"), () -> navigate(Page.WITHDRAW));
        tile(3, columns, 43, "mail_label", "mail_description", Items.BARREL.getDefaultInstance(), this::openMail);
        tile(4, columns, 43, "vip_label", "vip_short", Items.GOLDEN_APPLE.getDefaultInstance(), this::openVip);
        int y = 43 + ((5 + columns - 1) / columns) * 62 + 9;
        section(y, "travel");
        tile(0, columns, y + 18, "my_home", "my_home_short", Items.STRAW_BED.getDefaultInstance(), () -> navigate(Page.HOME_POINT));
        tile(1, columns, y + 18, "player_teleport", "player_teleport_desc", Items.ENDER_PEARL.getDefaultInstance(), () -> navigate(Page.SOCIAL));
        tile(2, columns, y + 18, "map_label", "webmap_short", Items.FILLED_MAP.getDefaultInstance(), this::openMap);
    }
    private void section(int y, String key) {
        lines.add(new BodyLine(y, t(key), MUTED, false, true));
        contentHeight = Math.max(contentHeight, y + 12);
    }
    private void tile(int index, int columns, int startY, String key, String description, ItemStack icon, Runnable action) {
        int gap = 7, tileWidth = (contentWidth - (columns - 1) * gap) / columns;
        button(contentX + (index % columns) * (tileWidth + gap), bodyTop + startY + (index / columns) * 62,
            tileWidth, 55, t(key), t(description).getString(), icon, action, true, false, false);
    }
    private JsonObject teleportData() {
        return data.has("teleport") && data.get("teleport").isJsonObject() ? data.getAsJsonObject("teleport") : new JsonObject();
    }
    private void travel() {
        int columns = contentWidth >= 210 ? 4 : 3;
        tile(0, columns, 0, "my_home", "my_home_short", Items.STRAW_BED.getDefaultInstance(), () -> navigate(Page.HOME_POINT));
        tile(1, columns, 0, "map_label", "webmap_short", Items.FILLED_MAP.getDefaultInstance(), this::openMap);
        tile(2, columns, 0, "player_teleport", "player_teleport_desc", Items.ENDER_PEARL.getDefaultInstance(), () -> navigate(Page.SOCIAL));
        paragraph(72, t("travel_hint"), MUTED);
    }
    private void social() {
        JsonObject teleport = teleportData();
        int y = 0;
        JsonObject incoming = object(teleport, "incoming"), outgoing = object(teleport, "outgoing");
        if (!incoming.isEmpty()) {
            y += paragraph(y, t("tpa_incoming", string(incoming, "name", ""), number(incoming, "expires_seconds", 0)), GREEN) + 9;
            long fee = number(incoming, "fee", -1);
            y += paragraph(y, fee >= 0 ? t("tpa_locked_fee", string(incoming, "payer_name", string(incoming, "name", "")), format(fee)) : t("travel_fee_unavailable"), fee >= 0 ? ACCENT : MUTED) + 10;
            boolean loading = bool(incoming, "loading", false);
            int w = (contentWidth - 7) / 2;
            CozyButton yes = button(contentX, bodyTop + y, w, 26, t(loading ? "tpa_loading" : "tpa_accept"), "", ItemStack.EMPTY,
                () -> sendRequest("tpa_yes", "", 0), true, true, false);
            CozyButton no = button(contentX + w + 7, bodyTop + y, w, 26, t("tpa_deny"), "", ItemStack.EMPTY,
                () -> sendRequest("tpa_no", "", 0), true, false, false);
            yes.active = !pending && !loading && fee >= 0;
            no.active = !pending && !loading;
            y += 39;
        }
        if (!outgoing.isEmpty()) {
            y += paragraph(y, t(bool(outgoing, "loading", false) ? "tpa_outgoing_loading" : "tpa_outgoing",
                string(outgoing, "name", ""), number(outgoing, "expires_seconds", 0)), ACCENT) + 9;
            long fee = number(outgoing, "fee", -1);
            y += paragraph(y, fee >= 0 ? t("tpa_locked_fee", string(outgoing, "payer_name", string(data, "player_name", "")), format(fee)) : t("travel_fee_unavailable"), MUTED) + 15;
        }
        y += paragraph(y, t("tpa_payer_rule"), MUTED) + 9;
        String formula = string(teleport, "fee_formula", "");
        if (!formula.isBlank()) y += paragraph(y, t("travel_fee_formula", formula), MUTED) + 12;
        long cooldown = Math.max(0, number(teleport, "cooldown_seconds", 0));
        if (cooldown > 0) y += paragraph(y, t("tpa_cooldown", cooldown), MUTED) + 12;
        section(y, "online_players"); y += 20;
        int count = 0;
        if (teleport.has("players") && teleport.get("players").isJsonArray()) for (JsonElement element : teleport.getAsJsonArray("players")) {
            if (!element.isJsonObject()) continue;
            JsonObject player = element.getAsJsonObject();
            String id = string(player, "uuid", ""), name = string(player, "name", "");
            if (id.isBlank() || name.isBlank()) continue;
            boolean busy = bool(player, "busy", false);
            long fee = number(player, "fee", -1); String feeError = string(player, "fee_error", "");
            boolean priced = fee >= 0 && feeError.isBlank();
            String detail = busy ? t("tpa_busy").getString() : !feeError.isBlank() ? feeError : !priced ? t("travel_fee_unavailable").getString() : t("tpa_request_fee", format(fee)).getString();
            CozyButton target = button(contentX, bodyTop + y, contentWidth, 46, Component.literal(name), detail,
                Items.ENDER_PEARL.getDefaultInstance(), () -> sendRequest("tpa_request", id, 0), true, false, false);
            target.active = !pending && !busy && cooldown == 0 && incoming.isEmpty() && outgoing.isEmpty() && priced && balance() >= fee;
            target.setTooltip(Tooltip.create(Component.literal(detail + "\n" + t("tpa_payer_rule").getString() + (priced && balance() < fee ? "\n" + t("insufficient_balance").getString() : ""))));
            y += 53; count++;
        }
        if (count == 0) y += paragraph(y, t("tpa_no_players"), MUTED) + 13;
        paragraph(y + 4, t("tpa_hint"), MUTED);
    }
    private static JsonObject object(JsonObject value, String key) {
        return value.has(key) && value.get(key).isJsonObject() ? value.getAsJsonObject(key) : new JsonObject();
    }

    private JsonObject homeData() { return data.has("home") && data.get("home").isJsonObject() ? data.getAsJsonObject("home") : new JsonObject(); }
    private void homePoint() {
        JsonObject home = homeData();
        boolean set = bool(home, "set", false), teleporting = bool(home, "pending", false);
        long cooldown = Math.max(0, number(home, "cooldown_seconds", 0));
        int y = 0;
        line(y, t("my_home"), INK); y += 21;
        if (set) {
            String dimension = string(home, "dimension", "");
            Component dimensionName = switch (dimension) {
                case "minecraft:overworld" -> t("dimension.overworld");
                case "minecraft:the_nether" -> t("dimension.nether");
                case "minecraft:the_end" -> t("dimension.end");
                default -> t("dimension.other", dimension);
            };
            y += paragraph(y, t("home_location", dimensionName, coordinate(home, "x"), coordinate(home, "y"), coordinate(home, "z")), GREEN) + 16;
        } else y += paragraph(y, t("home_unset"), MUTED) + 16;
        y += paragraph(y, t("home_set_hint"), MUTED) + 13;
        CozyButton setHome = button(contentX, bodyTop + y, contentWidth, 27, t(set ? "home_replace" : "home_set"), "", ItemStack.EMPTY,
            () -> sendRequest("home_set", "", 0), true, false, false);
        setHome.active = !pending && !teleporting;
        y += 37;
        long fee = number(home, "fee", -1); String feeError = string(home, "fee_error", "");
        boolean priced = fee >= 0 && feeError.isBlank();
        if (set) {
            y += paragraph(y, priced ? t(teleporting ? "home_locked_fee" : "home_fee", format(fee)) : t("travel_fee_unavailable"), priced ? ACCENT : MUTED) + 9;
            if (!feeError.isBlank()) y += paragraph(y, Component.literal(feeError), MUTED) + 9;
            if (priced && balance() < fee) y += paragraph(y, t("insufficient_balance"), ACCENT) + 9;
        }
        String formula = string(home, "fee_formula", "");
        if (!formula.isBlank()) y += paragraph(y, t("travel_fee_formula", formula), MUTED) + 12;
        y += paragraph(y, t("travel_payment_rule"), MUTED) + 13;
        CozyButton goHome = button(contentX, bodyTop + y, contentWidth, 27, t("home_go"), "", ItemStack.EMPTY,
            () -> sendRequest("home_go", "", 0), true, true, false);
        goHome.active = !pending && set && !teleporting && cooldown == 0 && priced && balance() >= fee;
        y += 39;
        if (cooldown > 0) y += paragraph(y, t("home_cooldown", cooldown), ACCENT) + 12;
        paragraph(y, t("home_teleport_hint"), MUTED);
    }
    private static long coordinate(JsonObject json, String key) {
        try { return (long) Math.floor(json.get(key).getAsDouble()); }
        catch (RuntimeException ignored) { return 0; }
    }

    private void economy() {
        line(0, t("wallet"), MUTED);
        line(17, t("balance_points", format(balance())), GREEN);
        int y = 38 + paragraph(38, t("wallet_desc"), MUTED) + 15;
        int columns = contentWidth >= 210 ? 4 : 3;
        tile(0, columns, y, "shop_label", "shop_description", Items.CHEST.getDefaultInstance(), () -> navigate(Page.SHOP));
        tile(1, columns, y, "exchange_label", "exchange_description", coin("gold"), () -> navigate(Page.WITHDRAW));
        tile(2, columns, y, "mail_label", "mail_description", Items.BARREL.getDefaultInstance(), this::openMail);
        tile(3, columns, y, "vip_label", "vip_short", Items.GOLDEN_APPLE.getDefaultInstance(), this::openVip);
        tile(4, columns, y, "services_label", "services_short", Items.ANVIL.getDefaultInstance(), this::openServices);
        y += ((5 + columns - 1) / columns) * 62 + 14;
        y += paragraph(y, t("coin_ratio"), MUTED) + 13;
        paragraph(y, t("coin_use"), MUTED);
    }

    private void shop() {
        String[] categories = {"building", "ores", "farming", "equipment", "modded", "rare"};
        int categoryWidth = contentWidth / 3;
        for (int i = 0; i < categories.length; i++) {
            String cat = categories[i];
            button(contentX + categoryWidth * (i % 3), 85 + (i / 3) * 22, categoryWidth - 3, 19, t("category." + cat), "", ItemStack.EMPTY,
                () -> { category = cat; scroll = 0; displayedScroll = 0; pageNanos = System.nanoTime(); rebuildContent(); }, false, category.equals(cat), false);
        }
        bodyTop = 135;
        int y = 0;
        for (Product p : products) {
            if (!category.equals("all") && !category.equals(p.category)) continue;
            String name = p.name.isBlank() ? stack(p.item).getHoverName().getString() : p.name;
            String detail = p.id + " · " + t("category." + p.category).getString();
            CozyButton product = button(contentX, bodyTop + y, contentWidth, 46, Component.literal(name), detail,
                stack(p.item), () -> openProduct(p.id), true, false, false);
            product.active = !pending;
            y += 53;
        }
        if (y == 0) paragraph(12, t(products.isEmpty() ? "shop_loading" : "empty_category"), MUTED);
        else contentHeight = Math.max(contentHeight, y);
    }

    private void openProduct(String id) {
        if (pending) return;
        selectedProductId = id; currentQuote = new JsonObject(); quoteExpiryDisplayed = false;
        navigate(Page.PRODUCT); sendRequest("quote", id, 0);
    }
    private void productPage() {
        if (!selectedProductId.equals(string(currentQuote, "id", ""))) {
            paragraph(5, t("quote_loading"), MUTED); return;
        }
        JsonObject quote = currentQuote;
        String name = string(quote, "name", selectedProductId), item = string(quote, "item", "minecraft:barrier");
        button(contentX, bodyTop, contentWidth, 48, Component.literal(name), selectedProductId,
            stack(item), () -> {}, true, false, false).active = false;
        int y = 60;
        line(y, t("buy_mode"), GREEN); y += 17;
        y += quoteButtons(quote, "buy", y);
        line(y + 8, t("sell_mode"), ACCENT); y += 25;
        y += quoteButtons(quote, "sell", y);
        long remaining = number(quote, "daily_sell_remaining", 0);
        y += 9 + paragraph(y + 9, t(remaining < 0 ? "daily_unlimited" : "daily_remaining", format(remaining)), MUTED);
        y += 12 + paragraph(y + 12, t("direct_trade_tip"), MUTED);
        y += 12 + paragraph(y + 12, t("recycle_price_rule"), MUTED);
        paragraph(y + 12, t("quote_expiry"), MUTED);
    }
    private int quoteButtons(JsonObject quote, String kind, int relativeY) {
        int columns = contentWidth >= 265 ? 4 : 2;
        int[] amounts = {1, 16, 32, 64};
        int requiredWidth = 0;
        for (int count : amounts) {
            long total = number(option(quote, kind, count), "total", -1);
            if (total >= 0) requiredWidth = Math.max(requiredWidth, font.width(t("total_points", format(total))) + 10);
        }
        while (columns > 1 && (contentWidth - (columns - 1) * 5) / columns < requiredWidth) columns /= 2;
        int boxWidth = (contentWidth - (columns - 1) * 5) / columns;
        for (int i = 0; i < amounts.length; i++) {
            int count = amounts[i];
            JsonObject option = option(quote, kind, count);
            long total = number(option, "total", -1);
            String token = string(option, "token", "");
            String reason = string(option, "reason", "");
            boolean valid = bool(option, "enabled", false) && total > 0 && !token.isBlank()
                && count <= number(quote, "stack_max", 0) && !quoteExpired(quote);
            if (reason.isBlank() && !valid) reason = t("unavailable").getString();
            String detail = total <= 0 ? t("unavailable").getString() : t("total_points", format(total)).getString();
            CozyButton optionButton = button(contentX + (i % columns) * (boxWidth + 5), bodyTop + relativeY + (i / columns) * 40,
                boxWidth, 34, t("item_count", count), detail, ItemStack.EMPTY,
                () -> consumeQuote(token), true, kind.equals("buy"), false);
            optionButton.active = valid && !pending;
            optionButton.setTooltip(Tooltip.create(Component.literal(t(kind + "_action").getString() + " ×" + count + " · " + detail + (reason.isBlank() ? "" : "\n" + reason))));
        }
        return ((amounts.length + columns - 1) / columns) * 40;
    }
    private void consumeQuote(String token) {
        if (pending || token.isBlank()) return;
        sendRequest("trade", token, 0);
    }
    private static JsonObject option(JsonObject quote, String kind, int count) {
        if (!quote.has(kind) || !quote.get(kind).isJsonArray()) return new JsonObject();
        for (JsonElement element : quote.getAsJsonArray(kind))
            if (element.isJsonObject() && number(element.getAsJsonObject(), "count", 0) == count) return element.getAsJsonObject();
        return new JsonObject();
    }
    private boolean quoteExpired(JsonObject quote) {
        return quote.has("expires_at") && System.nanoTime() >= quoteExpiresNanos;
    }

    private void withdraw() {
        int y = paragraph(0, t("exchange_tip"), MUTED) + 14;
        String[] denominations = {"copper", "silver", "gold"};
        long[] values = {1, 100, 10000};
        int[] counts = {1, 16, 32, 64};
        for (int i = 0; i < denominations.length; i++) {
            String denomination = denominations[i]; long value = values[i];
            button(contentX, bodyTop + y, contentWidth, 41, t("coin." + denomination), t("coin_value", format(value)).getString(),
                coin(denomination), () -> {}, true, false, false).active = false;
            y += 49;
            int columns = contentWidth >= 265 ? 4 : 2, w = (contentWidth - (columns - 1) * 5) / columns;
            for (int j = 0; j < counts.length; j++) {
                int count = counts[j];
                // Denomination values are fixed by the currency contract, never used for server shop pricing.
                long cost = value * count;
                CozyButton convert = button(contentX + (j % columns) * (w + 5), bodyTop + y + (j / columns) * 40, w, 34,
                    t("coin_count", count), t("total_points", format(cost)).getString(), ItemStack.EMPTY,
                    () -> sendRequest("withdraw", denomination, count), true, false, false);
                convert.active = !pending && balance() >= cost;
            }
            y += (counts.length / columns) * 40 + 16;
        }
    }

    private void openMail() { if (!pending) { navigate(Page.MAIL); sendRequest("mail", "", 0); } }
    private void mail() {
        int y = paragraph(0, t("mail_hint"), MUTED) + 14;
        if (!data.has("mail_items") || !data.get("mail_items").isJsonArray() || data.getAsJsonArray("mail_items").isEmpty()) {
            paragraph(y, t("mail_empty"), MUTED); return;
        }
        int[] amounts = {1, 16, 32, 64};
        for (JsonElement element : data.getAsJsonArray("mail_items")) {
            if (!element.isJsonObject()) continue;
            JsonObject entry = element.getAsJsonObject();
            String id = string(entry, "id", ""), name = string(entry, "name", ""), item = string(entry, "item", "minecraft:barrier");
            long count = number(entry, "count", 0);
            if (id.isBlank() || count <= 0) continue;
            button(contentX, bodyTop + y, contentWidth, 43, Component.literal(name), t("mail_stored", format(count)).getString(),
                stack(item), () -> {}, true, false, false).active = false;
            y += 50;
            int w = contentWidth / 4;
            for (int j = 0; j < amounts.length; j++) {
                int amount = amounts[j];
                CozyButton claim = button(contentX + j * w, bodyTop + y, w - 3, 23, t("claim_count", amount), "", ItemStack.EMPTY,
                    () -> sendRequest("claim", id, amount), true, false, false);
                claim.active = !pending;
                claim.setTooltip(Tooltip.create(t("claim_up_to", amount)));
            }
            y += 36;
        }
    }
    private void openVip() { if (!pending) { navigate(Page.VIP); sendRequest("vip_quote", "", 0); } }
    private static long expiryDeadline(JsonObject offer, JsonObject snapshot) {
        long serverTime = number(snapshot, "server_time", System.currentTimeMillis());
        long remaining = Math.max(0, Math.min(60_000, number(offer, "expires_at", serverTime) - serverTime));
        return System.nanoTime() + remaining * 1_000_000L;
    }
    private boolean vipOfferExpired() {
        JsonObject offer = object(data, "vip_offer");
        return bool(offer, "enabled", false) && !string(offer, "token", "").isBlank()
            && offer.has("expires_at") && System.nanoTime() >= vipExpiresNanos;
    }
    private void vip() {
        line(0, t("vip_title"), ACCENT);
        int y = 19 + paragraph(19, t("vip_rules"), MUTED) + 14;
        JsonObject offer = object(data, "vip_offer");
        line(y, t("vip_price", format(number(offer, "cost", number(data, "vip_cost", 77777)))), GREEN); y += 21;
        boolean activeVip = bool(data, "vip_active", false);
        if (activeVip) y += paragraph(y, t("vip_active_remaining", vipDuration()), GREEN) + 12;
        y += paragraph(y, t("vip_renewal_rule"), MUTED) + 12;
        y += paragraph(y, t("vip_makeup_gift", number(offer, "makeup_credit_grant", 1)), ACCENT) + 12;
        line(y, t("checkin.credits", format(number(object(data, "checkin"), "makeup_credits", 0))), GREEN); y += 24;
        String token = string(offer, "token", "");
        CozyButton buyVip = button(contentX, bodyTop + y, contentWidth, 28, t(activeVip ? "vip_renew" : "vip_buy"), "", ItemStack.EMPTY,
            () -> { if (!vipOfferExpired()) consumeQuote(token); }, true, true, false);
        buyVip.active = !pending && bool(offer, "enabled", false) && (!activeVip || bool(offer, "can_renew", false)) && !token.isBlank() && !vipOfferExpired();
        String reason = string(offer, "reason", "");
        y += 39;
        if (!reason.isBlank()) y += paragraph(y, Component.literal(reason), MUTED) + 12;
        if (vipOfferExpired()) y += paragraph(y, t("vip_quote_expired"), ACCENT) + 12;
        paragraph(y, t("vip_checkin_bonus"), MUTED);
    }

    private void openCheckin(String month) {
        if (pending || closing) return;
        checkinQuote = new JsonObject(); checkinExpiryDisplayed = false;
        checkinCalendar = new JsonObject(); checkinMonth = month;
        navigate(Page.CHECKIN); sendRequest("checkin_calendar", month, 0);
    }
    private void prepareCheckin(String date) {
        if (pending || closing) return;
        selectedCheckinDate = date; checkinQuote = new JsonObject(); checkinExpiryDisplayed = false;
        sendRequest("checkin_prepare", date, 0);
    }
    private void cancelCheckin() {
        if (pending) return;
        // Calendar refresh revokes the server's unconsumed ticket, without spending credits.
        checkinQuote = new JsonObject(); checkinExpiryDisplayed = false;
        navigate(Page.CHECKIN);
        sendRequest("checkin_calendar", checkinMonth, 0);
    }
    private boolean checkinQuoteExpired() {
        return bool(checkinQuote, "enabled", false) && !string(checkinQuote, "token", "").isBlank()
            && checkinQuote.has("expires_at") && System.nanoTime() >= checkinExpiresNanos;
    }
    private void confirmCheckin(String token) {
        if (pending || token.isBlank() || !bool(checkinQuote, "enabled", false) || checkinQuoteExpired()
            || !token.equals(string(checkinQuote, "token", ""))) return;
        sendRequest("checkin_confirm", token, 0);
    }
    private void checkinCalendarPage() {
        JsonObject state = object(data, "checkin");
        int y = paragraph(0, t("checkin.summary", number(state, "current_streak", 0), number(state, "longest_streak", 0)), GREEN) + 9;
        line(y, t("checkin.credits", format(number(state, "makeup_credits", 0))), ACCENT); y += 20;
        YearMonth month;
        try { month = YearMonth.parse(string(checkinCalendar, "month", "")); }
        catch (DateTimeParseException ignored) { paragraph(y, t(pending ? "checkin.loading" : "checkin.unavailable"), MUTED); return; }
        String previous = string(checkinCalendar, "previous_month", ""), next = string(checkinCalendar, "next_month", "");
        CozyButton backMonth = button(contentX, bodyTop + y, 25, 23, Component.literal("‹"), "", ItemStack.EMPTY,
            () -> openCheckin(previous), true, false, false);
        backMonth.active = !pending && validMonth(previous);
        backMonth.setTooltip(Tooltip.create(t("checkin.previous")));
        CozyButton monthTitle = button(contentX + 29, bodyTop + y, contentWidth - 58, 23,
            t("checkin.month", month.getYear(), month.getMonthValue()), "", ItemStack.EMPTY, () -> {}, true, false, false);
        monthTitle.active = false;
        CozyButton nextMonth = button(contentX + contentWidth - 25, bodyTop + y, 25, 23, Component.literal("›"), "", ItemStack.EMPTY,
            () -> openCheckin(next), true, false, false);
        nextMonth.active = !pending && validMonth(next);
        nextMonth.setTooltip(Tooltip.create(t("checkin.next"))); y += 32;
        int gap = 3, cellWidth = (contentWidth - 6 * gap) / 7, stride = cellWidth + gap;
        for (int i = 0; i < 7; i++) calendarHeadings.add(new CalendarHeading(i * stride, y, cellWidth, t("checkin.weekday." + (i + 1))));
        y += 16;
        int offset = month.atDay(1).getDayOfWeek().getValue() - 1;
        for (int dayNumber = 1; dayNumber <= month.lengthOfMonth(); dayNumber++) {
            String date = month.atDay(dayNumber).toString(); JsonObject day = calendarDay(date);
            String status = string(day, "status", "unavailable");
            boolean canPrepare = bool(day, "can_prepare", false) && (status.equals("today") || status.equals("missed"));
            String visual = status.equals("signed") ? (bool(day, "makeup", false) ? "makeup" : "signed") : canPrepare ? status : "disabled";
            int index = offset + dayNumber - 1;
            CozyButton cell = button(contentX + (index % 7) * stride, bodyTop + y + (index / 7) * 37,
                cellWidth, 33, Component.literal(Integer.toString(dayNumber)), "", ItemStack.EMPTY,
                () -> prepareCheckin(date), true, status.equals("today") && canPrepare, false);
            cell.calendarVisual = visual;
            cell.active = canPrepare && !pending;
            String hint = date + " · " + t("checkin.status." + visual).getString();
            long reward = number(day, "reward", -1);
            if (status.equals("signed") && reward >= 0) hint += "\n" + t("checkin.settled_reward", format(reward)).getString();
            else if (canPrepare) hint += "\n" + (reward > 0 ? t("checkin.preview_reward", format(reward))
                : t(status.equals("today") ? "checkin.today_hint" : "checkin.click_quote")).getString();
            cell.setTooltip(Tooltip.create(Component.literal(hint)));
        }
        y += ((offset + month.lengthOfMonth() + 6) / 7) * 37 + 10;
        y += paragraph(y, t("checkin.legend"), MUTED) + 11;
        String registered = string(checkinCalendar, "registered_date", string(state, "registered_date", ""));
        if (!registered.isBlank()) y += paragraph(y, t("checkin.registered", registered), MUTED) + 11;
        y += paragraph(y, t("checkin.time_rule", string(checkinCalendar, "today", string(state, "today", ""))), MUTED) + 11;
        y += paragraph(y, t("checkin.reward_rule"), MUTED) + 11;
        paragraph(y, t("checkin.makeup_rule"), MUTED);
    }
    private static boolean validMonth(String month) {
        try { YearMonth.parse(month); return true; } catch (DateTimeParseException ignored) { return false; }
    }
    private JsonObject calendarDay(String date) {
        if (checkinCalendar.has("days") && checkinCalendar.get("days").isJsonArray()) {
            for (JsonElement element : checkinCalendar.getAsJsonArray("days"))
                if (element.isJsonObject() && date.equals(string(element.getAsJsonObject(), "date", ""))) return element.getAsJsonObject();
        }
        return new JsonObject();
    }
    private void checkinConfirmation() {
        JsonObject quote = checkinQuote;
        boolean makeup = "makeup".equals(string(quote, "kind", ""));
        int y = paragraph(0, t(makeup ? "checkin.confirm_makeup" : "checkin.confirm_today", string(quote, "date", selectedCheckinDate)), ACCENT) + 14;
        long reward = number(quote, "balance_reward", -1);
        if (reward >= 0) { line(y, t("checkin.receive", format(reward)), GREEN); y += 23; }
        y += paragraph(y, t("checkin.streak_after", number(quote, "streak_for_date", 0), number(quote, "longest_streak_after", 0)), MUTED) + 12;
        y += paragraph(y, t(bool(quote, "uses_makeup_credit", false) ? "checkin.consume_credit" : "checkin.no_credit"), ACCENT) + 12;
        line(y, t("checkin.credits", format(number(object(data, "checkin"), "makeup_credits", 0))), MUTED); y += 23;
        if (quote.has("milestones") && quote.get("milestones").isJsonArray()) for (JsonElement element : quote.getAsJsonArray("milestones")) {
            if (!element.isJsonObject()) continue;
            JsonObject rewardRow = element.getAsJsonObject();
            y += paragraph(y, t("checkin.milestone", number(rewardRow, "days", 0), string(rewardRow, "name", ""), number(rewardRow, "count", 0)), GREEN) + 10;
        }
        String reason = string(quote, "reason", "");
        if (!reason.isBlank()) y += paragraph(y, Component.literal(reason), ACCENT) + 12;
        if (checkinQuoteExpired()) y += paragraph(y, t("checkin.quote_expired"), ACCENT) + 12;
        y += paragraph(y, t("checkin.confirm_rule"), MUTED) + 13;
        String token = string(quote, "token", "");
        CozyButton confirm = button(contentX, bodyTop + y, contentWidth, 28, t(makeup ? "checkin.do_makeup" : "checkin.do_today"), "", ItemStack.EMPTY,
            () -> confirmCheckin(token), true, true, false);
        confirm.active = !pending && bool(quote, "enabled", false) && !token.isBlank() && reward >= 0 && !checkinQuoteExpired();
        y += 36;
        CozyButton cancel = button(contentX, bodyTop + y, contentWidth, 25, t("checkin.cancel"), "", ItemStack.EMPTY,
            this::cancelCheckin, true, false, false);
        cancel.active = !pending;
    }

    private void openServices() {
        if (pending) return;
        serviceQuote = new JsonObject(); serviceQuoteExpiredDisplayed = false;
        navigate(Page.SERVICES); sendRequest("service_quote", "repair", 0);
    }
    private boolean serviceQuoteExpired() {
        return bool(serviceQuote, "enabled", false) && !string(serviceQuote, "id", "").isBlank()
            && serviceQuote.has("expires_at") && System.nanoTime() >= serviceQuoteExpiresNanos;
    }
    private void services() {
        line(0, t("services_title"), INK);
        int y = 20 + paragraph(20, t("repair_description"), MUTED) + 14;
        String token = string(serviceQuote, "id", ""); long fee = number(serviceQuote, "fee", -1);
        long missing = number(serviceQuote, "missing_durability", 0), maximum = number(serviceQuote, "max_durability", 0);
        String reason = string(serviceQuote, "reason", "");
        boolean priced = "repair".equals(string(serviceQuote, "service", "")) && fee >= 0 && missing > 0 && maximum >= missing;
        boolean offered = priced && !token.isBlank() && bool(serviceQuote, "enabled", true);
        if (priced) {
            y += paragraph(y, t("repair_item", string(serviceQuote, "item_name", "")), GREEN) + 12;
            y += paragraph(y, t("repair_damage", format(number(serviceQuote, "missing_durability", 0)), format(number(serviceQuote, "max_durability", 0))), MUTED) + 12;
            line(y, t("repair_fee", format(fee)), ACCENT); y += 24;
            if (serviceQuoteExpired()) y += paragraph(y, t("service_quote_expired"), ACCENT) + 12;
            else if (reason.isBlank() && balance() < fee) y += paragraph(y, t("insufficient_balance"), ACCENT) + 12;
        } else y += paragraph(y, t(pending ? "repair_loading" : "repair_unavailable"), MUTED) + 13;
        if (!reason.isBlank()) y += paragraph(y, Component.literal(reason), ACCENT) + 12;
        line(y, t("balance_points", format(balance())), GREEN); y += 25;
        CozyButton repair = button(contentX, bodyTop + y, contentWidth, 29, t("repair_confirm"), "", Items.ANVIL.getDefaultInstance(),
            () -> sendRequest("service_buy", token, 1), true, true, false);
        repair.active = offered && !pending && !serviceQuoteExpired() && balance() >= fee;
        y += 39;
        CozyButton refreshOffer = button(contentX, bodyTop + y, contentWidth, 26, t("repair_refresh"), "", ItemStack.EMPTY,
            () -> sendRequest("service_quote", "repair", 0), true, false, false);
        refreshOffer.active = !pending; y += 37;
        y += paragraph(y, t("repair_quote_hint"), MUTED) + 12;
        paragraph(y, t("repair_formula"), MUTED);
    }

    private void sendRequest(String action, String id, int count) {
        if (closing || pending && !pendingExpired) return;
        pending = true; pendingExpired = false; pendingAt = System.currentTimeMillis(); pendingAction = action;
        feedback = t(action.equals("trade") || action.equals("withdraw") || action.equals("claim") || action.equals("service_buy") || action.equals("checkin_confirm") ? "pending" : "refreshing").getString();
        delayedQuoteAt = 0;
        rebuildContent();
        CozyClientBridge.request(action, id, count);
    }

    private void guide() {
        card(contentX, 0, contentWidth, 49, "checkin.title", "checkin.entry", Items.WRITABLE_BOOK.getDefaultInstance(), () -> openCheckin(""));
        int y = 64;
        y = guideSection(y, "guide.coins_title", "guide.coins");
        y = guideSection(y, "guide.loot_title", "guide.loot");
        y = guideSection(y, "guide.farm_title", "guide.farm");
        y = guideSection(y, "guide.travel_title", "guide.travel");
        card(contentX, y + 7, contentWidth, 48, "keybinds", "keybinds_desc", Items.COMPARATOR.getDefaultInstance(),
            () -> minecraft.gui.setScreen(new KeyBindsScreen(this, minecraft.options)));
    }

    private int guideSection(int y, String title, String description) {
        line(y, t(title), INK);
        int h = paragraph(y + 17, t(description), MUTED);
        return y + 17 + h + 20;
    }

    private void settings() {
        card(contentX, 0, contentWidth, 53, "game_options", "game_options_desc", Items.REPEATER.getDefaultInstance(),
            () -> minecraft.gui.setScreen(new OptionsScreen(this, minecraft.options)));
        card(contentX, 62, contentWidth, 53, "keybinds", "keybinds_desc", Items.COMPARATOR.getDefaultInstance(),
            () -> minecraft.gui.setScreen(new KeyBindsScreen(this, minecraft.options)));
        paragraph(129, t("settings_hint"), MUTED);
        card(contentX, 187, contentWidth, 53, "webmap", "webmap_description", Items.FILLED_MAP.getDefaultInstance(), this::openMap);
    }

    private void openMap() { ConfirmLinkScreen.confirmLinkNow(this, MAP_URL); }
    private void refresh() {
        if (pending && !pendingExpired) return;
        if (page == Page.PRODUCT && !selectedProductId.isBlank()) sendRequest("quote", selectedProductId, 0);
        else if (page == Page.VIP) sendRequest("vip_quote", "", 0);
        else if (page == Page.MAIL) sendRequest("mail", "", 0);
        else if (page == Page.SERVICES) sendRequest("service_quote", "repair", 0);
        else if (page == Page.CHECKIN) sendRequest("checkin_calendar", checkinMonth, 0);
        else if (page == Page.CHECKIN_CONFIRM) {
            checkinQuote = new JsonObject(); navigate(Page.CHECKIN);
            sendRequest("checkin_prepare", selectedCheckinDate, 0);
        }
        else sendRequest("open", "", 0);
    }

    private void card(int x, int relativeY, int w, int h, String title, String description, ItemStack icon, Runnable action) {
        button(x, bodyTop + relativeY, w, h, t(title), t(description).getString(), icon, action, true, false, false);
    }
    private CozyButton button(int x, int y, int w, int h, Component label, String detail, ItemStack icon,
                              Runnable action, boolean body, boolean selected, boolean rail) {
        CozyButton button = new CozyButton(x, y, w, h, label, detail, icon, action, body, selected, rail);
        allButtons.add(button);
        addRenderableWidget(button);
        if (body) { bodyButtons.add(button); contentHeight = Math.max(contentHeight, button.relativeY + h + 5); }
        return button;
    }
    private void line(int y, Component text, int color) {
        lines.add(new BodyLine(y, text, color, false, false)); contentHeight = Math.max(contentHeight, y + 12);
    }
    private int paragraph(int y, Component text, int color) {
        int h = Math.max(10, font.split(text, contentWidth - 4).size() * 12);
        lines.add(new BodyLine(y, text, color, true, false)); contentHeight = Math.max(contentHeight, y + h); return h;
    }
    private int maxScroll() { return Math.max(0, contentHeight - (bodyBottom - bodyTop)); }
    private void setScroll(int value) {
        scroll = Math.max(0, Math.min(maxScroll(), value));
        displayedScroll = Math.max(0, Math.min(maxScroll(), displayedScroll));
        updateButtonPositions();
    }
    private void updateButtonPositions() {
        for (CozyButton button : allButtons) {
            button.setX(button.baseX + panelOffset);
            if (button.body) button.setY(bodyTop + button.relativeY - Math.round(displayedScroll) + pageOffset);
        }
    }
    private static float easeOut(float value) { float t = Math.max(0, Math.min(1, value)); return 1 - (1 - t) * (1 - t) * (1 - t); }
    private void animate() {
        long now = System.nanoTime();
        frameSeconds = Math.max(0, Math.min(.1F, (now - lastFrameNanos) / 1_000_000_000F)); lastFrameNanos = now;
        float open = easeOut((now - openedNanos) / 180_000_000F);
        panelOffset = -Math.round(Math.min(56, panelWidth / 5) * (1 - open));
        if (closing) {
            float close = Math.max(0, Math.min(1, (now - closingNanos) / 140_000_000F));
            panelOffset -= Math.round((panelWidth + 16) * close * close * close);
        }
        pageOffset = Math.round(8 * (1 - easeOut((now - pageNanos) / 150_000_000F)));
        displayedScroll += (scroll - displayedScroll) * (1 - (float) Math.exp(-frameSeconds * 19));
        if (Math.abs(scroll - displayedScroll) < .1F) displayedScroll = scroll;
        updateButtonPositions();
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        animate();
        g.pose().pushMatrix();
        g.pose().translate(panelOffset, 0);
        g.fill(panelWidth, 0, panelWidth + 3, height, 0x28000000);
        g.fillGradient(panelWidth + 3, 0, panelWidth + 13, height, 0x16000000, 0x00000000);
        g.fill(0, 0, RAIL_WIDTH, height, RAIL);
        g.fill(RAIL_WIDTH, 0, panelWidth, height, PAPER);
        g.fill(RAIL_WIDTH, 0, panelWidth, 3, 0xFFD1AE78);
        g.fill(RAIL_WIDTH, height - 18, panelWidth, height, 0xFFF2E8D6);
        g.horizontalLine(RAIL_WIDTH, panelWidth - 1, height - 19, LINE);

        int avatarX = contentX + 1;
        rounded(g, avatarX - 3, 16, 33, 33, 0xFFF0DDC5);
        if (minecraft.player != null) PlayerFaceExtractor.extractRenderState(g, minecraft.player.getSkin(), avatarX, 19, 27);
        rounded(g, avatarX + 23, 42, 6, 6, GREEN);
        String playerName = string(data, "player_name", minecraft.player == null ? "" : minecraft.player.getName().getString());
        g.text(font, clip(playerName, contentWidth - 39), contentX + 39, 17, INK, false);
        String status = bool(data, "vip_active", false) ? t("vip_remaining_short", vipDuration()).getString()
            : t("player_level", minecraft.player == null ? 0 : minecraft.player.experienceLevel).getString();
        g.text(font, clip(status, contentWidth - 39), contentX + 39, 30, bool(data, "vip_active", false) ? ACCENT : MUTED, false);
        g.text(font, clip(t("balance_short", format(balance())).getString(), contentWidth - 39), contentX + 39, 43, GREEN, false);
        g.horizontalLine(contentX, panelWidth - 15, 56, LINE);
        g.text(font, pageTitle(), contentX, 66, ACCENT, false);

        g.enableScissor(contentX - 2, bodyTop, panelWidth - 9, bodyBottom);
        if (page == Page.HOME) rounded(g, contentX, bodyTop - Math.round(displayedScroll) + pageOffset - 4,
            contentWidth, 21, 0xFFFFF2D3);
        for (BodyLine line : lines) {
            int y = bodyTop + line.y - Math.round(displayedScroll) + pageOffset;
            if (line.wrap) g.textWithWordWrap(font, line.text, contentX + 1, y, contentWidth - 4, line.color, false);
            else g.text(font, clip(line.text.getString(), contentWidth), contentX + 1, y, line.color, false);
            if (line.section) g.horizontalLine(contentX + Math.min(contentWidth - 5, font.width(line.text) + 9), panelWidth - 15, y + 4, LINE);
        }
        for (CalendarHeading heading : calendarHeadings) {
            int y = bodyTop + heading.y - Math.round(displayedScroll) + pageOffset;
            g.text(font, heading.text, contentX + heading.x + (heading.width - font.width(heading.text)) / 2, y, MUTED, false);
        }
        g.disableScissor();
        if (maxScroll() > 0) {
            int track = bodyBottom - bodyTop, thumb = Math.max(16, track * track / Math.max(track, contentHeight));
            int thumbY = bodyTop + Math.round((track - thumb) * displayedScroll / maxScroll());
            g.fill(panelWidth - 7, bodyTop, panelWidth - 5, bodyBottom, LINE);
            g.fill(panelWidth - 7, thumbY, panelWidth - 5, thumbY + thumb, 0xFFBCA487);
        }
        String footer = pending ? (pendingExpired ? t("pending_short_timeout").getString() : t("pending").getString()) : feedback;
        if (footer.isBlank()) footer = t("escape_hint").getString();
        g.text(font, clip(footer, contentWidth), contentX, height - 55, pending ? ACCENT : MUTED, false);
        long ticks = minecraft.level == null ? 0 : minecraft.level.getOverworldClockTime();
        long minutes = Math.floorMod(ticks, 24000) * 60 / 1000;
        String time = String.format(Locale.ROOT, "%02d:%02d", (minutes / 60 + 6) % 24, minutes % 60);
        g.text(font, t("world_clock", Math.floorDiv(ticks, 24000) + 1, time), contentX - 3, height - 12, MUTED, false);
        if (minecraft.player != null) {
            var pos = minecraft.player.blockPosition();
            String coordinates = pos.getX() + " / " + pos.getY() + " / " + pos.getZ();
            int maxWidth = contentWidth - font.width(t("world_clock", Math.floorDiv(ticks, 24000) + 1, time)) - 5;
            coordinates = clip(coordinates, maxWidth);
            g.text(font, coordinates, panelWidth - 12 - font.width(coordinates), height - 12, MUTED, false);
        }
        g.pose().popMatrix();
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        if (!closing && !feedback.isBlank() && mouseY >= height - 61 && mouseY < height - 42
            && mouseX >= contentX + panelOffset && mouseX < panelWidth + panelOffset)
            g.setTooltipForNextFrame(font, font.split(Component.literal(feedback), contentWidth - 12), tooltipPositioner(), mouseX, mouseY, false);
    }

    private Component pageTitle() {
        return t(switch (page) {
            case HOME -> "title"; case HOME_POINT -> "my_home"; case TRAVEL -> "travel"; case SOCIAL -> "social"; case ECONOMY -> "economy"; case SHOP -> "server_shop";
            case PRODUCT -> "product_detail"; case WITHDRAW -> "coin_exchange"; case MAIL -> "mailbox";
            case VIP -> "vip"; case SERVICES -> "services_label"; case GUIDE -> "life_guide";
            case CHECKIN -> "checkin.title"; case CHECKIN_CONFIRM -> "checkin.confirm_title"; case SETTINGS -> "settings";
        });
    }
    private long balance() { return Math.max(0, number(data, "balance", 0)); }
    private String vipDuration() {
        long remaining = Math.max(0, number(data, "vip_remaining_seconds", 0) - Math.max(0, System.currentTimeMillis() - receivedAt) / 1000);
        if (remaining >= 86400) return t("duration_days_hours", remaining / 86400, remaining % 86400 / 3600).getString();
        if (remaining >= 3600) return t("duration_hours_minutes", remaining / 3600, remaining % 3600 / 60).getString();
        return t("duration_minutes", Math.max(0, (remaining + 59) / 60)).getString();
    }
    private String clip(String value, int available) {
        if (available <= 0) return "";
        if (font.width(value) <= available) return value;
        return font.plainSubstrByWidth(value, Math.max(0, available - font.width("…"))) + "…";
    }
    private static String format(long value) { return NUMBERS.format(value); }
    private static Component t(String key, Object... args) {
        if (args.length == 0 && (key.equals("escape_hint") || key.equals("home_tip"))) {
            return Component.translatable("menu.cozylife_economy." + key,
                CozyClientEvents.MENU.getTranslatedKeyMessage());
        }
        return Component.translatable("menu.cozylife_economy." + key, args);
    }
    private static String string(JsonObject json, String key, String fallback) {
        try { return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }
    private static long number(JsonObject json, String key, long fallback) {
        try { return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsLong() : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }
    private static boolean bool(JsonObject json, String key, boolean fallback) {
        try { return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsBoolean() : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }
    private static String canonicalCategory(String category) {
        return switch (category.toLowerCase(Locale.ROOT)) {
            case "building", "blocks", "建材" -> "building";
            case "ores", "ore", "mining", "矿物", "矿产" -> "ores";
            case "equipment", "tools", "工具装备" -> "equipment";
            case "modded", "模组材料" -> "modded";
            case "rare", "稀有物品" -> "rare";
            default -> "farming";
        };
    }
    private static ItemStack stack(String id) {
        Identifier key = Identifier.tryParse(id);
        return key == null ? Items.BARRIER.getDefaultInstance() : BuiltInRegistries.ITEM.getOptional(key)
            .map(item -> item.getDefaultInstance()).orElseGet(Items.BARRIER::getDefaultInstance);
    }
    private static ItemStack coin(String denomination) { return stack("cozylife_economy:" + denomination + "_coin"); }
    private static void rounded(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        if (w < 6 || h < 6) { g.fill(x, y, x + w, y + h, color); return; }
        g.fill(x + 2, y, x + w - 2, y + h, color);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, color);
        g.fill(x, y + 2, x + w, y + h - 2, color);
    }
    private ClientTooltipPositioner tooltipPositioner() {
        return (screenWidth, screenHeight, x, y, tooltipWidth, tooltipHeight) -> {
            int minX = Math.max(6, RAIL_WIDTH + panelOffset + 7);
            int maxX = Math.max(minX, panelWidth + panelOffset - tooltipWidth - 8);
            int tooltipX = Math.max(minX, Math.min(x + 10, maxX));
            int tooltipY = Math.max(7, Math.min(y + 15, screenHeight - tooltipHeight - 8));
            return new Vector2i(tooltipX, tooltipY);
        };
    }
    private record Product(String id, String item, String name, String category, long buy, long sell) {}
    private record BodyLine(int y, Component text, int color, boolean wrap, boolean section) {}
    private record CalendarHeading(int x, int y, int width, Component text) {}

    private static int blend(int from, int to, float amount) {
        int result = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int a = (from >>> shift) & 255, b = (to >>> shift) & 255;
            result |= Math.round(a + (b - a) * amount) << shift;
        }
        return result;
    }

    /** A native narrated/focusable widget with the pack's visual style and viewport-aware hit testing. */
    private final class CozyButton extends AbstractWidget {
        private final String detail;
        private final ItemStack icon;
        private final Runnable action;
        private final boolean body, selected, rail;
        private final int relativeY, baseX;
        private float hoverProgress;
        private String calendarVisual = "";
        private Tooltip ownTooltip;
        private long tooltipSince;
        CozyButton(int x, int y, int width, int height, Component label, String detail, ItemStack icon,
                   Runnable action, boolean body, boolean selected, boolean rail) {
            super(x, y, width, height, label);
            this.detail = detail; this.icon = icon; this.action = action;
            this.body = body; this.selected = selected; this.rail = rail; this.relativeY = y - bodyTop; this.baseX = x;
            setTooltip(Tooltip.create(detail.isBlank() ? label : Component.literal(label.getString() + " · " + detail)));
        }
        @Override public void setTooltip(Tooltip tooltip) { super.setTooltip(tooltip); ownTooltip = tooltip; }
        private boolean inViewport(double mouseY) { return !body || mouseY >= bodyTop && mouseY < bodyBottom; }
        @Override public boolean isMouseOver(double x, double y) { return !closing && inViewport(y) && super.isMouseOver(x, y); }
        @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            return !closing && inViewport(event.y()) && super.mouseClicked(event, doubleClick);
        }
        @Override public boolean shouldTakeFocusAfterInteraction() {
            // onClick may have rebuilt the page before the container asks for focus.
            return allButtons.contains(this) && super.shouldTakeFocusAfterInteraction();
        }
        @Override public void setFocused(boolean focused) {
            focused = focused && allButtons.contains(this);
            super.setFocused(focused);
            if (focused && body) {
                if (getY() < bodyTop) setScroll(relativeY);
                else if (getBottom() > bodyBottom) setScroll(relativeY + height - (bodyBottom - bodyTop));
                displayedScroll = scroll;
                updateButtonPositions();
            }
        }
        @Override public void onClick(MouseButtonEvent event, boolean doubleClick) { if (!closing) action.run(); }
        @Override public boolean keyPressed(KeyEvent event) {
            if (!closing && active && isFocused() && (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_SPACE || event.key() == InputConstants.KEY_NUMPADENTER)) {
                playDownSound(minecraft.getSoundManager()); action.run(); return true;
            }
            return false;
        }
        @Override protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
        @Override protected void extractTooltipForNextRenderPass(GuiGraphicsExtractor g, int mouseX, int mouseY) {
            boolean hover = inViewport(mouseY) && mouseX >= getX() && mouseX < getRight() && mouseY >= getY() && mouseY < getBottom();
            boolean focus = isFocused() && (!body || getY() >= bodyTop && getBottom() <= bodyBottom);
            if (closing || ownTooltip == null || !hover && !focus || body && (getBottom() <= bodyTop || getY() >= bodyBottom)) {
                tooltipSince = 0; isHovered = false; return;
            }
            if (tooltipSince == 0) tooltipSince = System.nanoTime();
            if (System.nanoTime() - tooltipSince < 350_000_000L) return;
            g.setTooltipForNextFrame(font, ownTooltip.toCharSequence(minecraft), tooltipPositioner(),
                hover ? mouseX : getX() + width / 2, hover ? mouseY : getBottom(), false);
        }
        @Override protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            if (body && (getBottom() <= bodyTop || getY() >= bodyBottom)) return;
            if (body) g.enableScissor(contentX - 2 + panelOffset, bodyTop, panelWidth - 9 + panelOffset, bodyBottom);
            boolean hover = !closing && active && (isFocused() || isMouseOver(mouseX, mouseY));
            hoverProgress += ((hover ? 1F : 0F) - hoverProgress) * (1 - (float) Math.exp(-frameSeconds * 18));
            int background = rail ? (selected ? 0xFFF1DFC0 : blend(RAIL, 0xFF967054, hoverProgress))
                : selected ? blend(0xFFE8D3AB, 0xFFF1E2C5, hoverProgress) : blend(0xFFFFFCF5, 0xFFF6E8D0, hoverProgress);
            int border = rail ? background : selected ? 0xFFC8A375 : blend(LINE, 0xFFC8A375, hoverProgress);
            if (body && height >= 40) rounded(g, getX() + 1, getY() + 2, width, height, 0x1C71513D);
            rounded(g, getX(), getY(), width, height, border);
            rounded(g, getX() + 1, getY() + 1, width - 2, height - 2, background);
            if (isFocused()) g.outline(getX() - 1, getY() - 1, width + 2, height + 2, ACCENT);
            int foreground = rail && !selected ? 0xFFFFEAD0 : !active ? MUTED : INK;
            if (!calendarVisual.isBlank()) {
                boolean marked = calendarVisual.equals("signed") || calendarVisual.equals("makeup") || calendarVisual.equals("missed");
                int dateColor = calendarVisual.equals("disabled") ? 0xFFB5AA9C : calendarVisual.equals("signed") ? GREEN : INK;
                String label = getMessage().getString();
                g.text(font, label, getX() + (width - font.width(label)) / 2, getY() + (marked ? 5 : 12), dateColor, false);
                if (marked) {
                    String mark = calendarVisual.equals("signed") ? "✓" : calendarVisual.equals("makeup") ? "补" : "×";
                    g.text(font, mark, getX() + (width - font.width(mark)) / 2, getY() + 20,
                        calendarVisual.equals("signed") ? GREEN : calendarVisual.equals("makeup") ? ACCENT : 0xFFAD7058, false);
                }
            } else if (rail && !icon.isEmpty()) {
                g.item(icon, getX() + (width - 16) / 2, getY() + 3);
                g.text(font, clip(getMessage().getString(), width - 4), getX() + (width - font.width(getMessage())) / 2,
                    getBottom() - 11, foreground, false);
            } else if (body && !icon.isEmpty()) {
                boolean compact = width < 132;
                if (compact) {
                    rounded(g, getX() + (width - 25) / 2, getY() + 6, 25, 25, 0xFFEDE7D6);
                    g.item(icon, getX() + (width - 16) / 2, getY() + 10);
                    String compactLabel = clip(getMessage().getString(), width - 10);
                    g.text(font, compactLabel, getX() + (width - font.width(compactLabel)) / 2, getY() + 37, foreground, false);
                    if (height >= 56) g.text(font, clip(detail, width - 14), getX() + 7, getY() + 46, MUTED, false);
                } else {
                    rounded(g, getX() + 8, getY() + (height - 28) / 2, 28, 28, 0xFFEDE7D6);
                    g.item(icon, getX() + 14, getY() + (height - 16) / 2);
                    g.text(font, clip(getMessage().getString(), width - 61), getX() + 44, getY() + height / 2 - 10, foreground, false);
                    g.text(font, clip(detail, width - 55), getX() + 44, getY() + height / 2 + 4, MUTED, false);
                    if (active) g.text(font, "›", getRight() - 11, getY() + height / 2 - 4, ACCENT, false);
                }
            } else if (!detail.isBlank() && height >= 29) {
                String label = clip(getMessage().getString(), width - 6), amount = clip(detail, width - 6);
                g.text(font, label, getX() + (width - font.width(label)) / 2, getY() + 5, foreground, false);
                g.text(font, amount, getX() + (width - font.width(amount)) / 2, getY() + 19, !active ? MUTED : selected ? GREEN : ACCENT, false);
            } else {
                String label = clip(getMessage().getString(), width - 6);
                g.text(font, label, getX() + (width - font.width(label)) / 2, getY() + (height - 8) / 2, foreground, false);
            }
            if (body) g.disableScissor();
        }
    }
}
