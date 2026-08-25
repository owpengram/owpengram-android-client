package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.net.Uri;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.owpengram.OwpengramServer;
import org.telegram.owpengram.OwpengramServers;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import java.io.InputStream;

public class AddServerFragment extends BaseFragment {

    public interface OnSavedListener {
        void onSaved(OwpengramServer server);
    }

    private final OwpengramServer editingServer;
    private final OnSavedListener onSavedListener;

    private EditTextBoldCursor nameField;
    private EditTextBoldCursor addressField;
    private ProgressBar addressSpinner;
    private EditTextBoldCursor descField;
    private EditTextBoldCursor rsaField;
    private EditTextBoldCursor mainDcField;
    private View mainDcRow;
    private View multiDcToggle;
    private boolean multiDcEnabled = false;

    private TextView advancedHeader;
    private View advancedSection;
    private boolean advancedExpanded = false;

    private ImageView iconPreview;

    // Suppresses re-fetching for an address we already have a result for.
    private String lastFetchedAddress = "";
    // Set once a server icon has been chosen (manually, via pickIcon()) or
    // auto-fetched and persisted locally (see OwpengramServers.saveFetchedIcon);
    // applied to the server on save.
    private String fetchedLogoPath;
    private final Runnable fetchDebounceRunnable = this::fetchPublicKeyForAddress;
    private static final int FETCH_DEBOUNCE_MS = 500;
    private static final int REQUEST_PICK_ICON = 42;
    // Matches SaveCustomServerLogo's target size on the desktop client, so a
    // server's icon looks the same regardless of which client uploaded it.
    private static final int ICON_TARGET_SIZE = 256;

    private static final int DEFAULT_SINGLE_MAIN_DC = 2;

    public AddServerFragment(OwpengramServer existing, OnSavedListener listener) {
        this.editingServer    = existing;
        this.onSavedListener  = listener;
    }

