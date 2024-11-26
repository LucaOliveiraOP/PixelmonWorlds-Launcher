package net.kdt.pojavlaunch;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;


import android.Manifest;
import android.content.SharedPreferences;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.Toast;
import android.widget.ExpandableListView;
import net.kdt.pojavlaunch.modloaders.modpacks.api.CurseforgeApi;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchFilters;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchResult;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModItem;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModDetail;
import net.kdt.pojavlaunch.modloaders.modpacks.api.ModLoader;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentContainerView;
import androidx.fragment.app.FragmentManager;

import com.kdt.mcgui.ProgressLayout;
import com.kdt.mcgui.mcAccountSpinner;
import net.kdt.pojavlaunch.fragments.ForgeInstallFragment;
import net.kdt.pojavlaunch.contracts.OpenDocumentWithExtension;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.extra.ExtraListener;
import net.kdt.pojavlaunch.fragments.MainMenuFragment;
import net.kdt.pojavlaunch.fragments.MicrosoftLoginFragment;
import net.kdt.pojavlaunch.fragments.SelectAuthFragment;
import net.kdt.pojavlaunch.lifecycle.ContextAwareDoneListener;
import net.kdt.pojavlaunch.lifecycle.ContextExecutor;
import net.kdt.pojavlaunch.modloaders.ModloaderListenerProxy;
import net.kdt.pojavlaunch.modloaders.ModloaderDownloadListener;
import net.kdt.pojavlaunch.modloaders.modpacks.ModloaderInstallTracker;
import net.kdt.pojavlaunch.modloaders.modpacks.imagecache.IconCacheJanitor;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.prefs.screens.LauncherPreferenceFragment;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.progresskeeper.TaskCountListener;
import net.kdt.pojavlaunch.services.ProgressServiceKeeper;
import net.kdt.pojavlaunch.tasks.AsyncMinecraftDownloader;
import net.kdt.pojavlaunch.tasks.AsyncVersionList;
import net.kdt.pojavlaunch.modloaders.ForgeDownloadTask;
import net.kdt.pojavlaunch.modloaders.ForgeUtils;
import net.kdt.pojavlaunch.tasks.MinecraftDownloader;
import net.kdt.pojavlaunch.utils.NotificationUtils;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.lang.ref.WeakReference;

public class LauncherActivity extends BaseActivity {
    public static final String SETTING_FRAGMENT_TAG = "SETTINGS_FRAGMENT";

    public final ActivityResultLauncher<Object> modInstallerLauncher =
            registerForActivityResult(new OpenDocumentWithExtension("jar"), (data)->{
                if(data != null) Tools.launchModInstaller(this, data);
            });

    private mcAccountSpinner mAccountSpinner;
    private FragmentContainerView mFragmentView;
    private ImageButton mSettingsButton, mDeleteAccountButton;
    private ProgressLayout mProgressLayout;
    private ProgressServiceKeeper mProgressServiceKeeper;
    private ModloaderInstallTracker mInstallTracker;
    private NotificationManager mNotificationManager;

    /* Allows to switch from one button "type" to another */
    private final FragmentManager.FragmentLifecycleCallbacks mFragmentCallbackListener = new FragmentManager.FragmentLifecycleCallbacks() {
        @Override
        public void onFragmentResumed(@NonNull FragmentManager fm, @NonNull Fragment f) {
            mSettingsButton.setImageDrawable(ContextCompat.getDrawable(getBaseContext(), f instanceof MainMenuFragment
                    ? R.drawable.ic_menu_settings : R.drawable.ic_menu_home));
        }
    };

    /* Listener for the back button in settings */
    private final ExtraListener<String> mBackPreferenceListener = (key, value) -> {
        if(value.equals("true")) onBackPressed();
        return false;
    };

    /* Listener for the auth method selection screen */
    private final ExtraListener<Boolean> mSelectAuthMethod = (key, value) -> {
        Fragment fragment = getSupportFragmentManager().findFragmentById(mFragmentView.getId());
        // Allow starting the add account only from the main menu, should it be moved to fragment itself ?
        if(!(fragment instanceof MainMenuFragment)) return false;

        Tools.swapFragment(this, SelectAuthFragment.class, SelectAuthFragment.TAG, null);
        return false;
    };

