/*
 * Copyright (C) 2019 Konrad Kollnig, University of Oxford
 *
 * Tracker Control is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * Tracker Control is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Tracker Control. If not, see <http://www.gnu.org/licenses/>.
 */

package net.kollnig.missioncontrol;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.design.widget.BottomNavigationView;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.SwitchCompat;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import net.kollnig.missioncontrol.main.AppBlocklistController;
import net.kollnig.missioncontrol.main.AppsFragment;
import net.kollnig.missioncontrol.vpn.InConsumer;
import net.kollnig.missioncontrol.vpn.OutConsumer;
import net.kollnig.missioncontrol.vpn.OutFilter;

import edu.uci.calit2.antmonitor.lib.AntMonitorActivity;
import edu.uci.calit2.antmonitor.lib.logging.PacketProcessor.TrafficType;
import edu.uci.calit2.antmonitor.lib.vpn.VpnController;
import edu.uci.calit2.antmonitor.lib.vpn.VpnState;

public class MainActivity extends AppCompatActivity implements AntMonitorActivity,
		View.OnClickListener {
	private static final String TAG = MainActivity.class.getSimpleName();
	private final String APPS_FRAG_TAG = "appsFragTag";
	public static String CONSENT_PREF = "consent";
	public static String CONSENT_YES = "yes";
	public static String CONSENT_NO = "no";
	SwitchCompat mSwitchMonitoring;
	Toolbar mToolbar;
	FragmentManager fm;
	BottomNavigationView bottomNavigation;
	private int selectedPage = R.id.navigation_apps;

	/**
	 * The controller that will be used to start/stop the VPN service
	 */
	private VpnController mVpnController;

	@Override
	@SuppressLint("ClickableViewAccessibility")
	protected void onCreate (Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		// Initialise application filter
		AppBlocklistController.getInstance(this);

		mToolbar = findViewById(R.id.main_toolbar);
		setSupportActionBar(mToolbar);

		// Connect and disconnect buttons are disabled by default.
		// We update enabled state when we receive a broadcast about VPN state from the service.
		// Or when the service connection is established.
		mSwitchMonitoring = findViewById(R.id.switchMonitoring);
		updateMonitoringSwitch(false, false);

		// Use click events only, for simplicity, disable swipe
		mSwitchMonitoring.setOnClickListener(this);
		mSwitchMonitoring.setOnTouchListener(new View.OnTouchListener() {
			@Override
			public boolean onTouch (View v, MotionEvent event) {
				return event.getActionMasked() == MotionEvent.ACTION_MOVE;
			}
		});

		// Initialise VPN controller
		mVpnController = VpnController.getInstance(this);
		VpnController.setDnsCacheEnabled(true);

		// Set up the bottom bottomNavigation
		fm = getSupportFragmentManager();
		Fragment fApps = fm.findFragmentByTag(APPS_FRAG_TAG);
		if (fApps != null) {
			fm.beginTransaction().show(fApps).commit();
		} else {
			fm.beginTransaction().add(R.id.main_container, AppsFragment.newInstance(), APPS_FRAG_TAG).commit();
		}

		// Ask for consent to contact Google and other servers
		final SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
		final String consent = sharedPref.getString(CONSENT_PREF, null);

		if (consent == null) {
			AlertDialog.Builder builder = new AlertDialog.Builder(this);
			builder.setMessage(R.string.confirm_google_info)
					.setTitle(R.string.external_servers);
			builder.setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
				public void onClick (DialogInterface dialog, int id) {
					sharedPref.edit().putString(CONSENT_PREF, CONSENT_YES).apply();
					dialog.dismiss();
				}
			});
			builder.setNegativeButton(R.string.no, new DialogInterface.OnClickListener() {
				@Override
				public void onClick (DialogInterface dialog, int i) {
					sharedPref.edit().putString(CONSENT_PREF, CONSENT_NO).apply();
					dialog.dismiss();
				}
			});
			AlertDialog dialog = builder.create();
			dialog.setCancelable(false); // avoid back button
			dialog.setCanceledOnTouchOutside(false);
			dialog.show();
		}
	}

	/**
	 * Convenience method for starting the VPN service
	 */
	private void startMonitoring () {
		// Check if we are connected to the internet
		if (!mVpnController.isConnectedToInternet()) {
			Toast.makeText(MainActivity.this, R.string.no_service,
					Toast.LENGTH_LONG).show();
			updateMonitoringSwitch(true, false);
			return;
		}

		// Check if we have VPN rights from the user
		Intent intent = android.net.VpnService.prepare(MainActivity.this);
		if (intent != null) {
			// Ask user for VPN rights. If they are granted,
			// onActivityResult will be called with RESULT_OK
			startActivityForResult(intent, VpnController.REQUEST_VPN_RIGHTS);
		} else {
			// VPN rights were granted before, attempt a connection
			onActivityResult(VpnController.REQUEST_VPN_RIGHTS, RESULT_OK, null);
		}
	}

	@Override
	protected void onActivityResult (int request, int result, Intent data) {
		if (request == VpnController.REQUEST_VPN_RIGHTS) {
			// Check if the user granted us rights to VPN
			if (result == Activity.RESULT_OK) {
				// If so, we can attempt a connection

				// Prepare packet consumers (off-line packet processing)
				OutConsumer outConsumer =
						new OutConsumer(this, TrafficType.OUTGOING_PACKETS);
				InConsumer inConsumer =
						new InConsumer(this, TrafficType.INCOMING_PACKETS);
				OutFilter outFilter = new OutFilter(this);

				// Connect - triggers onVpnStateChanged
				mVpnController.connect(null, outFilter, inConsumer, outConsumer);
			} else {
				// enable the switch again so user can try again
				mSwitchMonitoring.setEnabled(true);
				mSwitchMonitoring.setChecked(false);
				Toast.makeText(getApplicationContext(), getResources().getString(R.string.vpn_rights_needed), Toast.LENGTH_SHORT).show();
			}
		}
	}

	@Override
	public boolean onCreateOptionsMenu (Menu menu) {
		getMenuInflater().inflate(R.menu.menu_main, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected (MenuItem item) {
		switch (item.getItemId()) {
			case R.id.menu_option_about:
				Uri aboutUri = Uri.parse(getString(R.string.about_url));
				Intent browserIntent = new Intent(Intent.ACTION_VIEW, aboutUri);
				startActivity(browserIntent);
				return true;
		}
		return super.onOptionsItemSelected(item);
	}

	@Override
	public void onClick (View view) {
		if (view.getId() == R.id.switchMonitoring) {
			mSwitchMonitoring.setEnabled(false);

			if (mSwitchMonitoring.isChecked()) {
				startMonitoring();
			} else {
				mVpnController.disconnect();
			}
		}
	}

	/**
	 * Convenience method for setting the switch state based on the VPN state
	 *
	 * @param notConnecting
	 * @param connected
	 */
	private void updateMonitoringSwitch (boolean notConnecting, boolean connected) {
		mSwitchMonitoring.setEnabled(notConnecting);
		mSwitchMonitoring.setChecked(connected);

		if (connected) {
			mToolbar.setSubtitle(getString(R.string.notification_detail_text_vpnservice_connected));
		} else {
			mToolbar.setSubtitle(getString(R.string.notification_detail_text_vpnservice_disconnected));
		}
	}

	/**
	 * Updates this {@code Activity} to reflect a change in the state of the VPN connection.
	 * Receiving this state change means we successfully bounded to the VPN service.
	 *
	 * @param vpnState The new state of the VPN connection.
	 */
	@Override
	public void onVpnStateChanged (VpnState vpnState) {
		Log.d(TAG, "Received new vpn state: " + vpnState);
		boolean isConnecting = vpnState == VpnState.CONNECTING;
		boolean isConnected = vpnState == VpnState.CONNECTED;

		// Receiving an update means we are bound, and we can control the VPN service, so now we can
		// allow the user to press the "connect" button (if we are not already connecting/connected)
		updateMonitoringSwitch(!isConnecting, isConnected);
	}

	@Override
	protected void onStart () {
		super.onStart();

		// Bind to the service so we can receive VPN status updates (see onVpnStateChanged)
		mVpnController.bind();
		// Note that we are not yet bound. We will be bound when we receive our first update about
		// the VPN state in onVpnStateChanged(), at which point we can enable the connect button
	}

	@Override
	protected void onStop () {
		super.onStop();

		// Unbind from the service as we no longer need to receive VPN status updates,
		// since we don't have to change the button to enabled/disabled, etc.
		mVpnController.unbind();
	}
}