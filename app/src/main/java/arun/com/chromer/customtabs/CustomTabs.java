package arun.com.chromer.customtabs;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.support.annotation.ColorInt;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.customtabs.CustomTabsIntent;
import android.support.customtabs.CustomTabsSession;
import android.support.v4.graphics.ColorUtils;
import android.widget.Toast;

import com.mikepenz.community_material_typeface_library.CommunityMaterial;
import com.mikepenz.iconics.IconicsDrawable;

import java.util.ArrayList;
import java.util.List;

import arun.com.chromer.R;
import arun.com.chromer.customtabs.callbacks.AddHomeShortcutService;
import arun.com.chromer.customtabs.callbacks.ClipboardService;
import arun.com.chromer.customtabs.callbacks.FavShareBroadcastReceiver;
import arun.com.chromer.customtabs.callbacks.OpenInChromeReceiver;
import arun.com.chromer.customtabs.callbacks.OpenInNewTabReceiver;
import arun.com.chromer.customtabs.callbacks.SecondaryBrowserReceiver;
import arun.com.chromer.customtabs.callbacks.ShareBroadcastReceiver;
import arun.com.chromer.customtabs.prefetch.ScannerService;
import arun.com.chromer.customtabs.warmup.WarmUpService;
import arun.com.chromer.db.AppColor;
import arun.com.chromer.db.WebColor;
import arun.com.chromer.dynamictoolbar.AppColorExtractorService;
import arun.com.chromer.dynamictoolbar.WebColorExtractorService;
import arun.com.chromer.preferences.manager.Preferences;
import arun.com.chromer.shared.AppDetectService;
import arun.com.chromer.shared.Constants;
import arun.com.chromer.util.ColorUtil;
import arun.com.chromer.util.Util;
import arun.com.chromer.webheads.WebHeadService;
import timber.log.Timber;

/**
 * A helper class that builds up the view intent according to user preferences and
 * launches custom tab.
 */
public class CustomTabs {
    private static final String ACTION_CUSTOM_TABS_CONNECTION = "android.support.customtabs.action.CustomTabsService";
    private static final String EXTRA_CUSTOM_TABS_KEEP_ALIVE = "android.support.customtabs.extra.KEEP_ALIVE";
    private static final String LOCAL_PACKAGE = "com.google.android.apps.chrome";
    private static final String STABLE_PACKAGE = "com.android.chrome";
    private static final String BETA_PACKAGE = "com.chrome.beta";
    private static final String DEV_PACKAGE = "com.chrome.dev";

