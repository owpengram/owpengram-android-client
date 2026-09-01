package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.owpengram.OwpengramServer;
import org.telegram.owpengram.OwpengramServers;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ServerInfoFragment extends BaseFragment {

    private final OwpengramServer server;
    private final Runnable onDeleted;

    private TextView statusTv;
    private TextView latencyTv;
    private View statusDot;
    private boolean pinging;

    private final ExecutorService pingPool = Executors.newSingleThreadExecutor();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    public ServerInfoFragment(OwpengramServer server, Runnable onDeleted) {
        this.server    = server;
        this.onDeleted = onDeleted;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle("Server Info");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        scroll.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Hero header
        content.addView(buildHero(context));

        // Status card
        content.addView(buildSectionHeader(context, "Connection status"));
        content.addView(buildStatusCard(context));

        // Details card
        if (!server.description.isEmpty()) {
            content.addView(buildSectionHeader(context, "About"));
            content.addView(buildDescCard(context));
        }

        // Accounts card
        content.addView(buildSectionHeader(context, "Accounts"));
        content.addView(buildAccountsCard(context));

        // Action buttons for custom servers
        if (!server.isOfficial) {
            content.addView(buildSectionHeader(context, ""));
            content.addView(buildActionButtons(context));
        }

        fragmentView = new FrameLayout(context);
        ((FrameLayout) fragmentView).addView(scroll, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        pingServer();
        return fragmentView;
    }

    @Override
    public void onFragmentDestroy() {
        pingPool.shutdownNow();
        super.onFragmentDestroy();
    }

    // --- Ping ---

    private void pingServer() {
        pinging = true;
        updateStatus(-2); // checking
        pingPool.submit(() -> {
            long start = System.currentTimeMillis();
            int ms;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(server.host, server.port), 3000);
                ms = (int) (System.currentTimeMillis() - start);
            } catch (Exception e) {
                ms = -1;
            }
            final int result = ms;
            uiHandler.post(() -> {
                pinging = false;
                updateStatus(result);
            });
        });
    }

    private void updateStatus(int ms) {
        if (statusTv == null || latencyTv == null) return;
        if (ms == -2) {
            statusTv.setText("Checking...");
            statusTv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            latencyTv.setText("");
            if (statusDot != null) statusDot.setBackground(
                    Theme.createCircleDrawable(dp(10), 0xFFAAAAAA));
        } else if (ms < 0) {
            statusTv.setText("Offline");
            statusTv.setTextColor(0xFFE53935);
            latencyTv.setText("");
            if (statusDot != null) statusDot.setBackground(
                    Theme.createCircleDrawable(dp(10), 0xFFE53935));
        } else {
            int color = ms < 100 ? 0xFF4CAF82 : ms < 300 ? 0xFFE8A838 : 0xFFE53935;
            statusTv.setText("Online");
            statusTv.setTextColor(color);
            latencyTv.setText(ms + " ms");
            latencyTv.setTextColor(color);
            if (statusDot != null) statusDot.setBackground(
                    Theme.createCircleDrawable(dp(10), color));
        }
    }

    // --- View builders ---

    private static final int[] PLACEHOLDER_COLORS = {
            0xFFE53935, 0xFFE64A19, 0xFF43A047,
            0xFF00838D, 0xFF1976D2, 0xFF7B1FA2, 0xFFF57F17
    };

    private View buildHero(Context context) {
        LinearLayout hero = new LinearLayout(context);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        hero.setPadding(dp(16), dp(20), dp(16), dp(16));

        // Avatar circle
        hero.addView(new AvatarView(context, server, dp(72)));

        // Name
        TextView nameTv = new TextView(context);
        nameTv.setText(server.name);
        nameTv.setTextSize(20);
        nameTv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        nameTv.setGravity(Gravity.CENTER);
        nameTv.setPadding(0, dp(10), 0, dp(2));
        hero.addView(nameTv);

        // Endpoint
        TextView epTv = new TextView(context);
        epTv.setText(server.host + ":" + server.port);
        epTv.setTextSize(12);
        epTv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        epTv.setGravity(Gravity.CENTER);
        hero.addView(epTv);

        // Badges
        LinearLayout badges = new LinearLayout(context);
        badges.setOrientation(LinearLayout.HORIZONTAL);
        badges.setGravity(Gravity.CENTER);
        badges.setPadding(0, dp(6), 0, 0);
        if (server.isOfficial)  badges.addView(buildBadge(context, "Official"));
        if (server.isTelegram)  badges.addView(buildBadge(context, "Telegram"));
        badges.addView(buildBadge(context, server.multiDc ? "Multi-DC" : "Single DC"));
        hero.addView(badges);

        return hero;
    }

    private View buildBadge(Context context, String text) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextSize(11);
        tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        tv.setBackgroundColor(0x1A1976D2);
        tv.setPadding(dp(8), dp(3), dp(8), dp(3));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(3), 0, dp(3), 0);
        tv.setLayoutParams(lp);
        return tv;
    }

    private View buildStatusCard(Context context) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        card.setPadding(dp(16), dp(12), dp(16), dp(12));

        statusDot = new View(context);
        statusDot.setBackground(Theme.createCircleDrawable(dp(10), 0xFFAAAAAA));
        card.addView(statusDot, LayoutHelper.createLinear(10, 10, Gravity.CENTER_VERTICAL, 0, 0, 10, 0));

        LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(LinearLayout.VERTICAL);

        statusTv = new TextView(context);
        statusTv.setText("Checking...");
        statusTv.setTextSize(15);
        statusTv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        textCol.addView(statusTv);

        latencyTv = new TextView(context);
        latencyTv.setTextSize(12);
        textCol.addView(latencyTv);

        card.addView(textCol, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        // Retry button
        TextView retryBtn = new TextView(context);
        retryBtn.setText("Check again");
        retryBtn.setTextSize(13);
        retryBtn.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        retryBtn.setOnClickListener(v -> { if (!pinging) pingServer(); });
        card.addView(retryBtn);

        return card;
    }

    private View buildDescCard(Context context) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        card.setPadding(dp(16), dp(12), dp(16), dp(12));

        TextView tv = new TextView(context);
        tv.setText(server.description);
        tv.setTextSize(14);
        tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        card.addView(tv);
        return card;
    }

    private View buildAccountsCard(Context context) {
        int count = 0;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                OwpengramServer s = OwpengramServers.getServerForAccount(a);
                if (s != null && server.id.equals(s.id)) count++;
            }
        }
        LinearLayout card = new LinearLayout(context);
        card.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        card.setPadding(dp(16), dp(12), dp(16), dp(12));

        TextView tv = new TextView(context);
        tv.setTextSize(14);
        tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        tv.setText(count == 0
                ? "No connected accounts"
                : count + " " + (count == 1 ? "account connected" : "accounts connected"));
        card.addView(tv);
        return card;
    }

    private View buildActionButtons(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(16), dp(4), dp(16), dp(4));
        row.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        TextView editBtn = new TextView(context);
        editBtn.setText("Edit");
        editBtn.setTextSize(14);
        editBtn.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        editBtn.setGravity(Gravity.CENTER);
        editBtn.setBackground(Theme.createSimpleSelectorRoundRectDrawable(
                dp(8),
                Theme.getColor(Theme.key_windowBackgroundWhite),
                Theme.getColor(Theme.key_listSelector)));
        editBtn.setPadding(dp(12), dp(12), dp(12), dp(12));
        editBtn.setOnClickListener(v ->
                presentFragment(new AddServerFragment(server, saved -> finishFragment())));

        TextView deleteBtn = new TextView(context);
        deleteBtn.setText("Delete");
        deleteBtn.setTextSize(14);
        deleteBtn.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
        deleteBtn.setGravity(Gravity.CENTER);
        deleteBtn.setBackground(Theme.createSimpleSelectorRoundRectDrawable(
                dp(8),
                Theme.getColor(Theme.key_windowBackgroundWhite),
                Theme.getColor(Theme.key_listSelector)));
        deleteBtn.setPadding(dp(12), dp(12), dp(12), dp(12));
        deleteBtn.setOnClickListener(v -> confirmDelete());

        row.addView(editBtn, LayoutHelper.createLinear(0, dp(44), 1f, 0, 0, 8, 0));
        row.addView(deleteBtn, LayoutHelper.createLinear(0, dp(44), 1f));
        return row;
    }

    private TextView buildSectionHeader(Context context, String title) {
        TextView tv = new TextView(context);
        tv.setText(title);
        tv.setTextSize(12);
        tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
        tv.setPadding(dp(16), dp(12), dp(16), dp(4));
        if (!title.isEmpty()) {
            tv.setAllCaps(true);
            tv.setLetterSpacing(0.06f);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tv.setLayoutParams(lp);
        return tv;
    }

    private void confirmDelete() {
        // Nothing else keeps an account pointed at a server that no longer
        // exists in the list from silently breaking, so rather than leave
        // that account around in a broken state, warn up front and actually
        // log it out as part of this same confirmation, instead of letting a
        // stale account for a server nobody's going to use again linger in
        // the switcher.
        List<Integer> affected = OwpengramServers.accountsUsingServer(server.id);
        String message = affected.isEmpty()
                ? "Server \"" + server.name + "\" and all related settings will be deleted."
                : "Server \"" + server.name + "\" and all related settings will be deleted. This will log out "
                        + affected.size() + (affected.size() == 1 ? " account" : " accounts")
                        + " signed in to it.";
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Delete server?");
        builder.setMessage(message);
        builder.setPositiveButton("Delete", (dialog, which) -> {
            for (int account : affected) {
                MessagesController.getInstance(account).performLogout(1);
            }
            OwpengramServers.removeCustomServer(server.id);
            if (onDeleted != null) onDeleted.run();
            finishFragment();
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    // --- AvatarView ---

    static class AvatarView extends View {
        private final Paint bgPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint txtPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint logoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Matrix logoMatrix = new android.graphics.Matrix();
        private android.graphics.Bitmap logoBitmap;
        private final String initial;
        private final int size;

        AvatarView(Context context, OwpengramServer server, int size) {
            super(context);
            this.size = size;
            int hash = Math.abs(server.name.hashCode());
            bgPaint.setColor(PLACEHOLDER_COLORS[hash % PLACEHOLDER_COLORS.length]);
            txtPaint.setColor(Color.WHITE);
            txtPaint.setTextSize(size * 0.45f);
            txtPaint.setFakeBoldText(true);
            txtPaint.setTextAlign(Paint.Align.CENTER);
            initial = server.name.isEmpty() ? "?" : String.valueOf(server.name.charAt(0)).toUpperCase();

            logoBitmap = serverLogo(context, server);
            if (logoBitmap != null) {
                logoPaint.setShader(new android.graphics.BitmapShader(logoBitmap,
                        android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP));
            }

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            setLayoutParams(lp);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float r = size / 2f;
            if (logoBitmap != null && logoPaint.getShader() != null) {
                float d = size;
                float scale = d / Math.min(logoBitmap.getWidth(), logoBitmap.getHeight());
                logoMatrix.setScale(scale, scale);
                logoMatrix.postTranslate(
                        -(logoBitmap.getWidth() * scale - d) / 2f,
                        -(logoBitmap.getHeight() * scale - d) / 2f);
                ((android.graphics.BitmapShader) logoPaint.getShader()).setLocalMatrix(logoMatrix);
                canvas.drawCircle(r, r, r, logoPaint);
            } else {
                canvas.drawCircle(r, r, r, bgPaint);
                canvas.drawText(initial, r, r - (txtPaint.descent() + txtPaint.ascent()) / 2f, txtPaint);
            }
        }

        // Built-in server logos decode from a drawable resource; custom
        // servers decode from OwpengramServer.logoPath (see the identical
        // comment on ServerSelectFragment's serverLogo -- kept in sync).
        private static final java.util.HashMap<String, android.graphics.Bitmap> LOGO_CACHE = new java.util.HashMap<>();
        private static android.graphics.Bitmap serverLogo(Context ctx, OwpengramServer server) {
            if (ctx == null || server == null) return null;
            int res = 0;
            if (TextUtils.isEmpty(server.logoPath)) {
                if (org.telegram.owpengram.OwpengramServers.ID_OWPENGRAM.equals(server.id)) res = org.telegram.messenger.R.drawable.server_owpengram;
                else if (org.telegram.owpengram.OwpengramServers.ID_TELEGRAM.equals(server.id)) res = org.telegram.messenger.R.drawable.server_telegram;
                if (res == 0) return null;
            }
            String cacheKey = server.id + "|" + (res != 0 ? String.valueOf(res) : server.logoPath);
            android.graphics.Bitmap b = LOGO_CACHE.get(cacheKey);
            if (b == null) {
                try {
                    b = res != 0
                            ? android.graphics.BitmapFactory.decodeResource(ctx.getResources(), res)
                            : android.graphics.BitmapFactory.decodeFile(server.logoPath);
                } catch (Throwable ignore) {}
                if (b != null) LOGO_CACHE.put(cacheKey, b);
            }
            return b;
        }
    }
}
