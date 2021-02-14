/*
 * Copyright (C) 2017 Peter Gregus for GravityBox Project (C3C076@xda)
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ceco.oreo.gravitybox;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import android.Manifest.permission;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Environment;
import android.os.FileObserver;
import android.util.Log;
import android.widget.Toast;

public class SettingsManager {
    private static final String BACKUP_PATH = Environment.getExternalStorageDirectory() + "/GravityBox/backup";
    private static final String BACKUP_OK_FLAG_OBSOLETE = BACKUP_PATH + "/.backup_ok";
    private static final String BACKUP_OK_FLAG = BACKUP_PATH + "/.backup_ok_lp";
    private static final String BACKUP_NO_MEDIA = BACKUP_PATH + "/.nomedia";
    private static final String LP_PREFERENCES = "com.ceco.lollipop.gravitybox_preferences.xml";
    private static final String MM_PREFERENCES = "com.ceco.marshmallow.gravitybox_preferences.xml";
    private static final String N_PREFERENCES = "com.ceco.nougat.gravitybox_preferences.xml";

    public interface FileObserverListener {
        void onFileUpdated(String path);
        void onFileAttributesChanged(String path);
    }

    private static Context mContext;
    private static SettingsManager mInstance;
    private WorldReadablePrefs mPrefsMain;
    private WorldReadablePrefs mPrefsLedControl;
    private WorldReadablePrefs mPrefsQuietHours;
    private WorldReadablePrefs mPrefsTuner;
    private FileObserver mFileObserver;
    private List<FileObserverListener> mFileObserverListeners;
    private String mPreferenceDir;

    private SettingsManager(Context context) {
        mContext = !context.isDeviceProtectedStorage() ?
                context.createDeviceProtectedStorageContext() : context;
        mFileObserverListeners = new ArrayList<>();
        mPrefsMain =  new WorldReadablePrefs(mContext, getPreferenceDir(), mContext.getPackageName() + "_preferences");
        mFileObserverListeners.add(mPrefsMain);
        mPrefsLedControl = new WorldReadablePrefs(mContext, getPreferenceDir(), "ledcontrol");
        mFileObserverListeners.add(mPrefsLedControl);
        mPrefsQuietHours = new WorldReadablePrefs(mContext, getPreferenceDir(), "quiet_hours");
        mFileObserverListeners.add(mPrefsQuietHours);
        mPrefsTuner = new WorldReadablePrefs(mContext, getPreferenceDir(), "tuner");
        mFileObserverListeners.add(mPrefsTuner);

        registerFileObserver();
    }

    public String getPreferenceDir() {
        if (mPreferenceDir == null) {
            try {
                SharedPreferences prefs = mContext.getSharedPreferences("dummy", Context.MODE_PRIVATE);
                prefs.edit().putBoolean("dummy", false).commit();
                Field f = prefs.getClass().getDeclaredField("mFile");
                f.setAccessible(true);
                mPreferenceDir = new File(((File) f.get(prefs)).getParent()).getAbsolutePath();
                Log.d("GravityBox", "Preference folder: " + mPreferenceDir);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                Log.e("GravityBox", "Could not determine preference folder path. Returning default.");
                e.printStackTrace();
                mPreferenceDir = mContext.getDataDir() + "/shared_prefs";
            }
        }
        return mPreferenceDir;
    }

    public static synchronized SettingsManager getInstance(Context context) {
        if (context == null && mInstance == null)
            throw new IllegalArgumentException("Context cannot be null");

        if (mInstance == null) {
            mInstance = new SettingsManager(context.getApplicationContext() != null ?
                    context.getApplicationContext() : context);
        }
        return mInstance;
    }

    public boolean backupSettings() {
        if (mContext.checkSelfPermission(permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                mContext.checkSelfPermission(permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(mContext, R.string.permission_storage_denied, Toast.LENGTH_SHORT).show();
            return false;
        }

        // create all necessary backup folders/subfolders in one go
        File targetDir = new File(BACKUP_PATH + "/files/app_picker");
        if (!(targetDir.exists() && targetDir.isDirectory())) {
            if (!targetDir.mkdirs()) {
                Toast.makeText(mContext, R.string.settings_backup_failed, Toast.LENGTH_LONG).show();
                return false;
            }
        }

        // create .nomedia file to disable media scanning on backup folder
        File noMediaFile = new File(BACKUP_NO_MEDIA);
        if (!noMediaFile.exists()) {
            try {
                noMediaFile.createNewFile();
            } catch (IOException ioe) { }
        }

        // delete backup OK flag file first (if exists)
        File backupOkFlagFile = new File(BACKUP_OK_FLAG);
        if (backupOkFlagFile.exists()) {
            backupOkFlagFile.delete();
        }

        // preferences
        String[] prefsFileNames = new String[] { 
                mContext.getPackageName() + "_preferences.xml",
                "ledcontrol.xml",
                "quiet_hours.xml",
                "tuner.xml",
                "lockwallpaper",
                "notifwallpaper",
                "notifwallpaper_landscape",
                "caller_photo",
                "navbar_custom_key_image",
        };
        for (String prefsFileName : prefsFileNames) {
            File prefsFile = new File(getPreferenceDir(), prefsFileName);
            if (prefsFile.exists()) {
                String bupPath = prefsFileName.endsWith(".xml") ? BACKUP_PATH : BACKUP_PATH + "/files";
                File prefsDestFile = new File(bupPath, prefsFileName);
                try {
                    Utils.copyFile(prefsFile, prefsDestFile);
                } catch (IOException e) {
                    e.printStackTrace();
                    Toast.makeText(mContext, R.string.settings_backup_failed, Toast.LENGTH_LONG).show();
                    return false;
                }
            } else if (prefsFileName.equals(prefsFileNames[0])) {
                // normally, this should never happen
                Toast.makeText(mContext, R.string.settings_backup_no_prefs, Toast.LENGTH_LONG).show();
                return false;
            }
        }

        // app picker
        String appPickerFilesDirPath = BACKUP_PATH + "/files/app_picker";
        File sourceDir = new File(getPreferenceDir() + "/app_picker");
        File[] appPickerfileList = sourceDir.listFiles();
        if (appPickerfileList != null) {
            for (File apf : appPickerfileList) {
                File outFile = new File(appPickerFilesDirPath, apf.getName());
                try {
                    Utils.copyFile(apf, outFile);
                } catch (IOException e) {
                    e.printStackTrace();
                    Toast.makeText(mContext, R.string.settings_backup_failed, Toast.LENGTH_LONG).show();
                    return false;
                }
            }
        }

        // other files
        String targetFilesDirPath = BACKUP_PATH + "/files";
        File[] fileList = mContext.getFilesDir().listFiles();
        if (fileList != null) {
            for (File f : fileList) {
                if (f.isFile()) {
                    File outFile = new File(targetFilesDirPath, f.getName());
                    try {
                        Utils.copyFile(f, outFile);
                    } catch (IOException e) {
                        e.printStackTrace();
                        Toast.makeText(mContext, R.string.settings_backup_failed, Toast.LENGTH_LONG).show();
                        return false;
                    }
                }
            }
        }

        try {
            backupOkFlagFile.createNewFile();
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(mContext, R.string.settings_backup_failed, Toast.LENGTH_LONG).show();
            return false;
        }
       
        Toast.makeText(mContext, R.string.settings_backup_success, Toast.LENGTH_SHORT).show();
        return true;
    }

    public boolean isBackupAvailable() {
        File backupOkFlagFile = new File(BACKUP_OK_FLAG);
        return backupOkFlagFile.exists();
    }

    public boolean isBackupObsolete() {
        return new File(BACKUP_OK_FLAG_OBSOLETE).exists() &&
                !isBackupAvailable();
    }

    public boolean restoreSettings() {
        if (mContext.checkSelfPermission(permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                mContext.checkSelfPermission(permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(mContext, R.string.permission_storage_denied, Toast.LENGTH_SHORT).show();
            return false;
        }

        if (!isBackupAvailable()) {
            Toast.makeText(mContext, R.string.settings_restore_no_backup, Toast.LENGTH_SHORT).show();
            return false;
        }

        // Make UUID file that serves as flag to perform some tasks at next boot after restore
        String uuid = "uuid_" + getOrCreateUuid();
        try {
            new File(mContext.getFilesDir() + "/" + uuid).createNewFile();
        } catch (IOException e) { /* ignore */ }

        // preferences
        String[] prefsFileNames = new String[] { 
                mContext.getPackageName() + "_preferences.xml",
                "ledcontrol.xml",
                "quiet_hours.xml",
                "tuner.xml",
                "lockwallpaper",
                "notifwallpaper",
                "notifwallpaper_landscape",
                "caller_photo",
                "navbar_custom_key_image",
        };
        for (String prefsFileName : prefsFileNames) {
            String bupPath = prefsFileName.endsWith(".xml") ? BACKUP_PATH : BACKUP_PATH + "/files";
            File prefsFile = new File(bupPath, prefsFileName);
            // try N preferences if no O prefs file exists
            if (prefsFileName.equals(prefsFileNames[0]) && !prefsFile.exists())
                prefsFile = new File(bupPath, N_PREFERENCES);
            // try MM preferences if no N prefs file exists
            if (prefsFileName.equals(prefsFileNames[0]) && !prefsFile.exists())
                prefsFile = new File(bupPath, MM_PREFERENCES);
            // try LP preferences if no MM prefs file exists
            if (prefsFileName.equals(prefsFileNames[0]) && !prefsFile.exists())
                prefsFile = new File(bupPath, LP_PREFERENCES);
            if (prefsFile.exists()) {
                File prefsDestFile = new File(getPreferenceDir(), prefsFileName);
                try {
                    Utils.copyFile(prefsFile, prefsDestFile);
                    prefsDestFile.setReadable(true, false);
                } catch (IOException e) {
                    e.printStackTrace();
                    Toast.makeText(mContext, R.string.settings_restore_failed, Toast.LENGTH_LONG).show();
                    return false;
                }
            } else if (prefsFileName.equals(prefsFileNames[0])) {
                Toast.makeText(mContext, R.string.settings_restore_no_backup, Toast.LENGTH_SHORT).show();
                return false;
            }
        }

        // app picker
        String appPickerFilesDirPath = getPreferenceDir() + "/app_picker";
        File appPickerFilesDir = new File(appPickerFilesDirPath);
        if (!(appPickerFilesDir.exists() && appPickerFilesDir.isDirectory())) {
            if (appPickerFilesDir.mkdirs()) {
                appPickerFilesDir.setExecutable(true, false);
                appPickerFilesDir.setReadable(true, false);
            }
        }
        File sourceDir = new File(BACKUP_PATH + "/files/app_picker");
        File[] appPickerfileList = sourceDir.listFiles();
        if (appPickerfileList != null) {
            for (File apf : appPickerfileList) {
                File outFile = new File(appPickerFilesDirPath, apf.getName());
                try {
                    Utils.copyFile(apf, outFile);
                    outFile.setReadable(true, false);
                } catch (IOException e) {
                    e.printStackTrace();
                    Toast.makeText(mContext, R.string.settings_restore_failed, Toast.LENGTH_LONG).show();
                    return true;
                }
            }
        }

        // other files
        String targetFilesDirPath = mContext.getFilesDir().getAbsolutePath();
        File targetFilesDir = new File(targetFilesDirPath);
        if (!(targetFilesDir.exists() && targetFilesDir.isDirectory())) {
            if (targetFilesDir.mkdirs()) {
                targetFilesDir.setExecutable(true, false);
                targetFilesDir.setReadable(true, false);
            }
        }
        File[] fileList = new File(BACKUP_PATH + "/files").listFiles();
        if (fileList != null) {
            for (File f : fileList) {
                if (f.isFile()) {
                    File outFile = new File(targetFilesDirPath + "/" + f.getName());
                    try {
                        Utils.copyFile(f, outFile);
                        outFile.setReadable(true, false);
                    } catch (IOException e) {
                        e.printStackTrace();
                        Toast.makeText(mContext, R.string.settings_restore_failed, Toast.LENGTH_LONG).show();
                        return false;
                    }
                }
            }
        }

        Toast.makeText(mContext, R.string.settings_restore_success, Toast.LENGTH_SHORT).show();
        return true;
    }

    public String getOrCreateUuid() {
        String uuid = mPrefsMain.getString("settings_uuid", null);
        if (uuid == null) {
            uuid = UUID.randomUUID().toString();
            mPrefsMain.edit().putString("settings_uuid", uuid).commit();
        }
        return uuid;
    }

    public void resetUuid(String uuid) {
        mPrefsMain.edit().putString("settings_uuid", uuid).commit();
    }

    public void resetUuid() {
        resetUuid(null);
    }

    public void fixFolderPermissionsAsync() {
        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                // main dir
                File pkgFolder = mContext.getDataDir();
                if (pkgFolder.exists()) {
                    pkgFolder.setExecutable(true, false);
                    pkgFolder.setReadable(true, false);
                }
                // cache dir
                File cacheFolder = mContext.getCacheDir();
                if (cacheFolder.exists()) {
                    cacheFolder.setExecutable(true, false);
                    cacheFolder.setReadable(true, false);
                }
                // files dir
                File filesFolder = mContext.getFilesDir();
                if (filesFolder.exists()) {
                    filesFolder.setExecutable(true, false);
                    filesFolder.setReadable(true, false);
                    for (File f : filesFolder.listFiles()) {
                        f.setExecutable(true, false);
                        f.setReadable(true, false);
                    }
                }
                // app picker
                File appPickerFolder = new File(getPreferenceDir() + "/app_picker");
                if (appPickerFolder.exists()) {
                    appPickerFolder.setExecutable(true, false);
                    appPickerFolder.setReadable(true, false);
                    for (File f : appPickerFolder.listFiles()) {
                        f.setExecutable(true, false);
                        f.setReadable(true, false);
                    }
                }
            }
        });
    }

    public WorldReadablePrefs getMainPrefs() {
        return mPrefsMain;
    }

    public WorldReadablePrefs getLedControlPrefs() {
        return mPrefsLedControl; 
    }

    public WorldReadablePrefs getQuietHoursPrefs() {
        return mPrefsQuietHours;
    }

    public WorldReadablePrefs getTunerPrefs() {
        return mPrefsTuner;
    }

    private void registerFileObserver() {
        mFileObserver = new FileObserver(getPreferenceDir(),
                FileObserver.ATTRIB | FileObserver.CLOSE_WRITE) {
            @Override
            public void onEvent(int event, String path) {
                for (FileObserverListener l : mFileObserverListeners) {
                    if ((event & FileObserver.ATTRIB) != 0)
                        l.onFileAttributesChanged(path);
                    if ((event & FileObserver.CLOSE_WRITE) != 0)
                        l.onFileUpdated(path);
                }
            }
        };
        mFileObserver.startWatching();
    }
}
