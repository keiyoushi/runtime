package keiyoushi.runtime.host;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.PathShape;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import keiyoushi.runtime.BuildConfig;

/**
 * The screen the launcher opens: the installed version, one button per installed Mihon build that
 * adds the runtime's update store to it, the modules with their API levels, and the licenses (a
 * second instance of this activity shows one license). Plain Java on framework views and themes:
 * in the runtime's own process no app provides the Kotlin stdlib, and bundled androidx or Material
 * would shadow the reader app's copies in its extension classloader.
 */
public final class LauncherActivity extends Activity {
    /** Mihon's add-store deep link (mihon://extension-store?url=<index>) */
    private static final String ADD_STORE_SCHEME = "mihon";

    private static final String ADD_STORE_HOST = "extension-store";

    /** The repository's LICENSE, packaged by build-logic's RuntimeLicenseTask */
    private static final String RUNTIME_LICENSE_ASSET = "LICENSE";

    /** Which license to show: 0 is the runtime's own, i + 1 is BuildModules.LICENSE_NAMES[i] */
    private static final String EXTRA_LICENSE = "keiyoushi.runtime.license";

    /** Content wider than this is centred, so lines stay readable on tablets and in landscape */
    private static final int MAX_CONTENT_WIDTH_DP = 640;

    private boolean dark;
    private int primary;
    private int onPrimary;
    private int surface;
    private ColorStateList textPrimary;
    private ColorStateList textSecondary;
    /** Secondary text on the cards: the theme's, unless the card's tint takes it below 4.5:1 */
    private ColorStateList cardTextSecondary;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // The theme follows the system's dark mode only from Android 10
        dark = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                        == Configuration.UI_MODE_NIGHT_YES;
        resolveColors();

