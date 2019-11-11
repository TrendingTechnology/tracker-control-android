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
package net.kollnig.missioncontrol.main;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import net.kollnig.missioncontrol.data.App;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AppBlocklistController extends BlocklistController {
	private static final String SHARED_PREFS_BLOCKLIST_APPS_KEY = "APPS_BLOCKLIST_APPS_KEY";
	private static AppBlocklistController instance;
	final PackageManager pm;
	private final String ownPackageName;
	public Set<String> systemApps = new HashSet<>();

	private AppBlocklistController (Context c) {
		super(c);

		pm = c.getPackageManager();
		ownPackageName = c.getApplicationContext().getPackageName();

		load();
	}

	/**
	 * Singleton getter.
	 *
	 * @param c context used to access shared preferences from.
	 * @return The current instance of the AppBlocklistController, if none, a new instance is created.
	 */
	public static AppBlocklistController getInstance (Context c) {
		if (instance == null) {
			// Create the instance
			instance = new AppBlocklistController(c);
		}
		// Return the instance
		return instance;
	}

	public List<App> load () {
		boolean initialisation = (systemApps.size() == 0);
		List<App> installedApps = new ArrayList<>();

		List<ApplicationInfo> appInfos = pm.getInstalledApplications(PackageManager.GET_META_DATA);
		for (ApplicationInfo appInfo : appInfos) {
			if (//!BuildConfig.DEBUG &&
					(isSystemPackage(appInfo) || appInfo.packageName.equals(ownPackageName))) {
				if (initialisation) {
					systemApps.add(appInfo.packageName);
				}
			} else {
				App app = new App();
				app.id = appInfo.packageName;
				app.icon = appInfo.loadIcon(pm);
				app.name = appInfo.loadLabel(pm).toString();

				installedApps.add(app);
			}
		}

		return installedApps;
	}

	public boolean blockedTracker (String appId, String tracker) {
		Set<String> trackers = this.getSubset(appId);
		if (trackers == null) {
			return false;
		}

		return !trackers.contains(tracker); // negate since it's a whitelist
	}

	public String getPrefKey () {
		return SHARED_PREFS_BLOCKLIST_APPS_KEY;
	}

	/**
	 * Check if the {@code FLAG_SYSTEM} or {@code FLAG_UPDATED_SYSTEM_APP} is set for the application.
	 *
	 * @param applicationInfo The application to check.
	 * @return true if the {@code applicationInfo} belongs to a system or updated system application.
	 */
	public boolean isSystemPackage (ApplicationInfo applicationInfo) {
		return (((applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0)
				|| (applicationInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0);
	}
}