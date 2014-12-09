package com.nextgis.logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.nextgis.logger.R;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ListView;
import android.widget.SearchView;
import android.widget.SearchView.OnCloseListener;
import android.widget.Toast;
import android.widget.SearchView.OnQueryTextListener;

public class MarkActivity extends Activity {
	private static int marksCount = 0;
	
	MenuItem searchBox;
	ListView lvCategories;
	
	CustomArrayAdapter substringMarkNameAdapter;

	private CellEngine gsmEngine;
	private SensorEngine sensorEngine;
	
	SharedPreferences prefs;
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setContentView(R.layout.mark_activity);
		
		prefs = PreferenceManager.getDefaultSharedPreferences(this);
		
		gsmEngine = new CellEngine(this);
		sensorEngine = new SensorEngine(this);

		lvCategories = (ListView) findViewById(R.id.lv_categories);
		final Activity base = this;
		
		marksCount = prefs.getInt(C.PREF_MARKS_COUNT, 0);
		
		lvCategories.setOnItemClickListener(new OnItemClickListener() {
			@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
			@Override
			public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
				String info = getString(R.string.mark_saved);
				MainActivity.checkOrCreateDirectory(MainActivity.dataDirPath);
				
				try {
					File csvFile = new File(MainActivity.csvMarkFilePath);
					boolean isFileExist = csvFile.exists();
					PrintWriter pw = new PrintWriter(new FileOutputStream(csvFile, true));

					if (!isFileExist)
						pw.println(C.csvMarkHeader);

					String markName = substringMarkNameAdapter.getItem(position);
					String ID = substringMarkNameAdapter.getSelectedMarkNameID(markName);

					if (markName.length() == 0) {	// FIXME looks like doesn't need anymore
						markName = C.markDefaultName;
					}

					ArrayList<CellEngine.GSMInfo> gsmInfoArray = gsmEngine.getGSMInfoArray();
					String userName = prefs.getString(C.PREF_USER_NAME, "User 1");

					for (CellEngine.GSMInfo gsmInfo : gsmInfoArray) {
						String active = gsmInfo.isActive() ? "1" : gsmInfoArray.get(0).getMcc() + "-" + gsmInfoArray.get(0).getMnc() + "-"
								+ gsmInfoArray.get(0).getLac() + "-" + gsmInfoArray.get(0).getCid();
						
						pw.println(CellEngine.getItem(gsmInfo, active, ID, markName, userName));
					}

					pw.close();
					info += " (" + ++marksCount + ")";

					Intent intent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(csvFile));
			    	sendBroadcast(intent);	// update media for MTP

					// checking accelerometer data state
					if (sensorEngine.isAnySensorEnabled()) {
						csvFile = new File(MainActivity.csvMarkFilePathSensor);
						isFileExist = csvFile.exists();
						pw = new PrintWriter(new FileOutputStream(csvFile, true));

						if (!isFileExist)
							pw.println(C.csvHeaderSensor);

						pw.println(SensorEngine.getItem(sensorEngine, ID, markName, userName, gsmInfoArray.get(0).getTimeStamp()));
						pw.close();

						intent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(csvFile));
				    	sendBroadcast(intent);	// update media for MTP
					}
					
					boolean i = ((WifiManager) getSystemService(Context.WIFI_SERVICE)).isScanAlwaysAvailable();
					boolean e = ((WifiManager) getSystemService(Context.WIFI_SERVICE)).isWifiEnabled();
					((WifiManager) getSystemService(Context.WIFI_SERVICE)).startScan();
					List<ScanResult> wifis = ((WifiManager) getSystemService(Context.WIFI_SERVICE)).getScanResults();
					int ss = wifis.get(0).level;
				} catch (FileNotFoundException e) {
					info = getString(R.string.fs_error_msg);
				}
				
