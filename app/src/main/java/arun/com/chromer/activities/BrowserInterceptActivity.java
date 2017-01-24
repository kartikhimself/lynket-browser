package arun.com.chromer.activities;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.annotation.StringRes;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.widget.Toast;

import com.afollestad.materialdialogs.DialogAction;
import com.afollestad.materialdialogs.MaterialDialog;

import arun.com.chromer.R;
import arun.com.chromer.activities.blacklist.BlackListManager;
import arun.com.chromer.preferences.manager.Preferences;
import arun.com.chromer.shared.AppDetectionManager;
import arun.com.chromer.shared.Constants;
import arun.com.chromer.util.Utils;
import arun.com.chromer.webheads.helper.ProxyActivity;
import timber.log.Timber;

@SuppressLint("GoogleAppIndexingApiWarning")
public class BrowserInterceptActivity extends AppCompatActivity {
    private MaterialDialog dialog;

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (getIntent() == null || getIntent().getData() == null) {
            Toast.makeText(this, getString(R.string.unsupported_link), Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        signalMainFinish();

        final boolean isFromNewTab = getIntent().getBooleanExtra(Constants.EXTRA_KEY_FROM_NEW_TAB, false);

        // Check if we should blacklist the launching app
        if (Preferences.blacklist(this)) {
            final String lastApp = AppDetectionManager.getInstance(this).getNonFilteredPackage();
            if (!TextUtils.isEmpty(lastApp) && BlackListManager.isPackageBlackListed(lastApp)) {
                // The calling app was blacklisted by user, perform blacklisting.
                performBlacklistAction();
                return;
            }
        }

        // If user prefers to open in bubbles, then start the web head service which will take care
        // of pre fetching and loading the bubble.
        if (Preferences.webHeads(this)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, getString(R.string.web_head_permission_toast), Toast.LENGTH_LONG).show();
                    final Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } else {
                    launchWebHead(isFromNewTab);
                }
            } else {
                launchWebHead(isFromNewTab);
            }
        } else {
            Intent customTabActivity = new Intent(this, CustomTabActivity.class);
            customTabActivity.setData(getIntent().getData());
            if (isFromNewTab || Preferences.mergeTabs(this)) {
                customTabActivity.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
                customTabActivity.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            }
            customTabActivity.putExtra(Constants.EXTRA_KEY_FROM_NEW_TAB, isFromNewTab);
            startActivity(customTabActivity);
        }

        finish();
    }

    private void signalMainFinish() {
        LocalBroadcastManager.getInstance(this)
                .sendBroadcast(new Intent(Constants.ACTION_CLOSE_MAIN));
    }

    /**
     * Performs the blacklist action, which is opening the link we received in the user's
     * preferred browser.
     * We try to formulate a intent with user's secondary browser and launch it. If it fails we show
     * a dialog and explain what went wrong.
     */
    private void performBlacklistAction() {
        final String secondaryBrowserPackage = Preferences.secondaryBrowserPackage(this);

        if (secondaryBrowserPackage == null) {
            showSecondaryBrowserHandlingError(R.string.secondary_browser_not_error);
            return;
        }

        if (Utils.isPackageInstalled(this, secondaryBrowserPackage)) {
            final Intent webIntentExplicit = getOriginalIntentCopy(getIntent());
            webIntentExplicit.setPackage(secondaryBrowserPackage);
            try {
                startActivity(webIntentExplicit);
                finish();
            } catch (Exception e) {
                Timber.e("Secondary browser launch error: %s", e.getMessage());
                showSecondaryBrowserHandlingError(R.string.secondary_browser_launch_error);
            }
        } else {
            showSecondaryBrowserHandlingError(R.string.secondary_browser_not_installed);
        }
    }

    private void showSecondaryBrowserHandlingError(@StringRes int stringRes) {
        closeDialogs();
        dialog = new MaterialDialog.Builder(this)
                .title(R.string.secondary_browser_launching_error_title)
                .content(stringRes)
                .iconRes(R.mipmap.ic_launcher)
                .positiveText(R.string.launch_setting)
                .negativeText(android.R.string.cancel)
                .onPositive(new MaterialDialog.SingleButtonCallback() {
                    @Override
                    public void onClick(@NonNull MaterialDialog dialog, @NonNull DialogAction which) {
                        final Intent chromerIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
                        chromerIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        startActivity(chromerIntent);
                    }
                })
                .dismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialog) {
                        finish();
                    }
                }).show();
    }

    private void closeDialogs() {
        if (dialog != null) {
            dialog.dismiss();
        }
    }

    private void launchWebHead(boolean isNewTab) {
        Intent webHeadLauncher = new Intent(this, ProxyActivity.class);
        webHeadLauncher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!isNewTab)
            webHeadLauncher.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);

        webHeadLauncher.putExtra(Constants.EXTRA_KEY_FROM_NEW_TAB, isNewTab);
        webHeadLauncher.setData(getIntent().getData());
        startActivity(webHeadLauncher);
    }

    @NonNull
    private Intent getOriginalIntentCopy(@NonNull Intent originalIntent) {
        Intent copy = new Intent(Intent.ACTION_VIEW, originalIntent.getData());
        if (originalIntent.getExtras() != null) {
            copy.putExtras(originalIntent.getExtras());
        }
        return copy;
    }
}
