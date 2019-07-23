/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License.
 */

package com.microsoft.appcenter.sasquatch.activities;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.FileObserver;
import android.preference.CheckBoxPreference;
import android.preference.Preference;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.text.InputType;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.Log;
import android.util.Patterns;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.Toast;

import com.microsoft.appcenter.AppCenter;
import com.microsoft.appcenter.AppCenterService;
import com.microsoft.appcenter.analytics.Analytics;
import com.microsoft.appcenter.analytics.AnalyticsPrivateHelper;
import com.microsoft.appcenter.auth.Auth;
import com.microsoft.appcenter.crashes.Crashes;
import com.microsoft.appcenter.data.Data;
import com.microsoft.appcenter.distribute.Distribute;
import com.microsoft.appcenter.push.Push;
import com.microsoft.appcenter.sasquatch.R;
import com.microsoft.appcenter.sasquatch.activities.MainActivity.StartType;
import com.microsoft.appcenter.sasquatch.eventfilter.EventFilter;
import com.microsoft.appcenter.utils.PrefStorageConstants;
import com.microsoft.appcenter.utils.async.AppCenterFuture;

import java.io.File;
import java.lang.reflect.Method;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.microsoft.appcenter.sasquatch.activities.ActivityConstants.ANALYTICS_TRANSMISSION_INTERVAL_KEY;
import static com.microsoft.appcenter.sasquatch.activities.ActivityConstants.DEFAULT_TRANSMISSION_INTERVAL_IN_SECONDS;
import static com.microsoft.appcenter.sasquatch.activities.MainActivity.APPCENTER_START_TYPE;
import static com.microsoft.appcenter.sasquatch.activities.MainActivity.APP_SECRET_KEY;
import static com.microsoft.appcenter.sasquatch.activities.MainActivity.FIREBASE_ENABLED_KEY;
import static com.microsoft.appcenter.sasquatch.activities.MainActivity.LOG_URL_KEY;
import static com.microsoft.appcenter.sasquatch.activities.MainActivity.MAX_STORAGE_SIZE_KEY;
import static com.microsoft.appcenter.sasquatch.activities.MainActivity.TARGET_KEY;
import static com.microsoft.appcenter.sasquatch.activities.MainActivity.USER_ID_KEY;

public class SettingsActivity extends AppCompatActivity {

    private static final String LOG_TAG = "SettingsActivity";

    private static final int FILE_ATTACHMENT_DIALOG_ID = 1;

    private static final int DEFAULT_MAX_STORAGE_SIZE = 10 * 1024 * 1024;

    private static boolean sRumStarted;

    private static boolean sEventFilterStarted;

    private static boolean sNeedRestartOnStartTypeUpdate;

    private static boolean sAnalyticsPaused;