    @Override
    public View createView(Context context) {
        boolean isEdit = (editingServer != null);
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(isEdit ? "Edit Server" : "New Server");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
                else if (id == 1) save();
            }
        });
        actionBar.createMenu().addItem(1, "Save");

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(8), 0, dp(16));
        content.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        scroll.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Section: name + description
        content.addView(buildSectionHeader(context, "General"));
        LinearLayout section1 = buildCard(context);
        buildIconRow(context, section1);
        nameField = buildField(context, "Name", InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_NEXT);
        addFieldToCard(section1, nameField, true);
        descField = buildField(context, "Description (optional)", InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_NEXT);
        addFieldToCard(section1, descField, false);
        content.addView(section1);

        // Section: connection
        content.addView(buildSectionHeader(context, "Connection"));
        LinearLayout section2 = buildCard(context);
        addressField = buildField(context, "Address (host:port)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, EditorInfo.IME_ACTION_NEXT);
        addressSpinner = new ProgressBar(context, null, android.R.attr.progressBarStyleSmall);
        addressSpinner.getIndeterminateDrawable().setColorFilter(
                Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), PorterDuff.Mode.SRC_IN);
        addressSpinner.setVisibility(View.GONE);

        LinearLayout addressRow = new LinearLayout(context);
        addressRow.setOrientation(LinearLayout.HORIZONTAL);
        addressRow.setGravity(Gravity.CENTER_VERTICAL);
        addressRow.setPadding(dp(16), dp(4), dp(16), dp(4));
        addressRow.addView(addressField, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        addressRow.addView(addressSpinner, LayoutHelper.createLinear(dp(20), dp(20), Gravity.CENTER_VERTICAL, dp(8), 0, 0, 0));
        section2.addView(addressRow);

        addressField.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                addressField.removeCallbacks(fetchDebounceRunnable);
                fetchPublicKeyForAddress();
            }
        });
        addressField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                addressField.removeCallbacks(fetchDebounceRunnable);
                addressField.postDelayed(fetchDebounceRunnable, FETCH_DEBOUNCE_MS);
            }
        });
        content.addView(section2);

        // Section: advanced (collapsed by default -- only matters for servers
        // that don't serve their key/DC over /owpengram/server-info, or to
        // override what auto-fetch filled in).
        advancedHeader = buildSectionHeader(context, "Advanced");
        advancedHeader.setOnClickListener(v -> toggleAdvanced());
        content.addView(advancedHeader);
        LinearLayout section3 = buildCard(context);
        multiDcToggle = buildToggleRow(context, "Multi-DC mode",
                "Only for Telegram-compatible servers", false);
        section3.addView(multiDcToggle);

        // Main DC selector — single-server only. Hidden when Multi-DC is on
        // (Telegram-compatible servers always use DC 2 as home).
        LinearLayout mainDcContainer = new LinearLayout(context);
        mainDcContainer.setOrientation(LinearLayout.VERTICAL);
        addDividerToCard(mainDcContainer, context);
        mainDcField = buildField(context, "Main data center (1-5)", InputType.TYPE_CLASS_NUMBER, EditorInfo.IME_ACTION_NEXT);
        mainDcField.setText(String.valueOf(DEFAULT_SINGLE_MAIN_DC));
        addFieldToCard(mainDcContainer, mainDcField, true);
        section3.addView(mainDcContainer);
        mainDcRow = mainDcContainer;

        addDividerToCard(section3, context);
        rsaField = buildField(context, "RSA Public Key (PEM)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE, EditorInfo.IME_ACTION_DONE);
        rsaField.setMinLines(3);
        rsaField.setGravity(Gravity.TOP);
        addFieldToCard(section3, rsaField, true);
        section3.setVisibility(View.GONE);
        advancedSection = section3;
        content.addView(section3);
        updateMainDcVisibility();

        // Save button
        content.addView(buildSaveButton(context));

        // Pre-fill if editing
        if (isEdit) {
            nameField.setText(editingServer.name);
            descField.setText(editingServer.description);
            addressField.setText(editingServer.port > 0
                    ? editingServer.host + ":" + editingServer.port
                    : editingServer.host);
            rsaField.setText(editingServer.rsaPublicKey);
            mainDcField.setText(String.valueOf(editingServer.mainDcId > 0
                    ? editingServer.mainDcId : DEFAULT_SINGLE_MAIN_DC));
            multiDcEnabled = editingServer.multiDc;
            updateToggleState();
            updateMainDcVisibility();
            if (editingServer.logoPath != null && !editingServer.logoPath.isEmpty()) {
                fetchedLogoPath = editingServer.logoPath;
                updateIconPreview(editingServer.logoPath);
            }
            // Show what's already configured instead of hiding it behind a
            // tap -- Advanced only collapses by default for the new-server,
            // auto-fetch-does-everything case.
            toggleAdvanced();
        }

        fragmentView = new FrameLayout(context);
        ((FrameLayout) fragmentView).addView(scroll, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        return fragmentView;
    }

    // --- Address parsing + auto-fetch ---

    /** Splits "host:port" on the last colon. port is 0 when absent/invalid. */
    private static String[] parseAddress(String address) {
        int colon = address.lastIndexOf(':');
        if (colon <= 0) {
            return new String[]{address, ""};
        }
        return new String[]{address.substring(0, colon).trim(), address.substring(colon + 1).trim()};
    }

    private void toggleAdvanced() {
        advancedExpanded = !advancedExpanded;
        advancedSection.setVisibility(advancedExpanded ? View.VISIBLE : View.GONE);
        advancedHeader.setText(advancedExpanded ? "HIDE ADVANCED" : "ADVANCED");
    }

    private void fetchPublicKeyForAddress() {
        String address = addressField.getText().toString().trim();
        if (address.isEmpty() || address.equals(lastFetchedAddress)) {
            return;
        }
        String[] parts = parseAddress(address);
        String host = parts[0];
        int port;
        try {
            port = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return;
        }
        if (host.isEmpty() || port <= 0) {
            return;
        }
        lastFetchedAddress = address;
        addressSpinner.setVisibility(View.VISIBLE);
        OwpengramServers.fetchServerInfo(host, port, result -> {
            addressSpinner.setVisibility(View.GONE);
            if (result == null || !lastFetchedAddress.equals(address)) {
                return;
            }
            // Always overwrite -- the server is the source of truth once it
            // answers, even if the user had typed/pasted something already.
            if (result.rsaPublicKeyPem != null && !result.rsaPublicKeyPem.isEmpty()) {
                rsaField.setText(result.rsaPublicKeyPem);
            }
            if (result.dcId > 0) {
                mainDcField.setText(String.valueOf(result.dcId));
            }
            if (result.name != null && !result.name.isEmpty()) {
                nameField.setText(result.name);
            }
            if (result.description != null && !result.description.isEmpty()) {
                descField.setText(result.description);
            }
            if (result.hasIcon) {
                OwpengramServers.fetchServerIcon(host, port, bitmap -> {
                    if (bitmap == null || !lastFetchedAddress.equals(address)) {
                        return;
                    }
                    applyIconBitmap(bitmap);
                });
            }
        });
    }

    // --- Icon picking (manual) + shared apply path with auto-fetch ---

    private void pickIcon() {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            startActivityForResult(intent, REQUEST_PICK_ICON);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_PICK_ICON || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        try (InputStream in = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri)) {
            Bitmap bitmap = in != null ? BitmapFactory.decodeStream(in) : null;
            if (bitmap == null) {
                return;
            }
            applyIconBitmap(bitmap);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /** Center-crops to square, matching the desktop client's SaveCustomServerLogo. */
    private static Bitmap cropSquare(Bitmap src) {
        int side = Math.min(src.getWidth(), src.getHeight());
        if (side <= 0) {
            return null;
        }
        Bitmap cropped = Bitmap.createBitmap(src, (src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
        if (side != ICON_TARGET_SIZE) {
            cropped = Bitmap.createScaledBitmap(cropped, ICON_TARGET_SIZE, ICON_TARGET_SIZE, true);
        }
        return cropped;
    }

    /** Shared by both the manual picker and the auto-fetch-on-address callback. */
    private void applyIconBitmap(Bitmap bitmap) {
        Bitmap square = cropSquare(bitmap);
        if (square == null) {
            return;
        }
        String path = OwpengramServers.saveFetchedIcon(square);
        if (path == null) {
            return;
        }
        fetchedLogoPath = path;
        updateIconPreview(path);
    }

    private void updateIconPreview(String path) {
        if (iconPreview == null || path == null) {
            return;
        }
        Bitmap bitmap = BitmapFactory.decodeFile(path);
        if (bitmap != null) {
            iconPreview.setImageBitmap(bitmap);
        }
    }

    // --- Save logic ---

    private void save() {
        String name = nameField.getText().toString().trim();
        String address = addressField.getText().toString().trim();
        String[] parts = parseAddress(address);
        String host = parts[0];

        if (name.isEmpty()) {
            showFieldError(nameField, "Enter a name");
            return;
        }
        if (host.isEmpty()) {
            showFieldError(addressField, "Enter host or IP");
            return;
        }
        int port;
        try {
            port = Integer.parseInt(parts[1]);
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            showFieldError(addressField, "Invalid address, expected host:port");
            return;
        }

        OwpengramServer server;
        if (editingServer != null) {
            server = editingServer;
        } else {
            server = new OwpengramServer();
        }
        int mainDc;
        if (multiDcEnabled) {
            mainDc = 2; // Telegram-compatible servers always home on DC 2
        } else {
            try {
                mainDc = Integer.parseInt(mainDcField.getText().toString().trim());
            } catch (NumberFormatException e) {
                mainDc = DEFAULT_SINGLE_MAIN_DC;
            }
            if (mainDc < 1 || mainDc > 5) mainDc = DEFAULT_SINGLE_MAIN_DC;
        }

        server.name        = name;
        server.host        = host;
        server.port        = port;
        server.description = descField.getText().toString().trim();
        server.rsaPublicKey = rsaField.getText().toString().trim();
        server.multiDc     = multiDcEnabled;
        server.mainDcId    = mainDc;
        if (fetchedLogoPath != null) {
            server.logoPath = fetchedLogoPath;
        }

        if (editingServer != null) {
            OwpengramServers.updateCustomServer(server);
        } else {
            OwpengramServers.addCustomServer(server);
        }

        if (onSavedListener != null) onSavedListener.onSaved(server);
        finishFragment();
    }

    // --- UI helpers ---

    private TextView buildSectionHeader(Context context, String title) {
        TextView tv = new TextView(context);
        tv.setText(title);
        tv.setTextSize(12);
        tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
        tv.setPadding(dp(16), dp(12), dp(16), dp(4));
        tv.setAllCaps(true);
        tv.setLetterSpacing(0.06f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tv.setLayoutParams(lp);
        return tv;
    }

    private void buildIconRow(Context context, LinearLayout card) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setBackground(Theme.getSelectorDrawable(true));
        row.setClickable(true);
        row.setOnClickListener(v -> pickIcon());

        iconPreview = new ImageView(context);
        iconPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iconPreview.setBackground(Theme.createRoundRectDrawable(dp(28), Theme.getColor(Theme.key_windowBackgroundGray)));
        iconPreview.setClipToOutline(true);
        row.addView(iconPreview, LayoutHelper.createLinear(56, 56));

        TextView label = new TextView(context);
        label.setText("Choose icon");
        label.setTextSize(15);
        label.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        label.setPadding(dp(14), 0, 0, 0);
        row.addView(label, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        card.addView(row);
        addDividerToCard(card, context);
    }

    private LinearLayout buildCard(Context context) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        card.setLayoutParams(lp);
        return card;
    }

    private EditTextBoldCursor buildField(Context context, String hint, int inputType, int imeAction) {
        EditTextBoldCursor et = new EditTextBoldCursor(context);
        et.setHint(hint);
        et.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        et.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        et.setTextSize(16);
        et.setInputType(inputType);
        et.setImeOptions(imeAction);
        et.setSingleLine(imeAction != EditorInfo.IME_ACTION_DONE);
        et.setBackground(null);
        et.setPadding(0, dp(8), 0, dp(8));
        return et;
    }

    private void addFieldToCard(LinearLayout card, EditTextBoldCursor field, boolean isFirst) {
        if (!isFirst) addDividerToCard(card, field.getContext());
        LinearLayout wrapper = new LinearLayout(field.getContext());
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(dp(16), dp(4), dp(16), dp(4));
        wrapper.addView(field, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        card.addView(wrapper);
    }

    private void addDividerToCard(LinearLayout card, Context context) {
        View divider = new View(context);
        divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        lp.setMarginStart(dp(16));
        divider.setLayoutParams(lp);
        card.addView(divider);
    }

    private View buildToggleRow(Context context, String title, String subtitle, boolean checked) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(10), dp(16), dp(10));

        LinearLayout textPart = new LinearLayout(context);
        textPart.setOrientation(LinearLayout.VERTICAL);

        TextView titleTv = new TextView(context);
        titleTv.setText(title);
        titleTv.setTextSize(15);
        titleTv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        textPart.addView(titleTv);

        TextView subTv = new TextView(context);
        subTv.setText(subtitle);
        subTv.setTextSize(12);
        subTv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        textPart.addView(subTv);

        row.addView(textPart, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        // Simple toggle indicator (we manage state manually)
        TextView toggleTv = new TextView(context);
        toggleTv.setTag("toggle");
        toggleTv.setTextSize(13);
        updateToggleText(toggleTv, multiDcEnabled);
        row.addView(toggleTv);

        row.setOnClickListener(v -> {
            multiDcEnabled = !multiDcEnabled;
            updateToggleText(toggleTv, multiDcEnabled);
            updateMainDcVisibility();
        });
        return row;
    }

    private void updateMainDcVisibility() {
        if (mainDcRow != null) {
            mainDcRow.setVisibility(multiDcEnabled ? View.GONE : View.VISIBLE);
        }
    }

    private void updateToggleText(TextView tv, boolean on) {
        tv.setText(on ? "On" : "Off");
        tv.setTextColor(on
                ? Theme.getColor(Theme.key_switch2TrackChecked)
                : Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
    }

    private void updateToggleState() {
        if (multiDcToggle != null) {
            TextView tv = multiDcToggle.findViewWithTag("toggle");
            if (tv != null) updateToggleText(tv, multiDcEnabled);
        }
    }

    private View buildSaveButton(Context context) {
        TextView btn = new TextView(context);
        btn.setText("Save Server");
        btn.setTextSize(16);
        btn.setTextColor(Color.WHITE);
        btn.setGravity(Gravity.CENTER);
        btn.setBackground(Theme.createSimpleSelectorRoundRectDrawable(
                dp(10),
                Theme.getColor(Theme.key_featuredStickers_addButton),
                Theme.getColor(Theme.key_featuredStickers_addButtonPressed)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        lp.setMargins(dp(16), dp(12), dp(16), dp(8));
        btn.setLayoutParams(lp);
        btn.setOnClickListener(v -> save());
        return btn;
    }

    private void showFieldError(EditTextBoldCursor field, String msg) {
        field.setError(msg);
        field.requestFocus();
        AndroidUtilities.showKeyboard(field);
    }
}
