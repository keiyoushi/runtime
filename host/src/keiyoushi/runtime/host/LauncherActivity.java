package keiyoushi.runtime.host;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.TypedArray;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import keiyoushi.runtime.BuildConfig;
import keiyoushi.runtime.R;

/**
 * The screen the launcher opens: the runtime's name and version, one button per installed Mihon build that adds
 * its update store, and links to the licenses and the details. Each inner page is another instance of this
 * activity. Plain Java on framework views and themes: in the runtime's own process no app provides the Kotlin
 * stdlib, and bundled androidx or Material would shadow the reader app's copies in its extension classloader.
 */
public final class LauncherActivity extends Activity {
    /** Mihon's add-store deep link (mihon://extension-store?url=<index>) */
    private static final Uri ADD_STORE = new Uri.Builder()
            .scheme("mihon")
            .authority("extension-store")
            .appendQueryParameter("url", BuildConfig.STORE_URL)
            .build();

    /** The repository's LICENSE, packaged by build-logic's RuntimeLicenseTask */
    private static final String RUNTIME_LICENSE_ASSET = "LICENSE";

    private static final String EXTRA_PAGE = "keiyoushi.runtime.page";

    /** Which license the license page shows: 0 is the runtime's own, i + 1 is BuildModules.LICENSE_NAMES[i] */
    private static final String EXTRA_LICENSE = "keiyoushi.runtime.license";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String page = getIntent().getStringExtra(EXTRA_PAGE);
        int license = getIntent().getIntExtra(EXTRA_LICENSE, -1);
        if ("license".equals(page) && license >= 0 && license <= BuildModules.LICENSE_NAMES.length) {
            showLicense(license);
        } else if ("licenses".equals(page)) {
            showLicenses();
        } else if ("details".equals(page)) {
            showDetails();
        } else {
            showMain();
        }
        // Edge to edge from targetSdk 35: keep the content clear of the system bars, scrolling behind the gesture bar
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            View scroll = findViewById(R.id.scroll);
            findViewById(android.R.id.content).setOnApplyWindowInsetsListener((view, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, 0);
                scroll.setPadding(0, 0, 0, bars.bottom);
                return insets;
            });
        }
    }

    private void showMain() {
        setContentView(R.layout.launcher);
        findViewById(R.id.icon).setClipToOutline(true);

        // Name and version on one line, sharing a baseline; at large font sizes the version wraps under the name
        String version = BuildConfig.VERSION_NAME;
        SpannableString name = new SpannableString(BuildConfig.RUNTIME_NAME + "  " + version);
        int start = name.length() - version.length();
        TypedArray secondary = obtainStyledAttributes(new int[] {android.R.attr.textColorSecondary});
        int flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;
        name.setSpan(new ForegroundColorSpan(secondary.getColor(0, 0)), start, name.length(), flags);
        secondary.recycle();
        name.setSpan(new RelativeSizeSpan(15f / 22f), start, name.length(), flags);
        TextView title = findViewById(R.id.title);
        title.setText(name);
        title.setContentDescription(BuildConfig.RUNTIME_NAME + ", version " + version);
        heading(title);

        ViewGroup buttons = findViewById(R.id.buttons);
        List<ResolveInfo> handlers = handlers();
        if (handlers.isEmpty()) {
            getLayoutInflater().inflate(R.layout.install_mihon, buttons);
        }
        List<String> labels = new ArrayList<>();
        for (ResolveInfo handler : handlers) {
            labels.add(handler.activityInfo.applicationInfo.loadLabel(getPackageManager()).toString());
        }
        for (int i = 0; i < handlers.size(); i++) {
            ResolveInfo handler = handlers.get(i);
            String label = labels.get(i);
            String app = handler.activityInfo.packageName;
            TextView button = (TextView) add(buttons, R.layout.store_button);
            // Two builds with one name (a release and a debug Mihon) are told apart by their package
            boolean shared = labels.indexOf(label) != labels.lastIndexOf(label);
            button.setText(shared ? getString(R.string.add_to_package, label, app) : getString(R.string.add_to, label));
            button.setContentDescription(getString(R.string.add_store_description, BuildConfig.RUNTIME_NAME, label)
                    + (shared ? ", " + app : ""));
            button.setOnClickListener(v -> addStore(handler, label));
        }

        // A single license opens straight away
        Intent licenses = BuildModules.LICENSE_NAMES.length == 0
                ? page("license").putExtra(EXTRA_LICENSE, 0)
                : page("licenses");
        link(findViewById(R.id.licenses), getString(R.string.licenses), licenses);
        link(findViewById(R.id.details), getString(R.string.details), page("details"));
    }

    private void showLicenses() {
        ViewGroup body = showPage(getString(R.string.licenses));
        link(add(body, R.layout.link_row), BuildConfig.RUNTIME_NAME, page("license").putExtra(EXTRA_LICENSE, 0));
        for (int i = 0; i < BuildModules.LICENSE_NAMES.length; i++) {
            Intent target = page("license").putExtra(EXTRA_LICENSE, i + 1);
            link(add(body, R.layout.link_row), BuildModules.LICENSE_NAMES[i], target);
        }
    }

    /** One license, reflowed for the screen's width and the user's font size */
    private void showLicense(int license) {
        String name = license == 0 ? BuildConfig.RUNTIME_NAME : BuildModules.LICENSE_NAMES[license - 1];
        String asset = license == 0 ? RUNTIME_LICENSE_ASSET : BuildModules.LICENSE_PATHS[license - 1];
        ViewGroup body = showPage(name);
        setTitle(name + " license");
        ((TextView) add(body, R.layout.body_text)).setText(LicenseText.reflow(readAsset(asset)));
    }

    /** What a bug report needs: the modules and their API levels, the store address, the version code */
    private void showDetails() {
        ViewGroup body = showPage(getString(R.string.details));
        section(body, R.string.modules);
        if (BuildModules.NAMES.length == 0) {
            info(body, getString(R.string.no_modules), null);
        }
        for (int i = 0; i < BuildModules.NAMES.length; i++) {
            int api = BuildModules.APIS[i];
            int minApi = BuildModules.MIN_APIS[i];
            info(body, BuildModules.NAMES[i], minApi > 1
                    ? getString(R.string.api_range, api, minApi, api)
                    : getString(R.string.api, api));
        }

        section(body, R.string.update_store);
        ((TextView) add(body, R.layout.body_text)).setText(BuildConfig.STORE_URL);
        add(body, R.layout.copy_button).setOnClickListener(v -> {
            ClipData clip = ClipData.newPlainText(getString(R.string.update_store), BuildConfig.STORE_URL);
            getSystemService(ClipboardManager.class).setPrimaryClip(clip);
            // Android 13+ confirms copies itself
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(this, R.string.address_copied, Toast.LENGTH_SHORT).show();
            }
        });

        section(body, R.string.version);
        info(body, BuildConfig.VERSION_NAME, getString(R.string.version_code, BuildConfig.VERSION_CODE));
    }

    /** Shows the inner-page layout with its title, and returns the body the page's rows go in */
    private ViewGroup showPage(String title) {
        setContentView(R.layout.page);
        setTitle(title);
        findViewById(R.id.back).setOnClickListener(v -> finish());
        TextView heading = findViewById(R.id.title);
        heading.setText(title);
        heading(heading);
        return findViewById(R.id.body);
    }

    private Intent page(String name) {
        return new Intent(this, LauncherActivity.class).putExtra(EXTRA_PAGE, name);
    }

    private View add(ViewGroup parent, int layout) {
        View view = getLayoutInflater().inflate(layout, parent, false);
        parent.addView(view);
        return view;
    }

    private void link(View row, String name, Intent target) {
        ((TextView) row).setText(name);
        row.setOnClickListener(v -> startActivity(target));
    }

    private void section(ViewGroup body, int name) {
        TextView view = (TextView) add(body, R.layout.section);
        view.setText(name);
        heading(view);
    }

    private void info(ViewGroup body, String name, String detail) {
        View row = add(body, R.layout.info_row);
        ((TextView) row.findViewById(R.id.name)).setText(name);
        TextView detailView = row.findViewById(R.id.detail);
        detailView.setText(detail);
        detailView.setVisibility(detail == null ? View.GONE : View.VISIBLE);
    }

    private static void heading(View view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            view.setAccessibilityHeading(true);
        }
    }

    /** The apps that take Mihon's add-store link, one activity per app */
    private List<ResolveInfo> handlers() {
        Intent intent = new Intent(Intent.ACTION_VIEW, ADD_STORE);
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
     * Sends the link to one app explicitly: the handlers' own labels are all "Add extension store", which can't
     * tell two Mihon builds apart in a chooser. It opens in the app's own task, clearing anything above its main
     * screen: Mihon's MainActivity closes itself when it isn't the root of its task
     */
    private void addStore(ResolveInfo handler, String label) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, ADD_STORE)
                    .setClassName(handler.activityInfo.packageName, handler.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, getString(R.string.open_failed, label), Toast.LENGTH_LONG).show();
        }
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
}