    private static final int BOTTOM_OPEN_TAB = 11;
    private static final int BOTTOM_SHARE_TAB = 12;
    /**
     * Fallback in case there was en error launching custom tabs
     */
    private final static CustomTabsFallback CUSTOM_TABS_FALLBACK =
            new CustomTabsFallback() {
                @Override
                public void openUri(Activity activity, Uri uri) {
                    if (activity != null) {
                        final String string = activity.getString(R.string.fallback_msg);
                        Toast.makeText(activity, string, Toast.LENGTH_SHORT).show();
                        try {
                            final Intent target = new Intent(Intent.ACTION_VIEW, uri);
                            activity.startActivity(Intent.createChooser(target, activity.getString(R.string.open_with)));
                        } catch (ActivityNotFoundException e) {
                            Toast.makeText(activity, activity.getString(R.string.unxp_err), Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            };
    /**
     * The context to work with
     */
    private Activity mActivity;
    /**
     * The url for which the custom tab should be launched;
     */
    private String mUrl;
    /**
     * The builder used to customize the custom tab intent
     */
    private CustomTabsIntent.Builder mIntentBuilder;
    /**
     * Client provided custom tab session
     */
    private CustomTabsSession mCustomTabsSession;
    @ColorInt
    private int mToolbarColor = Constants.NO_COLOR;
    /**
     * Toolbar color that overrides the default toolbar color generated by this helper.
     */
    @ColorInt
    private int mToolbarColorOverride = Constants.NO_COLOR;
    /**
     * True if this tab intent is used for web heads. False otherwise.
     */
    private boolean mForWebHead = false;

    /**
     * Private constructor to init our helper
     *
     * @param context the context to work with
     */

    private CustomTabs(Activity context) {
        mActivity = context;
    }

    /**
     * Used to get an instance of this helper
     *
     * @param activity The context from which custom tab should be launched
     * @return A helper instance
     */
    public static CustomTabs from(@NonNull Activity activity) {
        return new CustomTabs(activity);
    }

    /**
     * Opens the URL on a Custom Tab if possible. Otherwise fallsback to opening it on a WebView.
     *
     * @param activity         The host activity.
     * @param customTabsIntent a CustomTabsIntent to be used if Custom Tabs is available.
     * @param uri              the Uri to be opened.
     */
    private static void openCustomTab(Activity activity, CustomTabsIntent customTabsIntent, Uri uri) {
        final String packageName = getCustomTabPackage(activity);

        if (packageName != null) {
            customTabsIntent.intent.setPackage(packageName);
            Intent keepAliveIntent = new Intent()
                    .setClassName(activity.getPackageName(), KeepAliveService.class.getCanonicalName());
            customTabsIntent.intent.putExtra(EXTRA_CUSTOM_TABS_KEEP_ALIVE, keepAliveIntent);
            try {
                customTabsIntent.launchUrl(activity, uri);
                Timber.d("Launched url: %s", uri.toString());
            } catch (Exception e) {
                CUSTOM_TABS_FALLBACK.openUri(activity, uri);
                Timber.e("Called fallback even though a package was found, weird Exception : %s", e.toString());
            }
        } else {
            Timber.e("Called fallback since no package found!");
            CUSTOM_TABS_FALLBACK.openUri(activity, uri);
        }
    }

    /**
     * Attempts to find the custom the best custom tab package to use.
     *
     * @return A package that supports custom tab, null if not present
     */
    @Nullable
    private static String getCustomTabPackage(Context context) {
        final String userPackage = Preferences.customTabApp(context);
        if (userPackage != null && userPackage.length() > 0) {
            return userPackage;
        }
        if (isPackageSupportCustomTabs(context, STABLE_PACKAGE))
            return STABLE_PACKAGE;
        if (isPackageSupportCustomTabs(context, LOCAL_PACKAGE))
            return LOCAL_PACKAGE;

        List<String> supportingPackages = getCustomTabSupportingPackages(context);
        if (!supportingPackages.isEmpty()) {
            return supportingPackages.get(0);
        } else
            return null;
    }

    /**
     * Returns all valid custom tab supporting browser packages on the system. Does not respect if
     * the package is default or not.
     *
     * @param context context to work with
     * @return list of packages supporting CCT
     */
    @TargetApi(Build.VERSION_CODES.M)
    @NonNull
    public static List<String> getCustomTabSupportingPackages(Context context) {
        PackageManager pm = context.getApplicationContext().getPackageManager();
        Intent activityIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("http://www.example.com"));
        // Get all apps that can handle VIEW intents.
        List<ResolveInfo> resolvedActivityList = pm.queryIntentActivities(activityIntent, PackageManager.MATCH_ALL);
        List<String> packagesSupportingCustomTabs = new ArrayList<>();
        for (ResolveInfo info : resolvedActivityList) {
            if (isPackageSupportCustomTabs(context, info.activityInfo.packageName)) {
                packagesSupportingCustomTabs.add(info.activityInfo.packageName);
            }
        }
        return packagesSupportingCustomTabs;
    }

    /**
     * Determines if the provided package name is a valid custom tab provider or not.
     *
     * @param context     Context to work with
     * @param packageName Package name of the app
     * @return true if a provider, false otherwise
     */
    public static boolean isPackageSupportCustomTabs(Context context, @Nullable String packageName) {
        if (packageName == null) {
            return false;
        }
        PackageManager pm = context.getApplicationContext().getPackageManager();
        Intent serviceIntent = new Intent();
        serviceIntent.setAction(ACTION_CUSTOM_TABS_CONNECTION);
        serviceIntent.setPackage(packageName);
        return pm.resolveService(serviceIntent, 0) != null;
    }

    public CustomTabs withSession(@Nullable CustomTabsSession session) {
        if (session != null) {
            mCustomTabsSession = session;
        }
        return this;
    }

    /**
     * Exposed method to set the url for which this CCT should be launched
     *
     * @param url Url of the web site
     */
    public CustomTabs forUrl(@NonNull final String url) {
        mUrl = url.trim();
        return this;
    }

    /**
     * To set if the custom tab is going to be launched from web heads. Default is false.
     *
     * @param isWebHead true if for web heads
     * @return Instance of this class
     */
    public CustomTabs forWebHead(boolean isWebHead) {
        mForWebHead = isWebHead;
        return this;
    }

    /**
     * Clients can specify a toolbar color that will override whatever {@link #prepareToolbar()} sets.
     * Alpha value of the provided color will be ignored.
     *
     * @param overrideColor color to override
     */
    public CustomTabs overrideToolbarColor(@ColorInt int overrideColor) {
        mToolbarColorOverride = ColorUtils.setAlphaComponent(overrideColor, 0xFF);
        return this;
    }

    /**
     * Facade method that does all the heavy work of building up the builder based on user preferences
     *
     * @return Instance of this class
     */
    @NonNull
    public CustomTabs prepare() {
        mIntentBuilder = new CustomTabsIntent.Builder(getSession());
        // set defaults
        mIntentBuilder.setShowTitle(true);
        mIntentBuilder.enableUrlBarHiding();
        mIntentBuilder.addDefaultShareMenuItem();  // TODO make this conditional

        // prepare animations
        prepareAnimations();
        // prepare toolbar color
        prepareToolbar();
        // prepare action button
        prepareActionButton();
        // prepare all the menu items
        prepareMenuItems();
        // prepare all bottom bar item
        prepareBottomBar();
        return this;
    }

    /**
     * Builds custom tab intent from the builder we created so far and launches the custom tab.
     */
    public void launch() {
        assertBuilderInitialized();
        CustomTabsIntent customTabsIntent = mIntentBuilder.build();
        openCustomTab(mActivity, customTabsIntent, Uri.parse(mUrl));

        // Dispose reference
        mActivity = null;
        mCustomTabsSession = null;
    }

    /**
     * Tries to find available sessions for the url to launch in.
     *
     * @return Instance of this class
     */
    @Nullable
    private CustomTabsSession getSession() {
        if (mCustomTabsSession != null) {
            return mCustomTabsSession;
        }
        if (mForWebHead && WebHeadService.getInstance() != null) {
            Timber.d("Using webhead session");
            return WebHeadService.getInstance().getTabSession();
        }

        ScannerService sService = ScannerService.getInstance();
        if (sService != null && sService.getTabSession() != null && Preferences.preFetch(mActivity)) {
            Timber.d("Using scanner session");
            return sService.getTabSession();
        }
        WarmUpService service = WarmUpService.getInstance();
        if (service != null) {
            Timber.d("Using warm up session");
            return service.getTabSession();
        }
        return null;
    }

    /**
     * Used to set the correct custom tab opening/closing animations. Will re use last used animations
     * if the preference did not change from before.
     */
    private void prepareAnimations() {
        assertBuilderInitialized();
        if (Preferences.isAnimationEnabled(mActivity)) {
            int type = Preferences.animationType(mActivity);
            int speed = Preferences.animationSpeed(mActivity);
            int start[] = new int[]{};
            int exit[] = new int[]{};
            switch (speed) {
                case Preferences.ANIMATION_MEDIUM:
                    switch (type) {
                        case 1:
                            start = new int[]{R.anim.slide_in_right_medium, R.anim.slide_out_left_medium};
                            exit = new int[]{R.anim.slide_in_left_medium, R.anim.slide_out_right_medium};
                            break;
                        case 2:
                            start = new int[]{R.anim.slide_up_right_medium, R.anim.slide_down_left_medium};
                            exit = new int[]{R.anim.slide_up_left_medium, R.anim.slide_down_right_medium};
                            break;
                    }
                    break;
                case Preferences.ANIMATION_SHORT:
                    switch (type) {
                        case 1:
                            start = new int[]{R.anim.slide_in_right, R.anim.slide_out_left};
                            exit = new int[]{R.anim.slide_in_left, R.anim.slide_out_right};
                            break;
                        case 2:
                            start = new int[]{R.anim.slide_up_right, R.anim.slide_down_left};
                            exit = new int[]{R.anim.slide_up_left, R.anim.slide_down_right};
                            break;
                    }
                    break;
            }
            // set it to builder
            mIntentBuilder
                    .setStartAnimations(mActivity, start[0], start[1])
                    .setExitAnimations(mActivity, exit[0], exit[1]);
        }
    }

    /**
     * Method to handle tool bar color. Takes care of handling secondary toolbar color as well.
     */
    private void prepareToolbar() {
        assertBuilderInitialized();
        if (mToolbarColorOverride != Constants.NO_COLOR) {
            mToolbarColor = mToolbarColorOverride;
            mIntentBuilder
                    .setToolbarColor(mToolbarColorOverride)
                    .setSecondaryToolbarColor(mToolbarColorOverride);
        } else if (Preferences.isColoredToolbar(mActivity)) {
            // Get the user chosen color first
            mToolbarColor = Preferences.toolbarColor(mActivity);
            if (Preferences.dynamicToolbar(mActivity)) {
                if (Preferences.dynamicToolbarOnApp(mActivity)) {
                    setAppToolbarColor();
                }
                if (Preferences.dynamicToolbarOnWeb(mActivity)) {
                    setWebToolbarColor();
                }
            }
            if (mToolbarColor != Constants.NO_COLOR) {
                mIntentBuilder
                        .setToolbarColor(mToolbarColor)
                        .setSecondaryToolbarColor(mToolbarColor);
            }
        }
    }

    /**
     * Sets the toolbar color based on the web site we are launching for
     */
    private void setWebToolbarColor() {
        // Check if we have the color extracted for this source
        final String host = Uri.parse(mUrl).getHost();
        if (host != null) {
            final List<WebColor> webColors = WebColor.find(WebColor.class, "url = ?", host);

            if (!webColors.isEmpty()) {
                mToolbarColor = webColors.get(0).getColor();
            } else {
                final Intent extractorService = new Intent(mActivity, WebColorExtractorService.class);
                extractorService.setData(Uri.parse(mUrl));
                mActivity.startService(extractorService);
            }
        }
    }

    /**
     * Sets the toolbar color based on launching app.
     */
    private void setAppToolbarColor() {
        try {
            final String lastApp = AppDetectService.getInstance().getLastApp();
            final List<AppColor> appColors = AppColor.find(AppColor.class, "app = ?", lastApp);

            if (!appColors.isEmpty()) {
                mToolbarColor = appColors.get(0).getColor();
            } else {
                final Intent extractorService = new Intent(mActivity, AppColorExtractorService.class);
                extractorService.putExtra("app", lastApp);
                mActivity.startService(extractorService);
            }
        } catch (Exception e) {
            if (AppDetectService.getInstance() == null) {
                mActivity.startService(new Intent(mActivity, AppDetectService.class));
            }
        }
    }

    /**
     * Used to set the action button based on user preferences. Usually secondary browser or favorite share app.
     */
    private void prepareActionButton() {
        assertBuilderInitialized();
        switch (Preferences.preferredAction(mActivity)) {
            case Preferences.PREFERRED_ACTION_BROWSER:
                String pakage = Preferences.secondaryBrowserPackage(mActivity);
                if (Util.isPackageInstalled(mActivity, pakage)) {
                    final Bitmap icon = getAppIconBitmap(pakage);
                    final Intent intent = new Intent(mActivity, SecondaryBrowserReceiver.class);
                    final PendingIntent openBrowserPending = PendingIntent.getBroadcast(mActivity, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
                    //noinspection ConstantConditions
                    mIntentBuilder.setActionButton(icon, mActivity.getString(R.string.choose_secondary_browser), openBrowserPending);
                }
                break;
            case Preferences.PREFERRED_ACTION_FAV_SHARE:
                pakage = Preferences.favSharePackage(mActivity);
                if (Util.isPackageInstalled(mActivity, pakage)) {
                    final Bitmap icon = getAppIconBitmap(pakage);
                    final Intent intent = new Intent(mActivity, FavShareBroadcastReceiver.class);
                    final PendingIntent favSharePending = PendingIntent.getBroadcast(mActivity, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
                    //noinspection ConstantConditions
                    mIntentBuilder.setActionButton(icon, mActivity.getString(R.string.fav_share_app), favSharePending);
                }
                break;
        }
    }

    /**
     * Prepares all the menu items and adds to builder
     */
    private void prepareMenuItems() {
        assertBuilderInitialized();
        preparePreferredAction();
        prepareCopyLink();
        prepareAddToHomeScreen();
        prepareOpenInChrome();
    }

    /**
     * Opposite of what {@link #prepareActionButton()} does. Fills a menu item with either secondary
     * browser or favorite share app.
     */
    private void preparePreferredAction() {
        assertBuilderInitialized();
        switch (Preferences.preferredAction(mActivity)) {
            case Preferences.PREFERRED_ACTION_BROWSER:
                String pkg = Preferences.favSharePackage(mActivity);
                if (Util.isPackageInstalled(mActivity, pkg)) {
                    final String app = Util.getAppNameWithPackage(mActivity, pkg);
                    final String label = String.format(mActivity.getString(R.string.share_with), app);
                    final Intent shareIntent = new Intent(mActivity, FavShareBroadcastReceiver.class);
                    final PendingIntent pendingShareIntent = PendingIntent.getBroadcast(mActivity, 0, shareIntent, PendingIntent.FLAG_UPDATE_CURRENT);
                    mIntentBuilder.addMenuItem(label, pendingShareIntent);
                }
                break;
            case Preferences.PREFERRED_ACTION_FAV_SHARE:
                pkg = Preferences.secondaryBrowserPackage(mActivity);
                if (Util.isPackageInstalled(mActivity, pkg)) {
                    final String app = Util.getAppNameWithPackage(mActivity, pkg);
                    final String label = String.format(mActivity.getString(R.string.open_in_browser), app);
                    final Intent browseIntent = new Intent(mActivity, SecondaryBrowserReceiver.class);
                    final PendingIntent pendingBrowseIntent = PendingIntent.getBroadcast(mActivity, 0, browseIntent, PendingIntent.FLAG_UPDATE_CURRENT);
                    mIntentBuilder.addMenuItem(label, pendingBrowseIntent);
                }
                break;
        }
    }

    private void prepareCopyLink() {
        final Intent clipboardIntent = new Intent(mActivity, ClipboardService.class);
        final PendingIntent serviceIntentPending = PendingIntent.getService(mActivity, 0, clipboardIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        mIntentBuilder.addMenuItem(mActivity.getString(R.string.copy_link), serviceIntentPending);
    }

    /**
     * Adds menu item to enable adding the current url to home screen
     */
    private void prepareAddToHomeScreen() {
        final Intent addShortcutIntent = new Intent(mActivity, AddHomeShortcutService.class);
        final PendingIntent addShortcutPending = PendingIntent.getService(mActivity, 0, addShortcutIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        mIntentBuilder.addMenuItem(mActivity.getString(R.string.add_to_homescreen), addShortcutPending);
    }

    /**
     * Adds an open in chrome option
     */
    private void prepareOpenInChrome() {
        final String customTabPkg = Preferences.customTabApp(mActivity);

        if (Util.isPackageInstalled(mActivity, customTabPkg)) {
            if (customTabPkg.equalsIgnoreCase(BETA_PACKAGE)
                    || customTabPkg.equalsIgnoreCase(DEV_PACKAGE)
                    || customTabPkg.equalsIgnoreCase(STABLE_PACKAGE)) {

                final Intent chromeReceiver = new Intent(mActivity, OpenInChromeReceiver.class);
                final PendingIntent openChromePending = PendingIntent.getBroadcast(mActivity, 0, chromeReceiver, PendingIntent.FLAG_UPDATE_CURRENT);

                final String app = Util.getAppNameWithPackage(mActivity, customTabPkg);
                final String label = String.format(mActivity.getString(R.string.open_in_browser), app);
                mIntentBuilder.addMenuItem(label, openChromePending);
            }
        }
    }

    /**
     * Add all bottom bar actions
     */
    private void prepareBottomBar() {
        if (!Preferences.bottomBar(mActivity)) {
            return;
        }
        int iconColor = ColorUtil.getForegroundWhiteOrBlack(mToolbarColor);
        if (Util.isLollipopAbove()) {
            final Intent openInNewTabIntent = new Intent(mActivity, OpenInNewTabReceiver.class);
            final PendingIntent pendingOpenTabIntent = PendingIntent.getBroadcast(mActivity, 0, openInNewTabIntent, PendingIntent.FLAG_UPDATE_CURRENT);

            Bitmap openTabIcon = new IconicsDrawable(mActivity)
                    .icon(CommunityMaterial.Icon.cmd_plus_box)
                    .color(iconColor)
                    .sizeDp(24).toBitmap();
            mIntentBuilder.addToolbarItem(BOTTOM_OPEN_TAB, openTabIcon, mActivity.getString(R.string.open_in_new_tab), pendingOpenTabIntent);
        }

        final Intent shareIntent = new Intent(mActivity, ShareBroadcastReceiver.class);
        final PendingIntent pendingShareIntent = PendingIntent.getBroadcast(mActivity, 0, shareIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        Bitmap shareIcon = new IconicsDrawable(mActivity)
                .icon(CommunityMaterial.Icon.cmd_share_variant)
                .color(iconColor)
                .sizeDp(24).toBitmap();
        mIntentBuilder.addToolbarItem(BOTTOM_SHARE_TAB, shareIcon, mActivity.getString(R.string.share), pendingShareIntent);
    }

    /**
     * Method to check if the builder was initialized. Will fail fast if not.
     */
    private void assertBuilderInitialized() {
        if (mIntentBuilder == null) {
            throw new IllegalStateException("Intent builder null. Are you sure you called prepare()");
        }
    }

    /**
     * Returns the bitmap of the app icon. It is assumed, the package is installed.
     *
     * @return App icon bitmap
     */
    @Nullable
    private Bitmap getAppIconBitmap(@NonNull String packageName) {
        try {
            Drawable drawable = mActivity.getApplicationContext()
                    .getPackageManager().getApplicationIcon(packageName);
            return Util.drawableToBitmap(drawable);
        } catch (PackageManager.NameNotFoundException e) {
            Timber.e("App icon fetching for %s failed", packageName);
        }
        return null;
    }


    /**
     * To be used as a fallback to open the Uri when Custom Tabs is not available.
     */
    public interface CustomTabsFallback {
        /**
         * @param activity The Activity that wants to open the Uri.
         * @param uri      The uri to be opened by the fallback.
         */
        void openUri(Activity activity, Uri uri);
    }
}