    /* Listener for the settings fragment */
    private final View.OnClickListener mSettingButtonListener = v -> {
        Fragment fragment = getSupportFragmentManager().findFragmentById(mFragmentView.getId());
        if(fragment instanceof MainMenuFragment){
            Tools.swapFragment(this, LauncherPreferenceFragment.class, SETTING_FRAGMENT_TAG, null);
        } else{
            // The setting button doubles as a home button now
            Tools.backToMainMenu(this);
        }
    };

    /* Listener for account deletion */
    private final View.OnClickListener mAccountDeleteButtonListener = v -> new AlertDialog.Builder(this)
            .setMessage(R.string.warning_remove_account)
            .setPositiveButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.global_delete, (dialog, which) -> mAccountSpinner.removeCurrentAccount())
            .show();

    private final ExtraListener<Boolean> mLaunchGameListener = (key, value) -> {
        if(mProgressLayout.hasProcesses()){
            Toast.makeText(this, R.string.tasks_ongoing, Toast.LENGTH_LONG).show();
            return false;
        }

        String selectedProfile = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE,"");
        if (LauncherProfiles.mainProfileJson == null || !LauncherProfiles.mainProfileJson.profiles.containsKey(selectedProfile)){
            Toast.makeText(this, R.string.error_no_version, Toast.LENGTH_LONG).show();
            return false;
        }
        MinecraftProfile prof = LauncherProfiles.mainProfileJson.profiles.get(selectedProfile);
        if (prof == null || prof.lastVersionId == null || "Unknown".equals(prof.lastVersionId)){
            Toast.makeText(this, R.string.error_no_version, Toast.LENGTH_LONG).show();
            return false;
        }

        if(mAccountSpinner.getSelectedAccount() == null){
            Toast.makeText(this, R.string.no_saved_accounts, Toast.LENGTH_LONG).show();
            ExtraCore.setValue(ExtraConstants.SELECT_AUTH_METHOD, true);
            return false;
        }
        SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        boolean isFirstStart = prefs.getBoolean("isFirstStart", true);
        boolean isModInstalled = prefs.getBoolean("isModInstalled", false);
        if (isFirstStart) {
            Toast.makeText(this, "Launcher desatualizado", Toast.LENGTH_LONG).show();
            performActionWithVersion("1.16.5-36.2.42");
            SharedPreferences.Editor editor = prefs.edit();
            editor.putBoolean("isFirstStart", false);
            editor.apply();
            return false;
        }
        if (isModInstalled) {
            Toast.makeText(this, "Mod desatualizado", Toast.LENGTH_LONG).show();
            CurseforgeApi curseforgeApi = new CurseforgeApi("Pixelmon 1.16.5-9.1.12-pipe24759");

// Search for the modpack
            SearchFilters searchFilters = new SearchFilters();
            searchFilters.name = "desired-modpack-name";
            searchFilters.isModpack = true;
            SearchResult searchResult = curseforgeApi.searchMod(searchFilters, null);

// Get the details of the first modpack in the search results
            ModItem modItem = searchResult.results[0];
            ModDetail modDetail = curseforgeApi.getModDetails(modItem);

// Install the modpack
            int selectedVersion = 0; // Select the desired version index
            try {
                ModLoader modLoader = curseforgeApi.installMod(modDetail, selectedVersion);
            } catch (IOException e) {
                e.printStackTrace();
                Toast.makeText(this, "Failed to install modpack", Toast.LENGTH_LONG).show();
                return false;
            }
            SharedPreferences.Editor editor = prefs.edit();
            editor.putBoolean("isModInstalled", true);
            editor.apply();
            return false;
        }
        String normalizedVersionId = AsyncMinecraftDownloader.normalizeVersionId(prof.lastVersionId);
        JMinecraftVersionList.Version mcVersion = AsyncMinecraftDownloader.getListedVersion(normalizedVersionId);
        new MinecraftDownloader().start(
                this,
                mcVersion,
                normalizedVersionId,
                new ContextAwareDoneListener(this, normalizedVersionId)
        );
        return false;
    };

    private final TaskCountListener mDoubleLaunchPreventionListener = taskCount -> {
        // Hide the notification that starts the game if there are tasks executing.
        // Prevents the user from trying to launch the game with tasks ongoing.
        if(taskCount > 0) {
            Tools.runOnUiThread(() ->
                    mNotificationManager.cancel(NotificationUtils.NOTIFICATION_ID_GAME_START)
            );
        }
    };

    private ActivityResultLauncher<String> mRequestNotificationPermissionLauncher;
    private WeakReference<Runnable> mRequestNotificationPermissionRunnable;
    private void installModpack() {
        new InstallModpackTask().execute("desired-modpack-name");
    }
    private class InstallModpackTask extends AsyncTask<String, Void, Boolean> {
        @Override
        protected Boolean doInBackground(String... params) {
            String modpackName = params[0];
            try {
                CurseforgeApi curseforgeApi = new CurseforgeApi("your-api-key");

                // Search for the modpack
                SearchFilters searchFilters = new SearchFilters();
                searchFilters.name = modpackName;
                searchFilters.isModpack = true;
                SearchResult searchResult = curseforgeApi.searchMod(searchFilters, null);

                // Get the details of the first modpack in the search results
                ModItem modItem = searchResult.results[0];
                ModDetail modDetail = curseforgeApi.getModDetails(modItem);

                // Install the modpack
                int selectedVersion = 0; // Select the desired version index
                ModLoader modLoader = curseforgeApi.installMod(modDetail, selectedVersion);

                return true;
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }
        }

        @Override
        protected void onPostExecute(Boolean success) {
            if (success) {
                Toast.makeText(LauncherActivity.this, "Modpack installed successfully", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(LauncherActivity.this, "Failed to install modpack", Toast.LENGTH_LONG).show();
            }
        }
    }
    public void performActionWithVersion(String forgeVersion) {
        Log.d("LauncherActivity", "Selected Forge Version: " + forgeVersion);
        //File forgeVersionFile = new File(getFilesDir(), "forge_" + forgeVersion + ".installed");
        //if (forgeVersionFile.exists()) {
        //    return;
        //}
        ModloaderListenerProxy taskProxy = new ModloaderListenerProxy();
        Runnable downloadTask = createDownloadTask(forgeVersion, taskProxy);
        setTaskProxy(taskProxy);
        taskProxy.attachListener(new ModloaderDownloadListener() {
            public void onDownloadFinished(File downloadedFile) {
                Tools.runOnUiThread(() -> {
                    Context context = LauncherActivity.this;
                    taskProxy.detachListener();
                    setTaskProxy(null);
                    // Handle the downloaded file
                    Intent modInstallerStartIntent = new Intent(context, JavaGUILauncherActivity.class);
                    ForgeUtils.addAutoInstallArgs(modInstallerStartIntent, downloadedFile, true);
                    context.startActivity(modInstallerStartIntent);
                });
            }

            public void onDataNotAvailable() {
                Tools.runOnUiThread(() -> {
                    Context context = LauncherActivity.this;
                    taskProxy.detachListener();
                    setTaskProxy(null);
                    Tools.dialog(context, context.getString(R.string.global_error), context.getString(R.string.forge_dl_no_installer));
                });
            }

            public void onDownloadError(Exception e) {
                Tools.runOnUiThread(() -> {
                    Context context = LauncherActivity.this;
                    taskProxy.detachListener();
                    setTaskProxy(null);
                    Tools.showError(context, e);
                });
            }
        });
        new Thread(downloadTask).start();
    }

    private Runnable createDownloadTask(String selectedVersion, ModloaderListenerProxy listenerProxy) {
        return new ForgeDownloadTask(listenerProxy, selectedVersion);
    }

    private void setTaskProxy(ModloaderListenerProxy proxy) {
        // Implementation to set the task proxy
    }
    private void onDownloadVersionListComplete() {
        // Your code here
        Log.d("LauncherActivity", "Download version list complete");
    }
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pojav_launcher);
        FragmentManager fragmentManager = getSupportFragmentManager();
        // If we don't have a back stack root yet...
        if (fragmentManager.getBackStackEntryCount() < 1) {
            // Manually add the first fragment to the backstack to get easily back to it
            fragmentManager.beginTransaction()
                    .setReorderingAllowed(true)
                    .addToBackStack("ROOT")
                    .add(R.id.container_fragment, MainMenuFragment.class, null, "ROOT").commit();
        }

        // Add ForgeInstallFragment to the FragmentManager
        /*fragmentManager.beginTransaction()
                .replace(R.id.container_fragment, new ForgeInstallFragment(), "FORGE_INSTALL_FRAGMENT")
                .commit();*/

        //Call the function that has the contents of onChildClick but without UI logic


        IconCacheJanitor.runJanitor();
        mRequestNotificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isAllowed -> {
                    if (!isAllowed) handleNoNotificationPermission();
                    else {
                        Runnable runnable = Tools.getWeakReference(mRequestNotificationPermissionRunnable);
                        if (runnable != null) runnable.run();
                    }
                }
        );
        getWindow().setBackgroundDrawable(null);
        bindViews();
        checkNotificationPermission();
        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        ProgressKeeper.addTaskCountListener(mDoubleLaunchPreventionListener);
        ProgressKeeper.addTaskCountListener((mProgressServiceKeeper = new ProgressServiceKeeper(this)));

        mSettingsButton.setOnClickListener(mSettingButtonListener);
        mDeleteAccountButton.setOnClickListener(mAccountDeleteButtonListener);
        ProgressKeeper.addTaskCountListener(mProgressLayout);
        ExtraCore.addExtraListener(ExtraConstants.BACK_PREFERENCE, mBackPreferenceListener);
        ExtraCore.addExtraListener(ExtraConstants.SELECT_AUTH_METHOD, mSelectAuthMethod);

        ExtraCore.addExtraListener(ExtraConstants.LAUNCH_GAME, mLaunchGameListener);

        new AsyncVersionList().getVersionList(versions -> ExtraCore.setValue(ExtraConstants.RELEASE_TABLE, versions), false);

        mInstallTracker = new ModloaderInstallTracker(this);

        mProgressLayout.observe(ProgressLayout.DOWNLOAD_MINECRAFT);
        mProgressLayout.observe(ProgressLayout.UNPACK_RUNTIME);
        mProgressLayout.observe(ProgressLayout.INSTALL_MODPACK);
        mProgressLayout.observe(ProgressLayout.AUTHENTICATE_MICROSOFT);
        mProgressLayout.observe(ProgressLayout.DOWNLOAD_VERSION_LIST);
        //performActionWithVersion("1.16.5-36.2.42");
        //SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        //performActionWithVersion("1.16.5-36.2.42");
        //mProgressLayout.observe(ProgressLayout.DOWNLOAD_VERSION_LIST, new ProgressLayout.OnCompleteListener() {
            //@Override
            //public void onComplete() {
           //     onDownloadVersionListComplete();
           // }
       // });
        //boolean isFirstStart = prefs.getBoolean("isFirstStart", true);

        //if (isFirstStart) {
           // performActionWithVersion("1.16.5-36.2.42");

           // SharedPreferences.Editor editor = prefs.edit();
           // editor.putBoolean("isFirstStart", false);
           // editor.apply();
        //}
    }

    @Override
    protected void onResume() {
        super.onResume();
        ContextExecutor.setActivity(this);
        mInstallTracker.attach();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ContextExecutor.clearActivity();
        mInstallTracker.detach();
    }

    @Override
    public boolean setFullscreen() {
        return false;
    }

    @Override
    protected void onStart() {
        super.onStart();
        getSupportFragmentManager().registerFragmentLifecycleCallbacks(mFragmentCallbackListener, true);



    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mProgressLayout.cleanUpObservers();
        ProgressKeeper.removeTaskCountListener(mProgressLayout);
        ProgressKeeper.removeTaskCountListener(mProgressServiceKeeper);
        ExtraCore.removeExtraListenerFromValue(ExtraConstants.BACK_PREFERENCE, mBackPreferenceListener);
        ExtraCore.removeExtraListenerFromValue(ExtraConstants.SELECT_AUTH_METHOD, mSelectAuthMethod);
        ExtraCore.removeExtraListenerFromValue(ExtraConstants.LAUNCH_GAME, mLaunchGameListener);

        getSupportFragmentManager().unregisterFragmentLifecycleCallbacks(mFragmentCallbackListener);
    }

    /** Custom implementation to feel more natural when a backstack isn't present */
    @Override
    public void onBackPressed() {
        MicrosoftLoginFragment fragment = (MicrosoftLoginFragment) getVisibleFragment(MicrosoftLoginFragment.TAG);
        if(fragment != null){
            if(fragment.canGoBack()){
                fragment.goBack();
                return;
            }
        }

        // Check if we are at the root then
        if(getVisibleFragment("ROOT") != null){
            finish();
        }

        super.onBackPressed();
    }

    @Override
    public void onAttachedToWindow() {
        LauncherPreferences.computeNotchSize(this);
    }

    @SuppressWarnings("SameParameterValue")
    private Fragment getVisibleFragment(String tag){
        Fragment fragment = getSupportFragmentManager().findFragmentByTag(tag);
        if(fragment != null && fragment.isVisible()) {
            return fragment;
        }
        return null;
    }

    @SuppressWarnings("unused")
    private Fragment getVisibleFragment(int id){
        Fragment fragment = getSupportFragmentManager().findFragmentById(id);
        if(fragment != null && fragment.isVisible()) {
            return fragment;
        }
        return null;
    }

    private void checkNotificationPermission() {
        if(LauncherPreferences.PREF_SKIP_NOTIFICATION_PERMISSION_CHECK ||
                checkForNotificationPermission()) {
            return;
        }

        if(ActivityCompat.shouldShowRequestPermissionRationale(
                this,
                Manifest.permission.POST_NOTIFICATIONS)) {
            showNotificationPermissionReasoning();
            return;
        }
        askForNotificationPermission(null);
    }

    private void showNotificationPermissionReasoning() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.notification_permission_dialog_title)
                .setMessage(R.string.notification_permission_dialog_text)
                .setPositiveButton(android.R.string.ok, (d, w) -> askForNotificationPermission(null))
                .setNegativeButton(android.R.string.cancel, (d, w)-> handleNoNotificationPermission())
                .show();
    }

    private void handleNoNotificationPermission() {
        LauncherPreferences.PREF_SKIP_NOTIFICATION_PERMISSION_CHECK = true;
        LauncherPreferences.DEFAULT_PREF.edit()
                .putBoolean(LauncherPreferences.PREF_KEY_SKIP_NOTIFICATION_CHECK, true)
                .apply();
        Toast.makeText(this, R.string.notification_permission_toast, Toast.LENGTH_LONG).show();
    }

    public boolean checkForNotificationPermission() {
        return Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_DENIED;
    }

    public void askForNotificationPermission(Runnable onSuccessRunnable) {
        if(Build.VERSION.SDK_INT < 33) return;
        if(onSuccessRunnable != null) {
            mRequestNotificationPermissionRunnable = new WeakReference<>(onSuccessRunnable);
        }
        mRequestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
    }

    /** Stuff all the view boilerplate here */
    private void bindViews(){
        mFragmentView = findViewById(R.id.container_fragment);
        mSettingsButton = findViewById(R.id.setting_button);
        mDeleteAccountButton = findViewById(R.id.delete_account_button);
        mAccountSpinner = findViewById(R.id.account_spinner);
        mProgressLayout = findViewById(R.id.progress_layout);
    }
}