				Toast.makeText(base, info, Toast.LENGTH_SHORT).show();
			}
		});
		
		List<MarkName> markNames = new ArrayList<MarkName>();
		
		if (prefs.getBoolean(C.PREF_USE_CATS, false)) {
			String internalPath = getFilesDir().getAbsolutePath();
			File cats = new File(internalPath + "/" + C.categoriesFile);

			if (cats.isFile()) {
				BufferedReader in;
				String[] split;

				try {
					in = new BufferedReader(new FileReader(cats));
					String line = in.readLine();	// skip header "ID,NAME"

					while ((line = in.readLine()) != null) {
						split = line.split(",");	// FIXME dup preferences
						markNames.add(new MarkName(split[0], split[1]));
					}

					in.close();

					if (markNames.size() == 0)
						throw new ArrayIndexOutOfBoundsException();
				} catch (IOException e) {
					Toast.makeText(this, R.string.fs_error_msg, Toast.LENGTH_SHORT).show();
				} catch (ArrayIndexOutOfBoundsException e) {
					Toast.makeText(this, R.string.cat_file_error, Toast.LENGTH_SHORT).show();
				}
			}
		}
		
		substringMarkNameAdapter = new CustomArrayAdapter(this, markNames);
		lvCategories.setAdapter(substringMarkNameAdapter);
	}

	@Override
	protected void onResume() {
		super.onResume();
		
		gsmEngine.onResume();
		sensorEngine.onResume(this);
	}
	
	@Override
	protected void onPause() {
		super.onPause();
		
		gsmEngine.onPause();
		sensorEngine.onPause();

		prefs.edit().putInt(C.PREF_MARKS_COUNT, marksCount).commit();
	}
	
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.mark, menu);

		searchBox = menu.findItem(R.id.search);
		SearchView search = (SearchView) searchBox.getActionView();
		search.setQueryHint(getString(R.string.mark_editor_hint));
		search.setIconifiedByDefault(true);	// set icon inside edit text view
		search.setIconified(false);	// expand search view in action bar
		
		search.setOnCloseListener(new OnCloseListener() {
			@Override
			public boolean onClose() {
				return true;	// prevent collapse search view
			}
		});
		
		search.requestFocus();
		search.setOnQueryTextListener(new OnQueryTextListener() {
			@Override
			public boolean onQueryTextSubmit(String query) {
				return false;
			}

			@Override
			public boolean onQueryTextChange(String newText) {
				substringMarkNameAdapter.getFilter().filter(newText);
				return true;
			}
		});

		return true;
	}

	public class MarkName {
		private String ID = "";
		private String CAT = "Mark";

		public MarkName(String ID, String CAT) {
			this.ID = ID;
			this.CAT = CAT;
		}

		public String getID() {
			return ID;
		}

		public String getCAT() {
			return CAT;
		}
	}

	public class CustomArrayAdapter extends ArrayAdapter<String> implements Filterable {
		private List<MarkName> objects;
		private ArrayList<MarkName> matches = new ArrayList<MarkName>();;
		private final CustomFilter substringFilter = new CustomFilter();
		private MarkName temp;

		public CustomArrayAdapter(final Context ctx, final List<MarkName> objects) {
			super(ctx, android.R.layout.simple_list_item_1, android.R.id.text1);
			this.objects = objects;
			substringFilter.filter("");
		}

		@Override
		public Filter getFilter() {
			return substringFilter;
		}

		@Override
		public int getCount() {
			return matches.size();
		}

		@Override
		public String getItem(int position) {
			return matches.get(position).getCAT();
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			return super.getView(position, convertView, parent);
		}
		
		public String getSelectedMarkNameID(String prefix) {
			for (MarkName item : objects) {
				String CAT = substringFilter.getUpperString(item.getCAT());

				if (CAT.equals(substringFilter.getUpperString(prefix)))
					return item.getID();
			}

			return "";
		}

		private class CustomFilter extends Filter {
			@Override
			protected FilterResults performFiltering(final CharSequence prefix) {
				final FilterResults results = new FilterResults();

				if (prefix != null) {
					ArrayList<MarkName> resultList = new ArrayList<MarkName>();

					for (MarkName item : objects) {
						String CAT = getUpperString(item.getCAT());
						String substr = getUpperString(prefix.toString());

						if (CAT.contains(substr))
							resultList.add(item);
					}

					results.count = resultList.size();
					results.values = resultList;
					
					if (resultList.size() != 1 && !prefix.equals("")) {
						temp = new MarkName("", prefix.toString());
						results.count++;
//						resultList.add(temp);
					}
					else
						temp = null;
				}

				return results;
			}

			@SuppressWarnings("unchecked")
			@Override
			protected void publishResults(final CharSequence constraint, final FilterResults results) {
				matches.clear();
				
				if (results != null && results.count > 0) {
					if (temp != null)
						matches.add(temp);
					
					matches.addAll((ArrayList<MarkName>) results.values);
					notifyDataSetChanged();
				} else
					notifyDataSetInvalidated();
			}

			public String getUpperString(String str) {
				return str.toUpperCase(Locale.getDefault());
			}
		}
	}

}