        int license = getIntent().getIntExtra(EXTRA_LICENSE, -1);
        if (license >= 0 && license <= BuildModules.LICENSE_NAMES.length) {
            setContentView(screen(licenseBar(license), licenseBody(license)));
        } else {
            setContentView(screen(null, mainPage()));
        }
        drawBehindSystemBars();
    }

    private View mainPage() {
        String indexUrl = BuildConfig.STORE_URL;
        Intent addStore = new Intent(
                Intent.ACTION_VIEW,
                new Uri.Builder()
                        .scheme(ADD_STORE_SCHEME)
                        .authority(ADD_STORE_HOST)
                        .appendQueryParameter("url", indexUrl)
                        .build());

        LinearLayout column = column();

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(getApplicationInfo().loadIcon(getPackageManager()));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(64), dp(64));
        iconParams.setMargins(dp(4), dp(24), 0, dp(16));
        column.addView(icon, iconParams);

        TextView title = text(BuildConfig.RUNTIME_NAME, 32, textPrimary, null);
        heading(title);
        column.addView(title, inset(0));
        column.addView(text("Version " + BuildConfig.VERSION_NAME, 16, textSecondary, null), inset(4));

        column.addView(
                text(
                        "Extensions share code through this runtime. Add its update store to Mihon, "
                                + "and Mihon keeps it up to date.",
                        16,
                        textPrimary,
                        null),
                inset(20));

        List<ResolveInfo> handlers = handlers(addStore);
        if (handlers.isEmpty()) {
            column.addView(
                    text(
                            "Mihon isn't installed. Install Mihon, then open this screen again "
                                    + "or add the store below in Mihon by hand.",
                            16,
                            textSecondary,
                            null),
                    inset(16));
        }
        for (int i = 0; i < handlers.size(); i++) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(i == 0 ? 24 : 12);
            column.addView(addStoreButton(addStore, handlers.get(i)), params);
        }

        column.addView(text("Store address", 14, textSecondary, null), inset(24));
        TextView url = text(indexUrl, 14, textPrimary, null);
        url.setTextIsSelectable(true);
        column.addView(url, inset(2));

        column.addView(sectionTitle("Modules"));
        LinearLayout modules = card();
        if (BuildModules.NAMES.length == 0) {
            modules.addView(infoRow("None in this build", null));
        }
        for (int i = 0; i < BuildModules.NAMES.length; i++) {
            String api = "API " + BuildModules.APIS[i];
            if (BuildModules.MIN_APIS[i] > 1) {
                api += ", serves " + BuildModules.MIN_APIS[i] + " to " + BuildModules.APIS[i];
            }
            addRow(modules, infoRow(BuildModules.NAMES[i], api));
        }
        column.addView(modules);

        column.addView(sectionTitle("Licenses"));
        LinearLayout licenses = card();
        addRow(licenses, licenseRow(BuildConfig.RUNTIME_NAME, 0));
        for (int i = 0; i < BuildModules.LICENSE_NAMES.length; i++) {
            addRow(licenses, licenseRow(BuildModules.LICENSE_NAMES[i], i + 1));
        }
        LinearLayout.LayoutParams licensesParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        licensesParams.bottomMargin = dp(24);
        column.addView(licenses, licensesParams);
        return column;
    }

    /** The license page's top bar: back and the license's name */
    private View licenseBar(int license) {
        String name = license == 0 ? BuildConfig.RUNTIME_NAME : BuildModules.LICENSE_NAMES[license - 1];
        setTitle(name + " license");

        LinearLayout column = column();
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(8), 0, dp(8));

        ImageButton back = new ImageButton(this);
        back.setImageDrawable(glyph(BACK_ARROW, textPrimary.getDefaultColor()));
        back.setBackground(themeDrawable(android.R.attr.selectableItemBackgroundBorderless));
        back.setContentDescription("Back");
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView title = text(name, 22, textPrimary, null);
        heading(title);
        LinearLayout.LayoutParams titleParams =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.setMarginStart(dp(8));
        bar.addView(title, titleParams);
        column.addView(bar);
        return column;
    }

    /** One license, reflowed for the screen's width and the user's font size */
    private View licenseBody(int license) {
        String asset = license == 0 ? RUNTIME_LICENSE_ASSET : BuildModules.LICENSE_PATHS[license - 1];
        LinearLayout column = column();
        TextView body = text(LicenseText.reflow(readAsset(asset)), 16, textPrimary, null);
        body.setLineSpacing(0, 1.2f);
        body.setTextIsSelectable(true);
        LinearLayout.LayoutParams bodyParams = inset(8);
        bodyParams.bottomMargin = dp(24);
        column.addView(body, bodyParams);
        return column;
    }

    /** The apps that take Mihon's add-store link, one activity per app */
    private List<ResolveInfo> handlers(Intent intent) {
        PackageManager pm = getPackageManager();
        List<ResolveInfo> found = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
                : pm.queryIntentActivities(intent, 0);
        List<ResolveInfo> handlers = new ArrayList<>();
        List<String> packages = new ArrayList<>();
        for (ResolveInfo info : found) {
            if (!packages.contains(info.activityInfo.packageName)) {
                packages.add(info.activityInfo.packageName);
                handlers.add(info);
            }
        }
        return handlers;
    }

    /**
     * A filled button that adds the store to one app, named and drawn as the app itself: the
     * handlers' own labels are all "Add extension store", which can't tell two Mihon builds apart
     */
    private View addStoreButton(Intent addStore, ResolveInfo handler) {
        PackageManager pm = getPackageManager();
        ApplicationInfo app = handler.activityInfo.applicationInfo;
        CharSequence label = app.loadLabel(pm);
        String text = "Add update store to " + label;

        Pressable button = new Pressable(this);
        button.setOrientation(LinearLayout.HORIZONTAL);
        button.setGravity(Gravity.CENTER);
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(24), dp(12), dp(24), dp(12));
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(primary);
        fill.setCornerRadius(dp(24));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(withAlpha(onPrimary, 0x33)), fill, null));
        button.setContentDescription(text);

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(app.loadIcon(pm));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        button.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));

        TextView name = text(text, 14, ColorStateList.valueOf(onPrimary), medium());
        name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameParams.setMarginStart(dp(12));
        button.addView(name, nameParams);

        Intent target = new Intent(addStore)
                .setClassName(handler.activityInfo.packageName, handler.activityInfo.name);
        button.setOnClickListener(v -> {
            try {
                startActivity(target);
            } catch (ActivityNotFoundException | SecurityException e) {
                Toast.makeText(this, "Couldn't open " + label, Toast.LENGTH_LONG).show();
            }
        });
        return button;
    }

    /** A license to tap: it opens the full text */
    private View licenseRow(String name, int license) {
        Pressable row = new Pressable(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setPadding(dp(16), dp(12), dp(12), dp(12));
        row.setBackground(new RippleDrawable(
                ColorStateList.valueOf(withAlpha(textPrimary.getDefaultColor(), 0x1F)),
                null,
                new ColorDrawable(Color.WHITE)));
        row.setContentDescription(name + " license");

        TextView label = text(name, 16, textPrimary, null);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView chevron = new ImageView(this);
        chevron.setImageDrawable(glyph(CHEVRON, textSecondary.getDefaultColor()));
        chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams chevronParams = new LinearLayout.LayoutParams(dp(24), dp(24));
        chevronParams.setMarginStart(dp(8));
        row.addView(chevron, chevronParams);

        row.setOnClickListener(v ->
                startActivity(new Intent(this, LauncherActivity.class).putExtra(EXTRA_LICENSE, license)));
        return row;
    }

    /** A row of a card that shows a value: a name and, under it, a detail */
    private View infoRow(String name, String detail) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        // TalkBack reads the name and the detail as one item
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            row.setScreenReaderFocusable(true);
        }
        row.addView(text(name, 16, textPrimary, null));
        if (detail != null) {
            row.addView(text(detail, 14, cardTextSecondary, null));
        }
        return row;
    }

    /** A rounded group of rows, as Android's own settings lists draw them */
    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(surface);
        shape.setCornerRadius(dp(20));
        card.setBackground(shape);
        card.setClipToOutline(true);
        return card;
    }

    private void addRow(LinearLayout card, View row) {
        if (card.getChildCount() > 0) {
            View divider = new View(this);
            divider.setBackgroundColor(withAlpha(textPrimary.getDefaultColor(), 0x1F));
            LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
            params.setMarginStart(dp(16));
            params.setMarginEnd(dp(16));
            card.addView(divider, params);
        }
        card.addView(row);
    }

    private TextView sectionTitle(String value) {
        TextView view = text(value, 14, ColorStateList.valueOf(primary), medium());
        heading(view);
        view.setPadding(dp(16), dp(32), dp(16), dp(8));
        return view;
    }

    private TextView text(CharSequence value, float sizeSp, ColorStateList color, Typeface typeface) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(color);
        if (typeface != null) {
            view.setTypeface(typeface);
        }
        return view;
    }

    private static Typeface medium() {
        return Typeface.create("sans-serif-medium", Typeface.NORMAL);
    }

    private static void heading(View view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            view.setAccessibilityHeading(true);
        }
    }

    /** Text outside the cards lines up with the text inside them */
    private LinearLayout.LayoutParams inset(int topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(4), dp(topDp), dp(4), 0);
        return params;
    }

    private LinearLayout column() {
        LinearLayout column = new Column(this, dp(MAX_CONTENT_WIDTH_DP));
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(20), dp(8), dp(20), dp(8));
        return column;
    }

    /**
     * The window drawn edge to edge: the header (or nothing) is pinned under the status bar, the
     * content scrolls behind the gesture bar. Before Android 10 the navigation bar gets no contrast
     * scrim from the system, so there the content stops above it.
     */
    private View screen(View header, View content) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        if (header != null) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.gravity = Gravity.CENTER_HORIZONTAL;
            root.addView(header, params);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.addView(
                content,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER_HORIZONTAL));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        boolean scrimmedNavigation = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(left, top, right, scrimmedNavigation ? 0 : bottom);
            scroll.setPadding(0, 0, 0, scrimmedNavigation ? bottom : 0);
            return insets;
        });
        return root;
    }

    /** Edge to edge on every version (targetSdk 35+ enforces it from Android 15), with bar icons that fit the theme */
    private void drawBehindSystemBars() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.setSystemBarsAppearance(dark ? 0 : light, light);
            }
        } else {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (!dark) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            window.getDecorView().setSystemUiVisibility(flags);
        }
    }

    /**
     * Text and background from the theme; the accent from the wallpaper on Android 12+ (Material 3's
     * primary / on-primary tones), else a fixed green from the icon with the same contrast
     */
    private void resolveColors() {
        textPrimary = themeColors(android.R.attr.textColorPrimary);
        textSecondary = themeColors(android.R.attr.textColorSecondary);
        int background = themeColors(android.R.attr.colorBackground).getDefaultColor();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            primary = getColor(dark ? android.R.color.system_accent1_200 : android.R.color.system_accent1_600);
            onPrimary = getColor(dark ? android.R.color.system_accent1_800 : android.R.color.system_accent1_0);
        } else {
            primary = dark ? 0xFFB0D272 : 0xFF4A6516;
            onPrimary = dark ? 0xFF1E3700 : 0xFFFFFFFF;
        }
        surface = blend(background, primary, dark ? 0.10f : 0.07f);
        cardTextSecondary = contrast(textSecondary.getDefaultColor(), surface) >= 4.5 ? textSecondary : textPrimary;
    }

    private ColorStateList themeColors(int attr) {
        TypedArray values = obtainStyledAttributes(new int[] {attr});
        ColorStateList colors = values.getColorStateList(0);
        values.recycle();
        return colors != null ? colors : ColorStateList.valueOf(dark ? Color.WHITE : Color.BLACK);
    }

    private Drawable themeDrawable(int attr) {
        TypedArray values = obtainStyledAttributes(new int[] {attr});
        Drawable drawable = values.getDrawable(0);
        values.recycle();
        return drawable;
    }

    private static int blend(int base, int tint, float amount) {
        return Color.rgb(
                Math.round(Color.red(base) * (1 - amount) + Color.red(tint) * amount),
                Math.round(Color.green(base) * (1 - amount) + Color.green(tint) * amount),
                Math.round(Color.blue(base) * (1 - amount) + Color.blue(tint) * amount));
    }

    /** WCAG contrast ratio of an opaque or translucent colour over an opaque one */
    private static double contrast(int foreground, int background) {
        int over = blend(background, foreground, Color.alpha(foreground) / 255f);
        double a = luminance(over);
        double b = luminance(background);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    private static double luminance(int color) {
        return 0.2126 * linear(Color.red(color)) + 0.7152 * linear(Color.green(color)) + 0.0722 * linear(Color.blue(color));
    }

    private static double linear(int channel) {
        double value = channel / 255.0;
        return value <= 0.03928 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    /** Material's arrow_back and chevron_right, on a 24 x 24 grid */
    private static final float[] BACK_ARROW = {
        20f, 11f, 7.83f, 11f, 13.42f, 5.41f, 12f, 4f, 4f, 12f, 12f, 20f, 13.41f, 18.59f, 7.83f, 13f, 20f, 13f
    };

    private static final float[] CHEVRON = {10f, 6f, 8.59f, 7.41f, 13.17f, 12f, 8.59f, 16.59f, 10f, 18f, 16f, 12f};

    private Drawable glyph(float[] points, int color) {
        Path path = new Path();
        path.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) {
            path.lineTo(points[i], points[i + 1]);
        }
        path.close();
        ShapeDrawable drawable = new ShapeDrawable(new PathShape(path, 24, 24));
        drawable.getPaint().setColor(color);
        drawable.setIntrinsicWidth(dp(24));
        drawable.setIntrinsicHeight(dp(24));
        return drawable;
    }

    private String readAsset(String path) {
        try (InputStream in = getAssets().open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int read; (read = in.read(buffer)) != -1; ) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "Missing " + path;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** A tappable group that TalkBack announces as a button */
    private static final class Pressable extends LinearLayout {
        Pressable(Context context) {
            super(context);
            setClickable(true);
            setFocusable(true);
        }

        @Override
        public CharSequence getAccessibilityClassName() {
            return Button.class.getName();
        }
    }

    /** A column no wider than maxWidth */
    private static final class Column extends LinearLayout {
        private final int maxWidth;

        Column(Context context, int maxWidth) {
            super(context);
            this.maxWidth = maxWidth;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            if (MeasureSpec.getSize(widthMeasureSpec) > maxWidth) {
                widthMeasureSpec = MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.EXACTLY);
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }
}