    private static String sInitialStartType;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sNeedRestartOnStartTypeUpdate = !MainActivity.sSharedPreferences.getString(APPCENTER_START_TYPE, StartType.APP_SECRET.toString()).equals(StartType.SKIP_START.toString());
        getFragmentManager().beginTransaction()
                .replace(android.R.id.content, new SettingsFragment())
                .commit();
    }

    public static class SettingsFragment extends android.preference.PreferenceFragment implements SharedPreferences.OnSharedPreferenceChangeListener {

        private static final String UUID_FORMAT_REGEX = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

        private FileObserver mDatabaseFileObserver;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            addPreferencesFromResource(R.xml.settings);
            initCheckBoxSetting(R.string.appcenter_state_key, R.string.appcenter_state_summary_enabled, R.string.appcenter_state_summary_disabled, new HasEnabled() {

                @Override
                public void setEnabled(boolean enabled) {
                    AppCenter.setEnabled(enabled);
                    sAnalyticsPaused = false;
                }

                @Override
                public boolean isEnabled() {
                    return AppCenter.isEnabled().get();
                }
            });
            initClickableSetting(R.string.storage_size_key, Formatter.formatFileSize(getActivity(), MainActivity.sSharedPreferences.getLong(MAX_STORAGE_SIZE_KEY, DEFAULT_MAX_STORAGE_SIZE)), new Preference.OnPreferenceClickListener() {

                @Override
                public boolean onPreferenceClick(final Preference preference) {
                    final EditText input = new EditText(getActivity());
                    input.setInputType(InputType.TYPE_CLASS_NUMBER);
                    input.setHint(R.string.size_in_bytes);
                    input.setText(String.format(Locale.ENGLISH, "%d", MainActivity.sSharedPreferences.getLong(MAX_STORAGE_SIZE_KEY, DEFAULT_MAX_STORAGE_SIZE)));
                    input.setSelection(input.getText().length());
                    new AlertDialog.Builder(getActivity()).setTitle(R.string.storage_size_title).setView(input)
                            .setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {

                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    long newSize = 0;
                                    try {
                                        newSize = Long.parseLong(input.getText().toString());
                                    } catch (NumberFormatException ignored) {
                                    }
                                    if (newSize > 0) {
                                        MainActivity.sSharedPreferences.edit().putLong(MAX_STORAGE_SIZE_KEY, newSize).apply();
                                        Toast.makeText(getActivity(), String.format(getActivity().getString(R.string.storage_size_changed_format), Formatter.formatFileSize(getActivity(), newSize)), Toast.LENGTH_SHORT).show();
                                    } else {
                                        Toast.makeText(getActivity(), R.string.storage_size_error, Toast.LENGTH_SHORT).show();
                                    }
                                    preference.setSummary(Formatter.formatFileSize(getActivity(), newSize));
                                }
                            })
                            .setNegativeButton(R.string.cancel, null)
                            .create().show();
                    return true;
                }
            });
            final String DATABASE_NAME = "com.microsoft.appcenter.persistence";
            final File dbFile = getActivity().getDatabasePath(DATABASE_NAME);
            initClickableSetting(R.string.storage_file_size_key, Formatter.formatFileSize(getActivity(), dbFile.length()), null);
            mDatabaseFileObserver = new FileObserver(dbFile.getAbsolutePath(), FileObserver.MODIFY | FileObserver.CLOSE_WRITE) {

                @Override
                public void onEvent(int event, @Nullable String path) {
                    onDatabaseFileChanged(dbFile);
                }
            };

            /* Analytics. */
            initCheckBoxSetting(R.string.appcenter_analytics_state_key, R.string.appcenter_analytics_state_summary_enabled, R.string.appcenter_analytics_state_summary_disabled, new HasEnabled() {

                @Override
                public void setEnabled(boolean enabled) {
                    Analytics.setEnabled(enabled);
                    sAnalyticsPaused = false;
                }

                @Override
                public boolean isEnabled() {
                    return Analytics.isEnabled().get();
                }
            });
            initCheckBoxSetting(R.string.appcenter_analytics_pause_key, R.string.appcenter_analytics_pause_summary_paused, R.string.appcenter_analytics_pause_summary_resumed, new HasEnabled() {

                @Override
                public boolean isEnabled() {
                    return sAnalyticsPaused;
                }

                @Override
                public void setEnabled(boolean enabled) {
                    if (enabled) {
                        Analytics.pause();
                    } else {
                        Analytics.resume();
                    }
                    sAnalyticsPaused = enabled;
                }
            });
            int interval = MainActivity.sSharedPreferences.getInt(ANALYTICS_TRANSMISSION_INTERVAL_KEY, DEFAULT_TRANSMISSION_INTERVAL_IN_SECONDS);
            initClickableSetting(R.string.appcenter_analytics_transmission_interval_key, getTransmissionInterval(interval), new Preference.OnPreferenceClickListener() {

                @Override
                public boolean onPreferenceClick(final Preference preference) {

                    /* Initialize views for dialog. */
                    final EditText input = new EditText(getActivity());
                    input.setInputType(InputType.TYPE_NUMBER_FLAG_SIGNED);
                    input.setHint(R.string.time_interval_in_seconds);
                    input.setText(String.format(Locale.ENGLISH, "%d", MainActivity.sSharedPreferences.getInt(ANALYTICS_TRANSMISSION_INTERVAL_KEY, DEFAULT_TRANSMISSION_INTERVAL_IN_SECONDS)));
                    input.setSelection(input.getText().length());

                    /* Display dialog. */
                    new AlertDialog.Builder(getActivity()).setTitle(R.string.appcenter_analytics_transmission_interval_title).setView(input)
                            .setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {

                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    int newInterval;
                                    try {
                                        newInterval = Integer.parseInt(input.getText().toString());
                                    } catch (NumberFormatException ignored) {
                                        Toast.makeText(getActivity(), getActivity().getString(R.string.analytics_transmission_interval_invalid_value), Toast.LENGTH_SHORT).show();
                                        return;
                                    }
                                    if (newInterval == ActivityConstants.DEFAULT_TRANSMISSION_INTERVAL_IN_SECONDS) {
                                        MainActivity.sSharedPreferences.edit().remove(ANALYTICS_TRANSMISSION_INTERVAL_KEY).apply();
                                    } else {
                                        MainActivity.sSharedPreferences.edit().putInt(ANALYTICS_TRANSMISSION_INTERVAL_KEY, newInterval).apply();
                                    }
                                    String intervalString = getTransmissionInterval(newInterval);
                                    preference.setSummary(intervalString);
                                    Toast.makeText(getActivity(), intervalString, Toast.LENGTH_SHORT).show();

                                    /* Setting interval without restarting works if we used SKIP_START and has not started yet by changing startType. */
                                    Analytics.setTransmissionInterval(newInterval);
                                }
                            })
                            .setNegativeButton(R.string.cancel, null)
                            .create().show();
                    return true;
                }
            });
            initCheckBoxSetting(R.string.appcenter_auto_page_tracking_key, R.string.appcenter_auto_page_tracking_enabled, R.string.appcenter_auto_page_tracking_disabled, new HasEnabled() {

                @Override
                public boolean isEnabled() {
                    return AnalyticsPrivateHelper.isAutoPageTrackingEnabled();
                }

                @Override
                public void setEnabled(boolean enabled) {
                    AnalyticsPrivateHelper.setAutoPageTrackingEnabled(enabled);
                }
            });

            /* Crashes. */
            initCheckBoxSetting(R.string.appcenter_crashes_state_key, R.string.appcenter_crashes_state_summary_enabled, R.string.appcenter_crashes_state_summary_disabled, new HasEnabled() {

                @Override
                public void setEnabled(boolean enabled) {
                    Crashes.setEnabled(enabled);
                }

                @Override
                public boolean isEnabled() {
                    return Crashes.isEnabled().get();
                }
            });
            initChangeableSetting(R.string.appcenter_crashes_text_attachment_key, getCrashesTextAttachmentSummary(), new Preference.OnPreferenceChangeListener() {

                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    MainActivity.setTextAttachment((String) newValue);
                    preference.setSummary(getCrashesTextAttachmentSummary());
                    return true;
                }
            });
            initClickableSetting(R.string.appcenter_crashes_file_attachment_key, getCrashesFileAttachmentSummary(), new Preference.OnPreferenceClickListener() {

                @Override
                public boolean onPreferenceClick(Preference preference) {
                    Intent intent;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                        intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                    } else {
                        intent = new Intent(Intent.ACTION_GET_CONTENT);
                    }
                    intent.setType("*/*");
                    startActivityForResult(Intent.createChooser(intent, "Select attachment file"), FILE_ATTACHMENT_DIALOG_ID);
                    return true;
                }
            });

            /* Distribute. */
            initCheckBoxSetting(R.string.appcenter_distribute_state_key, R.string.appcenter_distribute_state_summary_enabled, R.string.appcenter_distribute_state_summary_disabled, new HasEnabled() {

                @Override
                public void setEnabled(boolean enabled) {
                    Distribute.setEnabled(enabled);
                }

                @Override
                public boolean isEnabled() {
                    return Distribute.isEnabled().get();
                }
            });
            initCheckBoxSetting(R.string.appcenter_distribute_debug_state_key, R.string.appcenter_distribute_debug_summary_enabled, R.string.appcenter_distribute_debug_summary_disabled, new HasEnabled() {

                @Override
                public boolean isEnabled() {
                    return MainActivity.sSharedPreferences.getBoolean(getString(R.string.appcenter_distribute_debug_state_key), false);
                }

                @Override
                public void setEnabled(boolean enabled) {
                    MainActivity.sSharedPreferences.edit().putBoolean(getString(R.string.appcenter_distribute_debug_state_key), enabled).apply();
                    Distribute.setEnabledForDebuggableBuild(enabled);
                }
            });

            /* Push. */
            initCheckBoxSetting(R.string.appcenter_push_state_key, R.string.appcenter_push_state_summary_enabled, R.string.appcenter_push_state_summary_disabled, new HasEnabled() {

                @Override
                public void setEnabled(boolean enabled) {
                    Push.setEnabled(enabled);
                }

                @Override
                public boolean isEnabled() {
                    return Push.isEnabled().get();
                }
            });

            /* Auth. */
            initCheckBoxSetting(R.string.appcenter_auth_state_key, R.string.appcenter_auth_state_summary_enabled, R.string.appcenter_auth_state_summary_disabled, new HasEnabled() {

                @Override
                public boolean isEnabled() {
                    return Auth.isEnabled().get();
                }

                @Override
                public void setEnabled(boolean enabled) {
                    Auth.setEnabled(enabled);
                }
            });

            /* Data. */
            initCheckBoxSetting(R.string.appcenter_data_state_key, R.string.appcenter_data_state_summary_enabled, R.string.appcenter_data_state_summary_disabled, new HasEnabled() {

                @Override
                public boolean isEnabled() {
                    return Data.isEnabled().get();
                }

                @Override
                public void setEnabled(boolean enabled) {
                    Data.setEnabled(enabled);
                }
            });

            /* Push. */
            initCheckBoxSetting(R.string.appcenter_push_firebase_state_key, R.string.appcenter_push_firebase_summary_enabled, R.string.appcenter_push_firebase_summary_disabled, new HasEnabled() {

                @Override
                @SuppressWarnings("unchecked")
                public void setEnabled(boolean enabled) {
                    try {
                        if (enabled) {
                            Push.enableFirebaseAnalytics(getActivity());
                        } else {

                            /* Remove reflection once vanilla build variant is removed and merged with firebase build variant. */
                            try {
                                Class firebaseAnalyticsClass = Class.forName("com.google.firebase.analytics.FirebaseAnalytics");
                                Object analyticsInstance = firebaseAnalyticsClass.getMethod("getInstance", Context.class).invoke(null, getActivity());
                                firebaseAnalyticsClass.getMethod("setAnalyticsCollectionEnabled", boolean.class).invoke(analyticsInstance, false);
                            } catch (Exception ignored) {

                                /* Nothing to handle; this is reached if Firebase isn't being used. */
                            }
                        }
                        MainActivity.sSharedPreferences.edit().putBoolean(FIREBASE_ENABLED_KEY, enabled).apply();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }

                @Override
                public boolean isEnabled() {
                    return isFirebaseEnabled();
                }
            });

            /* Real User Measurements. */
            try {
                @SuppressWarnings("unchecked") final Class<? extends AppCenterService> rum = (Class<? extends AppCenterService>) Class.forName("com.microsoft.appcenter.rum.RealUserMeasurements");
                final Method isEnabled = rum.getMethod("isEnabled");
                final Method setEnabled = rum.getMethod("setEnabled", boolean.class);
                initCheckBoxSetting(R.string.appcenter_rum_state_key, R.string.appcenter_rum_state_summary_enabled, R.string.appcenter_rum_state_summary_disabled, new HasEnabled() {

                    @Override
                    public void setEnabled(boolean enabled) {
                        try {
                            if (!sRumStarted) {
                                rum.getMethod("setRumKey", String.class).invoke(null, getString(R.string.rum_key));
                                AppCenter.start(rum);
                                sRumStarted = true;
                            }
                            setEnabled.invoke(null, enabled);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }

                    @Override
                    @SuppressWarnings("unchecked")
                    public boolean isEnabled() {
                        try {
                            return sRumStarted && ((AppCenterFuture<Boolean>) isEnabled.invoke(null)).get();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }
                });
            } catch (Exception e) {
                getPreferenceScreen().removePreference(findPreference(getString(R.string.real_user_measurements_key)));
            }

            /* EventFilter. */
            initCheckBoxSetting(R.string.appcenter_event_filter_state_key, R.string.appcenter_event_filter_state_summary_enabled, R.string.appcenter_event_filter_state_summary_disabled, new HasEnabled() {

                @Override
                public void setEnabled(boolean enabled) {
                    if (!sEventFilterStarted) {
                        AppCenter.start(EventFilter.class);
                        sEventFilterStarted = true;
                    }
                    EventFilter.setEnabled(enabled);
                }

                @Override
                public boolean isEnabled() {
                    return sEventFilterStarted && EventFilter.isEnabled().get();
                }
            });

            /* Auto page tracking. */
            initCheckBoxSetting(R.string.appcenter_auto_page_tracking_key, R.string.appcenter_auto_page_tracking_enabled, R.string.appcenter_auto_page_tracking_disabled, new HasEnabled() {

                @Override
                public boolean isEnabled() {
                    return AnalyticsPrivateHelper.isAutoPageTrackingEnabled();
                }

                @Override
                public void setEnabled(boolean enabled) {
                    AnalyticsPrivateHelper.setAutoPageTrackingEnabled(enabled);
                }
            });

            /* Application Information. */
            initClickableSetting(R.string.install_id_key, String.valueOf(AppCenter.getInstallId().get()), new Preference.OnPreferenceClickListener() {

                @Override
                public boolean onPreferenceClick(final Preference preference) {
                    final EditText input = new EditText(getActivity());
                    input.setInputType(InputType.TYPE_CLASS_TEXT);
                    input.setText(String.valueOf(AppCenter.getInstallId().get()));

                    new AlertDialog.Builder(getActivity()).setTitle(R.string.install_id_title).setView(input)
                            .setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {

                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    if (input.getText().toString().matches(UUID_FORMAT_REGEX)) {
                                        UUID uuid = UUID.fromString(input.getText().toString());
                                        SharedPreferences appCenterPreferences = getActivity().getSharedPreferences("AppCenter", Context.MODE_PRIVATE);
                                        appCenterPreferences.edit().putString(PrefStorageConstants.KEY_INSTALL_ID, uuid.toString()).apply();
                                        Toast.makeText(getActivity(), String.format(getActivity().getString(R.string.install_id_changed_format), uuid.toString()), Toast.LENGTH_SHORT).show();
                                    } else {
                                        Toast.makeText(getActivity(), R.string.install_id_invalid, Toast.LENGTH_SHORT).show();
                                    }
                                    preference.setSummary(String.valueOf(AppCenter.getInstallId().get()));
                                }
                            })
                            .setNegativeButton(R.string.cancel, null)
                            .create().show();
                    return true;
                }
            });

            /* When changing start type from SKIP_START to other type, we need to trigger a preference change to update the display from null to actual value. */
            initChangeableSetting(R.string.install_id_key, String.valueOf(AppCenter.getInstallId().get()), new Preference.OnPreferenceChangeListener() {

                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    preference.setSummary(String.valueOf(AppCenter.getInstallId().get()));
                    return true;
                }
            });
            initEditText(R.string.app_secret_key, R.string.app_secret_title, APP_SECRET_KEY, getString(R.string.app_secret), new EditTextListener() {

                @Override
                public void onSave(String value) {
                    if (!TextUtils.isEmpty(value)) {
                        setKeyValue(APP_SECRET_KEY, value);
                        Toast.makeText(getActivity(), String.format(getActivity().getString(R.string.app_secret_changed_format), value), Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(getActivity(), R.string.app_secret_invalid, Toast.LENGTH_SHORT).show();
                    }
                }

                @Override
                public void onReset() {
                    String defaultAppSecret = getString(R.string.app_secret);
                    setKeyValue(APP_SECRET_KEY, defaultAppSecret);
                    Toast.makeText(getActivity(), String.format(getActivity().getString(R.string.app_secret_changed_format), defaultAppSecret), Toast.LENGTH_SHORT).show();
                }
            });

            /* Miscellaneous. */
            final String startType = MainActivity.sSharedPreferences.getString(APPCENTER_START_TYPE, StartType.APP_SECRET.toString());
            if (sInitialStartType == null) {
                sInitialStartType = startType;
            }
            initChangeableSetting(R.string.appcenter_start_type_key, startType, new Preference.OnPreferenceChangeListener() {

                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    if (newValue == null) {
                        return true;
                    }
                    String startValue = newValue.toString();
                    setKeyValue(APPCENTER_START_TYPE, startValue);
                    preference.setSummary(MainActivity.sSharedPreferences.getString(APPCENTER_START_TYPE, null));

                    /* Try to start now, this tests double calls log an error as well as valid call if previous type was none. */
                    String logUrl = MainActivity.sSharedPreferences.getString(LOG_URL_KEY, MainActivity.getLogUrl(getActivity(), startValue));
                    if (!TextUtils.isEmpty(logUrl)) {
                        AppCenter.setLogUrl(logUrl);
                    }
                    MainActivity.startAppCenter(getActivity().getApplication(), startValue);

                    /* Invite to restart app to take effect. */
                    if (sNeedRestartOnStartTypeUpdate) {
                        Toast.makeText(getActivity(), R.string.appcenter_start_type_changed, Toast.LENGTH_SHORT).show();
                    } else {
                        sInitialStartType = startValue;
                        sNeedRestartOnStartTypeUpdate = true;
                    }
                    return true;
                }
            });
            initEditText(R.string.target_id_key, R.string.target_id_title, TARGET_KEY, getString(R.string.target_id), new EditTextListener() {

                @Override
                public void onSave(String value) {
                    if (!TextUtils.isEmpty(value)) {
                        setKeyValue(TARGET_KEY, value);
                        Toast.makeText(getActivity(), String.format(getActivity().getString(R.string.target_id_changed_format), value), Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(getActivity(), R.string.target_id_invalid, Toast.LENGTH_SHORT).show();
                    }
                }

                @Override
                public void onReset() {
                    String defaultTargetId = getString(R.string.target_id);
                    setKeyValue(TARGET_KEY, defaultTargetId);
                    Toast.makeText(getActivity(), String.format(getActivity().getString(R.string.target_id_changed_format), defaultTargetId), Toast.LENGTH_SHORT).show();
                }
            });
            initEditText(R.string.user_id_key, R.string.user_id_title, USER_ID_KEY, null, new EditTextListener() {

                @Override
                public void onSave(String value) {
                    setKeyValue(USER_ID_KEY, value);
                    MainActivity.setUserId(value);
                }

                @Override
                public void onReset() {
                    setKeyValue(USER_ID_KEY, null);
                    MainActivity.setUserId(null);
                }
            });
            initClickableSetting(R.string.clear_crash_user_confirmation_key, new Preference.OnPreferenceClickListener() {

                @SuppressLint("VisibleForTests")
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    SharedPreferences appCenterPreferences = getActivity().getSharedPreferences("AppCenter", Context.MODE_PRIVATE);
                    appCenterPreferences.edit().remove(Crashes.PREF_KEY_ALWAYS_SEND).apply();
                    Toast.makeText(getActivity(), R.string.clear_crash_user_confirmation_toast, Toast.LENGTH_SHORT).show();
                    return true;
                }
            });
            String defaultLogUrl = MainActivity.getLogUrl(getActivity(), sInitialStartType);
            final String defaultLogUrlDisplay = TextUtils.isEmpty(defaultLogUrl) ? getString(R.string.log_url_set_to_production) : defaultLogUrl;
            initClickableSetting(R.string.log_url_key, MainActivity.sSharedPreferences.getString(LOG_URL_KEY, defaultLogUrlDisplay), new Preference.OnPreferenceClickListener() {

                @Override
                public boolean onPreferenceClick(final Preference preference) {
                    final EditText input = new EditText(getActivity());
                    input.setInputType(InputType.TYPE_CLASS_TEXT);
                    input.setText(MainActivity.sSharedPreferences.getString(LOG_URL_KEY, null));
                    input.setHint(R.string.log_url_set_to_production);

                    new AlertDialog.Builder(getActivity()).setTitle(R.string.log_url_title).setView(input)
                            .setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {

                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    if (Patterns.WEB_URL.matcher(input.getText().toString()).matches()) {
                                        String url = input.getText().toString();
                                        setKeyValue(LOG_URL_KEY, url);
                                        if (!TextUtils.isEmpty(url)) {
                                            AppCenter.setLogUrl(url);
                                        }
                                        toastUrlChange(url);
                                    } else if (input.getText().toString().isEmpty()) {
                                        setDefaultUrl();
                                    } else {
                                        Toast.makeText(getActivity(), R.string.log_url_invalid, Toast.LENGTH_SHORT).show();
                                    }
                                    preference.setSummary(MainActivity.sSharedPreferences.getString(LOG_URL_KEY, defaultLogUrlDisplay));
                                }
                            })
                            .setNeutralButton(R.string.reset, new DialogInterface.OnClickListener() {

                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    String defaultUrl = setDefaultUrl();
                                    if (!TextUtils.isEmpty(defaultUrl)) {
                                        AppCenter.setLogUrl(defaultUrl);
                                    }
                                    preference.setSummary(MainActivity.sSharedPreferences.getString(LOG_URL_KEY, defaultLogUrlDisplay));
                                }
                            })
                            .setNegativeButton(R.string.cancel, null)
                            .create().show();
                    return true;
                }

                private String setDefaultUrl() {
                    setKeyValue(LOG_URL_KEY, null);
                    String logUrl = MainActivity.getLogUrl(getActivity(), sInitialStartType);
                    toastUrlChange(logUrl);
                    return logUrl;
                }

                private void toastUrlChange(String url) {
                    if (TextUtils.isEmpty(url)) {
                        url = getString(R.string.log_url_production);
                    }
                    Toast.makeText(getActivity(), String.format(getActivity().getString(R.string.log_url_changed_format), url), Toast.LENGTH_SHORT).show();
                }
            });

            /* Register preference change listener. */
            getPreferenceManager().getSharedPreferences()
                    .registerOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onDestroy() {
            super.onDestroy();

            /* Unregister preference change listener. */
            getPreferenceManager().getSharedPreferences()
                    .unregisterOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onStart() {
            super.onStart();
            mDatabaseFileObserver.startWatching();
        }

        @Override
        public void onStop() {
            super.onStop();
            mDatabaseFileObserver.stopWatching();
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {

            /* Update other preferences. */
            final BaseAdapter adapter = (BaseAdapter) getPreferenceScreen().getRootAdapter();
            for (int i = 0; i < adapter.getCount(); i++) {
                Preference preference = (Preference) adapter.getItem(i);
                if (preference.getOnPreferenceChangeListener() != null && !key.equals(preference.getKey())) {
                    preference.getOnPreferenceChangeListener().onPreferenceChange(preference, null);
                }
            }
        }

        @Override
        public void onActivityResult(int requestCode, int resultCode, Intent data) {
            super.onActivityResult(requestCode, resultCode, data);
            if (requestCode == FILE_ATTACHMENT_DIALOG_ID) {
                Uri fileAttachment = resultCode == RESULT_OK && data != null ? data.getData() : null;
                if (fileAttachment != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    getActivity().getContentResolver().takePersistableUriPermission(fileAttachment, data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
                MainActivity.setFileAttachment(fileAttachment);
                Preference preference = getPreferenceManager().findPreference(getString(R.string.appcenter_crashes_file_attachment_key));
                if (preference != null) {
                    preference.setSummary(getCrashesFileAttachmentSummary());
                }
            }
        }

        private void onDatabaseFileChanged(final File dbFile) {
            Activity activity = getActivity();
            if (activity == null) {
                return;
            }
            activity.runOnUiThread(new Runnable() {

                @Override
                public void run() {
                    Activity activity = getActivity();
                    if (activity == null) {
                        return;
                    }
                    Preference preference = getPreferenceManager().findPreference(getString(R.string.storage_file_size_key));
                    if (preference != null) {
                        preference.setSummary(Formatter.formatFileSize(activity, dbFile.length()));
                    }
                }
            });
        }

        private void initEditText(int key, final int title, final String preferencesKey, final String defaultValue, final EditTextListener listener) {
            Preference preference = getPreferenceManager().findPreference(getString(key));
            if (preference == null) {
                Log.w(LOG_TAG, "Couldn't find preference for key: " + key);
                return;
            }
            preference.setSummary(getSummary(preferencesKey, defaultValue));
            preference.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {

                @Override
                public boolean onPreferenceClick(final Preference preference) {
                    final EditText input = new EditText(getActivity());
                    input.setInputType(InputType.TYPE_CLASS_TEXT);
                    input.setText(MainActivity.sSharedPreferences.getString(preferencesKey, defaultValue));

                    new AlertDialog.Builder(getActivity()).setTitle(title).setView(input)
                            .setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    listener.onSave(input.getText().toString());
                                    preference.setSummary(getSummary(preferencesKey, defaultValue));
                                }
                            })
                            .setNeutralButton(R.string.reset, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    listener.onReset();
                                    preference.setSummary(getSummary(preferencesKey, defaultValue));
                                }
                            })
                            .setNegativeButton(R.string.cancel, null)
                            .create().show();
                    return true;
                }
            });
        }

        private void initCheckBoxSetting(int key, final int enabledSummary, final int disabledSummary, final HasEnabled hasEnabled) {
            Preference preference = getPreferenceManager().findPreference(getString(key));
            if (preference == null) {
                Log.w(LOG_TAG, "Couldn't find preference for key: " + key);
                return;
            }
            preference.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {

                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    if (newValue != null) {
                        hasEnabled.setEnabled((Boolean) newValue);
                    }
                    boolean enabled = hasEnabled.isEnabled();
                    if (((CheckBoxPreference) preference).isChecked() != enabled) {
                        preference.setSummary(enabled ? enabledSummary : disabledSummary);
                        ((CheckBoxPreference) preference).setChecked(enabled);
                        return true;
                    }
                    return false;
                }
            });
            boolean enabled = hasEnabled.isEnabled();
            preference.setSummary(enabled ? enabledSummary : disabledSummary);
            ((CheckBoxPreference) preference).setChecked(enabled);
        }

        @SuppressWarnings("SameParameterValue")
        private void initClickableSetting(int key, Preference.OnPreferenceClickListener clickListener) {
            initClickableSetting(key, null, clickListener);
        }

        @SuppressWarnings("SameParameterValue")
        private void initClickableSetting(int key, String summary, Preference.OnPreferenceClickListener clickListener) {
            Preference preference = getPreferenceManager().findPreference(getString(key));
            if (preference == null) {
                Log.w(LOG_TAG, "Couldn't find preference for key: " + getString(key));
                return;
            }
            preference.setOnPreferenceClickListener(clickListener);
            if (summary != null) {
                preference.setSummary(summary);
            }
        }

        private void initChangeableSetting(@SuppressWarnings("SameParameterValue") int key, String summary, Preference.OnPreferenceChangeListener changeListener) {
            Preference preference = getPreferenceManager().findPreference(getString(key));
            if (preference == null) {
                Log.w(LOG_TAG, "Couldn't find preference for key: " + getString(key));
                return;
            }
            preference.setOnPreferenceChangeListener(changeListener);
            if (summary != null) {
                preference.setSummary(summary);
            }
        }

        private void setKeyValue(String key, String value) {
            SharedPreferences.Editor editor = MainActivity.sSharedPreferences.edit();
            if (value == null) {
                editor.remove(key);
            } else {
                editor.putString(key, value);
            }
            editor.apply();
        }

        private String getTransmissionInterval(int interval) {
            Date date = new Date(TimeUnit.SECONDS.toMillis(interval));
            DateFormat dateFormat = new SimpleDateFormat("HH:mm:ss", Locale.ENGLISH);
            dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
            String formattedInterval = dateFormat.format(date);
            long days = TimeUnit.SECONDS.toDays(interval);
            if (days > 0) {
                formattedInterval = days + "." + formattedInterval;
            }
            return interval + getString(R.string.appcenter_analytics_transmission_interval_summary_format) + formattedInterval;
        }

        private boolean isFirebaseEnabled() {
            return MainActivity.sSharedPreferences.getBoolean(FIREBASE_ENABLED_KEY, false);
        }

        private String getCrashesTextAttachmentSummary() {
            String textAttachment = MainActivity.sCrashesListener.getTextAttachment();
            if (!TextUtils.isEmpty(textAttachment)) {
                return getString(R.string.appcenter_crashes_text_attachment_summary, textAttachment.length());
            }
            return getString(R.string.appcenter_crashes_text_attachment_summary_empty);
        }

        private String getCrashesFileAttachmentSummary() {
            Uri fileAttachment = MainActivity.sCrashesListener.getFileAttachment();
            if (fileAttachment != null) {
                try {
                    String name = MainActivity.sCrashesListener.getFileAttachmentDisplayName();
                    String size = MainActivity.sCrashesListener.getFileAttachmentSize();
                    return getString(R.string.appcenter_crashes_file_attachment_summary, name, size);
                } catch (SecurityException e) {
                    Log.e(LOG_TAG, "Couldn't get info about file attachment.", e);

                    /* Reset file attachment. */
                    MainActivity.setFileAttachment(null);
                }
            }
            return getString(R.string.appcenter_crashes_file_attachment_summary_empty);
        }

        private String getSummary(final String preferencesKey, final String defaultValue) {
            String summary = MainActivity.sSharedPreferences.getString(preferencesKey, defaultValue);
            if (summary == null) {
                return getString(R.string.unset_summary);
            } else if (summary.isEmpty()) {
                return getString(R.string.empty_summary);
            }
            return summary;
        }

        private interface HasEnabled {

            boolean isEnabled();

            void setEnabled(boolean enabled);
        }

        private interface EditTextListener {

            void onSave(String value);

            void onReset();
        }
    }
}
