package com.rafali.flickruploader.ui.activity;

import java.util.Arrays;
import java.util.List;

import org.androidannotations.api.BackgroundExecutor;
import org.slf4j.LoggerFactory;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceClickListener;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.text.Html;
import android.view.MenuItem;
import android.widget.EditText;

import com.google.common.base.Joiner;
import com.rafali.flickruploader.FlickrUploader;
import com.rafali.flickruploader.tool.Utils;
import com.rafali.flickruploader2.R;

@SuppressWarnings("deprecation")
public class PreferencesAdvancedActivity extends PreferenceActivity implements OnSharedPreferenceChangeListener {

	static final org.slf4j.Logger LOG = LoggerFactory.getLogger(PreferencesAdvancedActivity.class);

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		getActionBar().setDisplayHomeAsUpEnabled(true);
		addPreferencesFromResource(R.xml.preferences_advanced);
		PreferenceManager.getDefaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(this);
		findPreference("clear_logs").setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
			@Override
			public boolean onPreferenceClick(Preference preference) {
				Utils.showConfirmCancel(PreferencesAdvancedActivity.this, "Delete logs", "Do you confirm deleting all log files?", new Utils.Callback<Boolean>() {
					@Override
					public void onResult(Boolean result) {
						if (result) {
							BackgroundExecutor.execute(new Runnable() {
								@Override
								public void run() {
									FlickrUploader.deleteAllLogs();
									render();
								}
							});
						}
					}
				});
				return false;
			}
		});

		findPreference("upload_description").setOnPreferenceClickListener(new PremiumOnclick("upload_description"));
		findPreference("custom_tags").setOnPreferenceClickListener(new PremiumOnclick("custom_tags"));
		findPreference("check_premium_status").setOnPreferenceClickListener(new OnPreferenceClickListener() {
			@Override
			public boolean onPreferenceClick(Preference preference) {
				runOnUiThread(new Runnable() {
					@Override
					public void run() {
						findPreference("check_premium_status").setSummary("Checking from server…");
					}
				});
				BackgroundExecutor.execute(new Runnable() {
					@Override
					public void run() {
						Utils.checkPremium(true, new Utils.Callback<Boolean>() {
							@Override
							public void onResult(final Boolean result) {
								runOnUiThread(new Runnable() {
									@Override
									public void run() {
										String message;
										if (result) {
											message = "PRO status confirmed!";
										} else {
											if (Utils.customSku == null) {
												message = "No PRO info found for your device email: " + Joiner.on(", ").join(Utils.getAccountEmails());
											} else {
												message = "Your coupon has been applied to the device!";
											}
										}
										findPreference("check_premium_status").setSummary(message);
									}
								});
							}
						});
					}
				});
				return false;
			}
		});
		render();
	}

	class PremiumOnclick implements OnPreferenceClickListener {

		private String prefKey;

		public PremiumOnclick(String prefKey) {
			this.prefKey = prefKey;
		}

		@Override
		public boolean onPreferenceClick(Preference preference) {
			AlertDialog.Builder alert = new AlertDialog.Builder(PreferencesAdvancedActivity.this);
			alert.setTitle(findPreference(prefKey).getTitle());
			if (Utils.isPremium()) {
				// Set an EditText view to get user input
				final EditText input = new EditText(PreferencesAdvancedActivity.this);
				if (prefKey.equals("upload_description")) {
					input.setText(Utils.getUploadDescription());
				} else {
					input.setText(Utils.getStringProperty(prefKey));
				}
				alert.setView(input);

				alert.setPositiveButton("OK", new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int whichButton) {
						String value = input.getText().toString();
						LOG.debug("value : " + value);
						Utils.setStringProperty(prefKey, value);
						render();
					}
				});

				alert.setNegativeButton("Cancel", null);
			} else {
				alert.setMessage("A PRO account is needed to customize this branding feature");
				alert.setPositiveButton("Buy PRO Now", new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						Utils.startGooglePayment(PreferencesAdvancedActivity.this, new Utils.Callback<Boolean>() {
							@Override
							public void onResult(Boolean result) {
								render();
							}
						});
					}
				});
				alert.setNegativeButton("Later", null);
			}

			alert.show();
			return false;
		}
	}

	private void render() {
		BackgroundExecutor.execute(new Runnable() {
			@Override
			public void run() {
				final long size = FlickrUploader.getLogSize();
				final List<String> autoupload_delay_values = Arrays.asList(getResources().getStringArray(R.array.autoupload_delay_values));
				final String[] autoupload_delay_entries = getResources().getStringArray(R.array.autoupload_delay_entries);
				final String autoupload_delay_value = Utils.getStringProperty("autoupload_delay", autoupload_delay_values.get(0));
				runOnUiThread(new Runnable() {
					@Override
					public void run() {
						findPreference("clear_logs").setSummary("Files size: " + Utils.formatFileSize(size));
						String autoupload_delay = autoupload_delay_entries[autoupload_delay_values.indexOf(autoupload_delay_value)];
						if (autoupload_delay.equalsIgnoreCase("custom")) {
							findPreference("autoupload_delay").setSummary(autoupload_delay);
						} else {
							findPreference("autoupload_delay").setSummary(autoupload_delay);
						}
						findPreference("upload_description").setSummary(Html.fromHtml(Utils.getUploadDescription()));
						findPreference("custom_tags").setSummary(Utils.getStringProperty("custom_tags"));
					}
				});
			}
		});
	}

	@Override
	public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
		if ("autoupload_delay".equals(key) && sp.getString(key, "").equalsIgnoreCase("custom")) {
			LOG.debug("custom");
		}
		render();
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			finish();
		}
		return super.onOptionsItemSelected(item);
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(this);
	}
}
