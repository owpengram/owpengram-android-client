package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.UserConfig;
import org.telegram.owpengram.OwpengramServer;
import org.telegram.owpengram.OwpengramServers;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Premium.LimitReachedBottomSheet;
import org.telegram.ui.Components.RecyclerListView;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Server selection (mirrors the desktop client's Intro server-select step):
 *   - list of servers as cards (logo, name, endpoint, live online status, Join button)
 *   - tap a row to open its details, tap Join to connect
 *   - Join performs an online check, then shows a blocking "Connecting" dialog,
 *     applies the server, waits for the MTProto connection, and only then proceeds
 *     to the login screen. On timeout the user may retry or go back.
 */
public class ServerSelectFragment extends BaseFragment {

    private static final int TYPE_HEADER  = 0;
    private static final int TYPE_SERVER  = 1;
    private static final int TYPE_ADD     = 2;
    private static final int TYPE_SPACER  = 3;

    private static final int[] AVATAR_COLORS = {
        0xFFE53935, 0xFFE64A19, 0xFF43A047,
        0xFF00838D, 0xFF1976D2, 0xFF7B1FA2, 0xFFF57F17
    };

    private static final int COLOR_GOOD = 0xFF4CAF82;
    private static final int COLOR_WARN = 0xFFE8A838;
    private static final int COLOR_BAD  = 0xFFE53935;
    private static final int COLOR_IDLE = 0xFF9AA4AE;

    // Ping cache value used while a check is still running.
    private static final int PING_CHECKING = -2;

    private static final int CONNECT_TIMEOUT_MS = 20000;

    // When >= 0, this is the account slot to log into (add-account flow).
    // When -1, use currentAccount (first-launch flow).
    private final int loginAccount;

    public ServerSelectFragment() {
        this.loginAccount = -1;
    }

    public ServerSelectFragment(int loginAccount) {
        this.loginAccount = loginAccount;
    }

    private RecyclerListView listView;
    private ListAdapter adapter;

    private final List<OwpengramServer> servers = new ArrayList<>();

    private final ExecutorService pingPool = Executors.newCachedThreadPool();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final HashMap<String, Integer> pingCache = new HashMap<>();

    private Runnable connectPoll;
    private boolean destroyed;

    // Refreshes this screen's list when the custom-server list changes from
    // outside it -- e.g. an owpg://addserver link opened from a browser
    // while this fragment already happens to be on screen. Without this,
    // a just-added server only shows up after leaving and re-entering the
    // screen (which re-triggers onFragmentCreate -> reloadServers()).
    private final Runnable customServersChangedListener = this::reloadServers;

    // --- Lifecycle ---

    @Override
    public boolean onFragmentCreate() {
        reloadServers();
        pingAll();
        OwpengramServers.addCustomServersChangedListener(customServersChangedListener);
        return true;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(org.telegram.messenger.R.drawable.ic_ab_back);
        actionBar.setTitle("Choose a server");
        actionBar.setSubtitle("Pick where to sign in");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        FrameLayout frame = new FrameLayout(context);
        frame.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = frame;

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setAdapter(adapter = new ListAdapter(context));
        listView.setVerticalScrollBarEnabled(false);
        listView.setOnItemClickListener((view, position) -> onRowClick(position));
        frame.addView(listView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        return fragmentView;
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        if (connectPoll != null) uiHandler.removeCallbacks(connectPoll);
        pingPool.shutdownNow();
        OwpengramServers.removeCustomServersChangedListener(customServersChangedListener);
        super.onFragmentDestroy();
    }

    // --- Data ---

    private void reloadServers() {
        servers.clear();
        servers.addAll(OwpengramServers.listServers());
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    // --- Ping ---

    private void pingAll() {
        for (OwpengramServer s : servers) {
            pingServer(s);
        }
    }

    private void pingServer(OwpengramServer s) {
        final String id   = s.id;
        final String host = s.host;
        final int port    = s.port;
        pingCache.put(id, PING_CHECKING);
        updateRow(id);
        pingPool.submit(() -> {
            int ms = pingTcp(host, port);
            uiHandler.post(() -> {
                if (destroyed) return;
                pingCache.put(id, ms);
                updateRow(id);
            });
        });
    }

    private int pingTcp(String host, int port) {
        long t = System.currentTimeMillis();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 3000);
            return (int) (System.currentTimeMillis() - t);
        } catch (Exception e) {
            return -1;
        }
    }

    private void updateRow(String serverId) {
        if (adapter == null) return;
        List<Object> rows = buildRows();
        for (int i = 0; i < rows.size(); i++) {
            Object o = rows.get(i);
            if (o instanceof OwpengramServer && serverId.equals(((OwpengramServer) o).id)) {
                adapter.notifyItemChanged(i);
                return;
            }
        }
    }

    // --- Row model ---

    private List<Object> buildRows() {
        List<Object> rows = new ArrayList<>();
        List<OwpengramServer> official = new ArrayList<>();
        List<OwpengramServer> custom   = new ArrayList<>();
        for (OwpengramServer s : servers) {
            if (s.isOfficial) official.add(s); else custom.add(s);
        }

        rows.add("official");
        rows.addAll(official);

        if (!custom.isEmpty()) {
            rows.add("custom");
            rows.addAll(custom);
        }

        rows.add("add");
        rows.add("spacer");
        return rows;
    }

    private void onRowClick(int position) {
        List<Object> rows = buildRows();
        if (position < 0 || position >= rows.size()) return;
        Object o = rows.get(position);
        if (o instanceof OwpengramServer) {
            presentFragment(new ServerInfoFragment((OwpengramServer) o, this::reloadServers));
        } else if ("add".equals(o)) {
            presentFragment(new AddServerFragment(null, saved -> {
                reloadServers();
                pingServer(saved);
            }));
        }
    }

    // --- Join / connect flow (desktop parity) ---

    private void joinServer(OwpengramServer server) {
        if (server == null) return;
        // The premium account-count limit applies only to Telegram accounts
        // (custom servers are limited solely by the total free slots). Enforce it
        // here once the chosen server's type is known, like the desktop client.
        int targetAccount = (loginAccount >= 0) ? loginAccount : currentAccount;
        if (server.isTelegram
                && !UserConfig.getInstance(targetAccount).isClientActivated()
                && !OwpengramServers.canAddTelegramAccount()
                && getParentActivity() != null) {
            showDialog(new LimitReachedBottomSheet(this, getContext(),
                    LimitReachedBottomSheet.TYPE_ACCOUNTS, currentAccount, null));
            return;
        }
        // Online check first, exactly like desktop CheckServerOnline before joining.
        pingPool.submit(() -> {
            int ms = pingTcp(server.host, server.port);
            uiHandler.post(() -> {
                if (destroyed) return;
                pingCache.put(server.id, ms);
                updateRow(server.id);
                if (ms >= 0) {
                    proceedJoin(server);
                } else {
                    AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
                    b.setTitle("Server unreachable");
                    b.setMessage("Couldn't reach " + server.name + " (" + server.host + ":" + server.port + "). Try to connect anyway?");
                    b.setPositiveButton("Connect", (d, w) -> proceedJoin(server));
                    b.setNegativeButton("Cancel", null);
                    showDialog(b.create());
                }
            });
        });
    }

    private void proceedJoin(OwpengramServer server) {
        if (getParentActivity() == null) return;
        final int targetAccount = (loginAccount >= 0) ? loginAccount : currentAccount;

        OwpengramServers.setServerForAccount(server.id, targetAccount);
        OwpengramServers.applyServerToAccount(server, targetAccount); // resetKeys=true
        ConnectionsManager.getInstance(targetAccount).resumeNetworkMaybe();

        AlertDialog progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(false);
        showDialog(progress);

        waitForConnection(targetAccount, server, progress);
    }

    private void waitForConnection(int account, OwpengramServer server, AlertDialog progress) {
        final long start = SystemClock.elapsedRealtime();
        connectPoll = new Runnable() {
            @Override
            public void run() {
                if (destroyed) return;
                int state = ConnectionsManager.native_getConnectionState(account);
                if (state == ConnectionsManager.ConnectionStateConnected) {
                    dismissDialog(progress);
                    checkEmailSignupAndGoToLogin(account, server);
                } else if (SystemClock.elapsedRealtime() - start > CONNECT_TIMEOUT_MS) {
                    dismissDialog(progress);
                    showConnectFailed(server);
                } else {
                    uiHandler.postDelayed(this, 200);
                }
            }
        };
        uiHandler.postDelayed(connectPoll, 200);
    }

    // A raw, one-off help.getAppConfig — MessagesController.loadAppConfig()'s
    // cache fetcher doesn't set the unauthenticated-request flags this needs
    // pre-login. This is the only place in the login flow that knows both the
    // account and the server before LoginActivity's view is built, so it's
    // the natural place to resolve "should the phone screen become an email
    // screen" instead of guessing inside LoginActivity itself.
    private void checkEmailSignupAndGoToLogin(int account, OwpengramServer server) {
        TLRPC.TL_help_getAppConfig req = new TLRPC.TL_help_getAppConfig();
        req.hash = 0;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            boolean emailSignupEnabled = false;
            if (response instanceof TLRPC.TL_help_appConfig) {
                TLRPC.JSONValue config = ((TLRPC.TL_help_appConfig) response).config;
                if (config instanceof TLRPC.TL_jsonObject) {
                    for (TLRPC.TL_jsonObjectValue entry : ((TLRPC.TL_jsonObject) config).value) {
                        if ("email_signup_enabled".equals(entry.key) && entry.value instanceof TLRPC.TL_jsonBool) {
                            emailSignupEnabled = ((TLRPC.TL_jsonBool) entry.value).value;
                            break;
                        }
                    }
                }
            }
            if (destroyed) return;
            goToLogin(server, emailSignupEnabled);
        }), ConnectionsManager.RequestFlagWithoutLogin | ConnectionsManager.RequestFlagEnableUnauthorized | ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    private void dismissDialog(AlertDialog dialog) {
        try {
            if (dialog != null && dialog.isShowing()) dialog.dismiss();
        } catch (Exception ignore) {
        }
    }

    private void showConnectFailed(OwpengramServer server) {
        if (getParentActivity() == null) return;
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle("Connection failed");
        b.setMessage("Couldn't establish a connection to " + server.name + ". You can continue to the login screen and keep trying, or go back.");
        b.setPositiveButton("Continue", (d, w) -> goToLogin(server, false));
        b.setNegativeButton("Back", null);
        showDialog(b.create());
    }

    private void goToLogin(OwpengramServer server, boolean emailSignupEnabled) {
        if (destroyed) return;
        // Parameterless LoginActivity for first login (target == selected account):
        // LoginActivity(n) sets newAccount=true and later calls switchToAccount(n),
        // which early-returns when n == selectedAccount, leaving an empty stack.
        LoginActivity login = (loginAccount >= 0 && loginAccount != UserConfig.selectedAccount)
                ? new LoginActivity(loginAccount)
                : new LoginActivity();
        login.setEmailSignupEnabled(emailSignupEnabled);
        presentFragment(login, true);
    }

    // --- Adapter ---

    private class ListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final Context context;

        ListAdapter(Context context) { this.context = context; }

        @Override public int getItemCount() { return buildRows().size(); }

        @Override
        public int getItemViewType(int position) {
            Object o = buildRows().get(position);
            if (o instanceof OwpengramServer) return TYPE_SERVER;
            if ("add".equals(o)) return TYPE_ADD;
            if ("spacer".equals(o)) return TYPE_SPACER;
            return TYPE_HEADER;
        }

        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            switch (type) {
                case TYPE_SERVER: return new ServerHolder(new ServerCell(context));
                case TYPE_ADD:    return new SimpleHolder(buildAddCell(context));
                case TYPE_HEADER: return new HeaderHolder(buildHeader(context));
                default:          return new SimpleHolder(buildSpacer(context));
            }
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            int type = getItemViewType(position);
            Object o = buildRows().get(position);
            if (type == TYPE_SERVER) {
                OwpengramServer s = (OwpengramServer) o;
                ((ServerHolder) holder).cell.bind(s, pingCache.get(s.id));
            } else if (type == TYPE_HEADER) {
                String key = (String) o;
                ((HeaderHolder) holder).tv.setText("official".equals(key) ? "Official servers" : "Custom servers");
            }
        }
    }

    // --- Static cell builders ---

    private View buildHeader(Context context) {
        TextView tv = new TextView(context);
        tv.setTextSize(12);
        tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
        tv.setAllCaps(true);
        tv.setLetterSpacing(0.04f);
        tv.setTypeface(AndroidUtilities.bold());
        tv.setPadding(dp(20), dp(16), dp(20), dp(8));
        tv.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return tv;
    }

    private View buildAddCell(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(6), dp(20), dp(6));

        TextView tv = new TextView(context);
        tv.setText("+  Add a server");
        tv.setTextSize(15);
        tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        tv.setTypeface(AndroidUtilities.bold());
        tv.setGravity(Gravity.CENTER);
        tv.setMinimumHeight(dp(52));
        tv.setPadding(dp(16), 0, dp(16), 0);
        tv.setBackground(Theme.createSimpleSelectorRoundRectDrawable(
                dp(14),
                Theme.getColor(Theme.key_windowBackgroundWhite),
                Theme.getColor(Theme.key_listSelector)));
        tv.setOnClickListener(v -> onRowClick(addRowPosition()));
        row.addView(tv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52, Gravity.CENTER_VERTICAL));

        row.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private int addRowPosition() {
        List<Object> rows = buildRows();
        for (int i = 0; i < rows.size(); i++) {
            if ("add".equals(rows.get(i))) return i;
        }
        return -1;
    }

    private View buildSpacer(Context context) {
        View v = new View(context);
        v.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)));
        return v;
    }

    // --- ViewHolders ---

    static class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView tv;
        HeaderHolder(View v) { super(v); tv = (TextView) v; }
    }
    static class SimpleHolder extends RecyclerView.ViewHolder {
        SimpleHolder(View v) { super(v); }
    }
    class ServerHolder extends RecyclerView.ViewHolder {
        final ServerCell cell;
        ServerHolder(ServerCell c) { super(c); cell = c; }
    }

    // Built-in server logos (pulled from the desktop client) decode from a
    // drawable resource; custom servers decode from OwpengramServer.logoPath
    // (set by AddServerFragment, either auto-fetched from the server or
    // picked locally). Decoded once and cached; a server with neither falls
    // back to the generated colored initial avatar.
    private static final java.util.HashMap<String, android.graphics.Bitmap> SERVER_LOGO_CACHE = new java.util.HashMap<>();
    private static android.graphics.Bitmap serverLogo(Context ctx, OwpengramServer server) {
        if (ctx == null || server == null) return null;
        int res = 0;
        if (TextUtils.isEmpty(server.logoPath)) {
            if (OwpengramServers.ID_OWPENGRAM.equals(server.id)) res = org.telegram.messenger.R.drawable.server_owpengram;
            else if (OwpengramServers.ID_TELEGRAM.equals(server.id)) res = org.telegram.messenger.R.drawable.server_telegram;
            if (res == 0) return null;
        }
        // Keyed on logoPath too (not just id) so re-uploading a custom
        // server's icon busts the cache instead of showing the stale one.
        String cacheKey = server.id + "|" + (res != 0 ? String.valueOf(res) : server.logoPath);
        android.graphics.Bitmap b = SERVER_LOGO_CACHE.get(cacheKey);
        if (b == null) {
            try {
                b = res != 0
                        ? android.graphics.BitmapFactory.decodeResource(ctx.getResources(), res)
                        : android.graphics.BitmapFactory.decodeFile(server.logoPath);
            } catch (Throwable ignore) {}
            if (b != null) SERVER_LOGO_CACHE.put(cacheKey, b);
        }
        return b;
    }

    // --- ServerCell ---

    private class ServerCell extends FrameLayout {

        private final Paint avatarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint letterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint logoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Matrix logoMatrix = new android.graphics.Matrix();
        private android.graphics.Bitmap logoBitmap;

        private final TextView nameTv;
        private final TextView endpointTv;
        private final TextView statusTv;
        private final View statusDot;
        private final TextView joinBtn;

        private String initial = "?";
        private ValueAnimator pulse;

        ServerCell(Context context) {
            super(context);
            setWillNotDraw(false);
            setPadding(dp(20), dp(5), dp(20), dp(5));
            setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // Inner card so rows read as distinct cards on the gray background.
            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setMinimumHeight(dp(72));
            // Right padding reserves room for the Join button, which is a DIRECT
            // child of this cell (see note at addView below).
            card.setPadding(dp(14), dp(10), dp(86), dp(10));
            card.setBackground(Theme.createRoundRectDrawable(dp(14),
                    Theme.getColor(Theme.key_windowBackgroundWhite)));

            letterPaint.setColor(Color.WHITE);
            letterPaint.setFakeBoldText(true);
            letterPaint.setTextAlign(Paint.Align.CENTER);
            letterPaint.setTextSize(dp(19));

            // Avatar spacer (drawn in onDraw): 48dp circle + 14dp gap.
            View avatarSpace = new View(context);
            card.addView(avatarSpace, LayoutHelper.createLinear(48 + 14, 48, Gravity.CENTER_VERTICAL));

            LinearLayout textCol = new LinearLayout(context);
            textCol.setOrientation(LinearLayout.VERTICAL);

            nameTv = new TextView(context);
            nameTv.setTextSize(15.5f);
            nameTv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            nameTv.setTypeface(AndroidUtilities.bold());
            nameTv.setSingleLine();
            nameTv.setEllipsize(TextUtils.TruncateAt.END);
            textCol.addView(nameTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            endpointTv = new TextView(context);
            endpointTv.setTextSize(13);
            endpointTv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            endpointTv.setSingleLine();
            endpointTv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            textCol.addView(endpointTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

            // Status line: a coloured dot + status text.
            LinearLayout statusRow = new LinearLayout(context);
            statusRow.setOrientation(LinearLayout.HORIZONTAL);
            statusRow.setGravity(Gravity.CENTER_VERTICAL);

            statusDot = new View(context);
            statusRow.addView(statusDot, LayoutHelper.createLinear(8, 8, Gravity.CENTER_VERTICAL, 0, 0, 7, 0));

            statusTv = new TextView(context);
            statusTv.setTextSize(12.5f);
            statusTv.setSingleLine();
            statusRow.addView(statusTv, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

            textCol.addView(statusRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 5, 0, 0));

            card.addView(textCol, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

            addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            // IMPORTANT: the Join button must be a DIRECT child of the cell.
            // RecyclerListView only checks the row's immediate children for
            // clickability (one level deep); a button nested inside the card would
            // be missed and the tap would fall through to the row's item-click
            // (opening the info screen) instead of joining.
            joinBtn = new TextView(context);
            joinBtn.setText("Join");
            joinBtn.setTextSize(14);
            joinBtn.setTypeface(AndroidUtilities.bold());
            joinBtn.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
            joinBtn.setGravity(Gravity.CENTER);
            joinBtn.setPadding(dp(18), 0, dp(18), 0);
            joinBtn.setBackground(Theme.createSimpleSelectorRoundRectDrawable(
                    dp(17),
                    Theme.getColor(Theme.key_featuredStickers_addButton),
                    Theme.getColor(Theme.key_featuredStickers_addButtonPressed)));
            // marginEnd = cell padding (20) + inset inside the card (12).
            addView(joinBtn, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 34,
                    Gravity.CENTER_VERTICAL | Gravity.END, 0, 0, 20 + 12, 0));
        }

        void bind(OwpengramServer server, Integer pingMs) {
            initial = server.name.isEmpty() ? "?" : String.valueOf(server.name.charAt(0)).toUpperCase();
            int hash = Math.abs(server.name.hashCode());
            avatarPaint.setColor(AVATAR_COLORS[hash % AVATAR_COLORS.length]);

            logoBitmap = serverLogo(getContext(), server);
            logoPaint.setShader(logoBitmap == null ? null : new android.graphics.BitmapShader(
                    logoBitmap,
                    android.graphics.Shader.TileMode.CLAMP,
                    android.graphics.Shader.TileMode.CLAMP));

            nameTv.setText(server.name);
            endpointTv.setText(server.host + ":" + server.port);

            stopPulse();
            int dotColor;
            if (pingMs == null || pingMs == PING_CHECKING) {
                dotColor = COLOR_IDLE;
                statusTv.setText("Checking…");
                statusTv.setTextColor(COLOR_IDLE);
                statusDot.setBackground(Theme.createCircleDrawable(dp(8), dotColor));
                startPulse();
            } else if (pingMs < 0) {
                dotColor = COLOR_BAD;
                statusTv.setText("Offline");
                statusTv.setTextColor(COLOR_BAD);
                statusDot.setAlpha(1f);
                statusDot.setBackground(Theme.createCircleDrawable(dp(8), dotColor));
            } else {
                dotColor = pingMs < 100 ? COLOR_GOOD : pingMs < 300 ? COLOR_WARN : COLOR_BAD;
                statusTv.setText("Online · " + pingMs + " ms");
                statusTv.setTextColor(dotColor);
                statusDot.setAlpha(1f);
                statusDot.setBackground(Theme.createCircleDrawable(dp(8), dotColor));
            }

            joinBtn.setOnClickListener(v -> joinServer(server));
            invalidate();
        }

        private void startPulse() {
            pulse = ValueAnimator.ofFloat(0.35f, 1f);
            pulse.setDuration(700);
            pulse.setRepeatMode(ValueAnimator.REVERSE);
            pulse.setRepeatCount(ValueAnimator.INFINITE);
            pulse.addUpdateListener(a -> statusDot.setAlpha((float) a.getAnimatedValue()));
            pulse.start();
        }

        private void stopPulse() {
            if (pulse != null) {
                pulse.cancel();
                pulse = null;
            }
            statusDot.setAlpha(1f);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            stopPulse();
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            // Drawn after children so it sits on top of the card's white background.
            // Cell padding-left (20) + card padding-left (14) = 34 to circle edge.
            float r = dp(24);
            float cx = dp(20 + 14) + r;
            float cy = getHeight() / 2f;
            if (logoBitmap != null && logoPaint.getShader() != null) {
                float d = 2 * r;
                float scale = d / Math.min(logoBitmap.getWidth(), logoBitmap.getHeight());
                logoMatrix.setScale(scale, scale);
                logoMatrix.postTranslate(
                        cx - r - (logoBitmap.getWidth() * scale - d) / 2f,
                        cy - r - (logoBitmap.getHeight() * scale - d) / 2f);
                ((android.graphics.BitmapShader) logoPaint.getShader()).setLocalMatrix(logoMatrix);
                canvas.drawCircle(cx, cy, r, logoPaint);
            } else {
                canvas.drawCircle(cx, cy, r, avatarPaint);
                canvas.drawText(initial, cx, cy - (letterPaint.descent() + letterPaint.ascent()) / 2f, letterPaint);
            }
        }
    }
}
