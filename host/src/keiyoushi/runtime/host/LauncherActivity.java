package keiyoushi.runtime.host;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.TypedArray;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
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
 * The screen the launcher opens: the installed version, the modules with their API levels, a
 * button that adds the runtime's update store to a reader app, and the licenses. Plain Java on
 * framework views: in the runtime's own process no app provides the Kotlin stdlib.
 */
public final class LauncherActivity extends Activity {
    /** The legacy add-store deep link: every fork of the app takes it */
    private static final String ADD_STORE_SCHEME = "tachiyomi";

    private static final String ADD_STORE_HOST = "add-repo";

    /** The repository's LICENSE, packaged by build-logic's RuntimeLicenseTask */
    private static final String RUNTIME_LICENSE_ASSET = "LICENSE";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String indexUrl = BuildConfig.STORE_BASE_URL + "/index.min.json";
        Intent addStore = new Intent(
                Intent.ACTION_VIEW,
                new Uri.Builder()
                        .scheme(ADD_STORE_SCHEME)
                        .authority(ADD_STORE_HOST)
                        .appendQueryParameter("url", indexUrl)
                        .build());
        List<String> handlers = handlerLabels(addStore);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(24);
        column.setPadding(padding, padding, padding, padding);

        column.addView(text(BuildConfig.RUNTIME_NAME, 24, true));
        column.addView(text("Version " + BuildConfig.VERSION_NAME, 16, false));

        column.addView(space());
        column.addView(text("Modules", 18, true));
        if (BuildModules.NAMES.length == 0) {
            column.addView(text("None in this build", 16, false));
        }
        for (int i = 0; i < BuildModules.NAMES.length; i++) {
            String line = BuildModules.NAMES[i] + ": API " + BuildModules.APIS[i];
            if (BuildModules.MIN_APIS[i] > 1) {
                line += " (serves " + BuildModules.MIN_APIS[i] + " to " + BuildModules.APIS[i] + ")";
            }
            column.addView(text(line, 16, false));
        }

        column.addView(space());
        column.addView(text(
                "Extensions use this runtime for shared code. Add its update store to your reader app, "
                        + "and the app updates the runtime like any other extension.",
                16,
                false));

        Button add = new Button(this);
        add.setText("Add update store");
        add.setEnabled(!handlers.isEmpty());
        add.setOnClickListener(v -> {
            try {
                startActivity(Intent.createChooser(addStore, "Add update store to"));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, "No app can add it", Toast.LENGTH_LONG).show();
            }
        });
        column.addView(add);

        column.addView(text(
                handlers.isEmpty()
                        ? "No installed app can add extension stores. Install Mihon or a fork of it first."
                        : "Can add it: " + String.join(", ", handlers),
                14,
                false));

        TextView url = text("Store: " + indexUrl, 14, false);
        url.setTextIsSelectable(true);
        column.addView(url);

        column.addView(space());
        column.addView(text("Licenses", 18, true));
        column.addView(licenseRow(BuildConfig.RUNTIME_NAME, RUNTIME_LICENSE_ASSET));
        for (int i = 0; i < BuildModules.LICENSE_NAMES.length; i++) {
            column.addView(licenseRow(BuildModules.LICENSE_NAMES[i], BuildModules.LICENSE_PATHS[i]));
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(column);
        // Edge to edge from targetSdk 35: keep the content clear of the system bars
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
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
            view.setPadding(left, top, right, bottom);
            return insets;
        });
        setContentView(scroll);
    }

    /** The apps that take the add-store link, by name */
    private List<String> handlerLabels(Intent intent) {
        PackageManager pm = getPackageManager();
        List<ResolveInfo> found = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
                : pm.queryIntentActivities(intent, 0);
        List<String> labels = new ArrayList<>();
        for (ResolveInfo info : found) {
            String label = info.activityInfo.applicationInfo.loadLabel(pm).toString();
            if (!labels.contains(label)) {
                labels.add(label);
            }
        }
        return labels;
    }

    /** A name to tap: it opens the license's full text */
    private TextView licenseRow(String name, String asset) {
        TextView row = text(name, 16, false);
        row.setMinHeight(dp(48));
        row.setGravity(Gravity.CENTER_VERTICAL);
        TypedArray attrs = obtainStyledAttributes(new int[] {android.R.attr.selectableItemBackground});
        row.setBackground(attrs.getDrawable(0));
        attrs.recycle();
        row.setOnClickListener(v -> showLicense(name, asset));
        return row;
    }

    private void showLicense(String name, String asset) {
        TextView body = new TextView(this);
        body.setText(readAsset(asset));
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        int padding = dp(24);
        body.setPadding(padding, dp(8), padding, dp(8));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .show();
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

    private TextView text(String value, int sizeSp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        view.setPadding(0, dp(4), 0, dp(4));
        return view;
    }

    private View space() {
        View view = new View(this);
        view.setMinimumHeight(dp(16));
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
