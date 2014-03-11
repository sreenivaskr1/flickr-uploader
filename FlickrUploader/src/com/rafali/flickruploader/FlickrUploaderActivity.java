package com.rafali.flickruploader;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.LoggerFactory;

import uk.co.senab.bitmapcache.CacheableBitmapDrawable;
import uk.co.senab.bitmapcache.CacheableImageView;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.app.ShareCompat;
import android.util.SparseArray;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.ShareActionProvider;
import android.widget.TextView;
import android.widget.Toast;

import com.google.analytics.tracking.android.EasyTracker;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.collect.Ordering;
import com.googlecode.androidannotations.annotations.AfterViews;
import com.googlecode.androidannotations.annotations.Background;
import com.googlecode.androidannotations.annotations.EActivity;
import com.googlecode.androidannotations.annotations.UiThread;
import com.googlecode.androidannotations.annotations.ViewById;
import com.googlecode.androidannotations.api.BackgroundExecutor;
import com.rafali.common.STR;
import com.rafali.common.ToolString;
import com.rafali.flickruploader.FlickrApi.PRIVACY;
import com.rafali.flickruploader.Utils.Callback;
import com.rafali.flickruploader.Utils.MediaType;
import com.rafali.flickruploader.billing.IabHelper;
import com.rafali.flickruploader2.R;
import com.tonicartos.widget.stickygridheaders.StickyGridHeadersBaseAdapter;
import com.tonicartos.widget.stickygridheaders.StickyGridHeadersGridView;

@EActivity(R.layout.flickr_uploader_slider_activity)
public class FlickrUploaderActivity extends Activity {

	private static final int MAX_LINK_SHARE = 5;

	static final org.slf4j.Logger LOG = LoggerFactory.getLogger(FlickrUploaderActivity.class);

	ActionMode mMode;

	private static FlickrUploaderActivity instance;
	private FlickrUploaderActivity activity;

	@Override
	public void onCreate(Bundle bundle) {
		activity = this;
		super.onCreate(bundle);
		getActionBar().setTitle("Flickr Uploader");
		LOG.debug("onCreate " + bundle);
		UploadService.wake();
		if (Utils.getStringProperty(STR.accessToken) == null) {
			Utils.confirmSignIn(activity);
		}
		load();
		if (instance != null)
			instance.finish();
		instance = activity;
		Utils.checkPremium(false, new Utils.Callback<Boolean>() {
			@Override
			public void onResult(Boolean result) {
				renderPremium();
			}
		});
		handleIntent(getIntent());
	}

	@Background
	void handleIntent(Intent intent) {
		if (intent != null) {
			String action = intent.getAction();
			String type = intent.getType();
			if (Intent.ACTION_SEND.equals(action) && type != null) {
				if (type.startsWith("image/") || type.startsWith("video/")) {
					Uri imageUri = (Uri) intent.getParcelableExtra(Intent.EXTRA_STREAM);
					List<Media> loadImages = Utils.loadImages(imageUri.toString(), type.startsWith("image/") ? MediaType.photo : MediaType.video, 1);
					LOG.debug("imageUri : " + imageUri + ", loadImages : " + loadImages);
					if (!loadImages.isEmpty()) {
						confirmUpload(loadImages, false);
					} else {
						toast("No media found for " + imageUri);
					}
				}
			} else if (Intent.ACTION_SEND_MULTIPLE.equals(action) && type != null) {
				if (type.startsWith("image/") || type.startsWith("video/")) {
					ArrayList<Uri> imageUris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
					List<Media> loadImages = new ArrayList<Media>();
					if (imageUris != null) {
						for (Uri imageUri : imageUris) {
							List<Media> tmpImages = Utils.loadImages(imageUri.toString(), type.startsWith("image/") ? MediaType.photo : MediaType.video, 1);
							LOG.debug("imageUri : " + imageUri + ", loadImages : " + loadImages);
							loadImages.addAll(tmpImages);
						}
						if (!loadImages.isEmpty()) {
							confirmUpload(loadImages, false);
						} else {
							toast("No media found");
						}
					}
				}
			}
		}
	}

	@UiThread
	void toast(String message) {
		Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
	}

	SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

	@Background
	void load() {
		medias = Utils.loadImages(null, MediaType.photo);
		photos = new ArrayList<Media>(medias);
		videos = Utils.loadImages(null, MediaType.video);

		if (getSort() == SORT.old_to_recent) {
			Collections.reverse(photos);
			Collections.reverse(videos);
		}

		headers = new ArrayList<Header>();
		headerMap = new HashMap<Media, Header>();
		headerIds = new HashMap<String, Header>();

		for (Media media : photos) {
			String id = format.format(new Date(media.date));
			Header header = headerIds.get(id);
			if (header == null) {
				header = new Header(id, id);
				headerIds.put(id, header);
				headers.add(header);
			} else {
				header.count++;
			}
			headerMap.put(media, header);
		}

		List<Media> all = new ArrayList<Media>(photos);
		all.addAll(videos);
		folders = Utils.getFolders(all);
		for (Folder folder : folders) {
			foldersMap.put(folder.images.get(0), folder);
		}
		init();
	}

	Map<Media, Header> headerMap;
	List<Header> headers;
	Map<String, Header> headerIds;

	class Header {
		String id;
		String title;
		int count = 1;
		boolean collapsed = false;
		boolean selected = false;

		Header(String id, String title) {
			this.id = id;
			this.title = title;
		}

		@Override
		public String toString() {
			return id + ":" + count + ":" + title + ":collapsed=" + collapsed + ":selected=" + selected;
		}
	}

	enum SORT {
		recent_to_old, old_to_recent
	}

	SORT getSort() {
		long sort_type = Utils.getLongProperty("sort_type");
		if (sort_type == 1) {
			return SORT.old_to_recent;
		} else {
			return SORT.recent_to_old;
		}
	}

	@Override
	protected void onStart() {
		super.onStart();
		EasyTracker.getInstance().activityStart(activity);
	}

	@Override
	protected void onStop() {
		Mixpanel.flush();
		super.onStop();
		EasyTracker.getInstance().activityStop(activity);
	}

	@Override
	protected void onNewIntent(Intent intent) {
		super.onNewIntent(intent);
		renderMenu();
		LOG.debug("onNewIntent : " + intent);
	}

	@Override
	public void onConfigurationChanged(Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
		LOG.debug("onConfigurationChanged");
		refresh(false);
	}

	public static FlickrUploaderActivity getInstance() {
		return instance;
	}

	void testNotification() {
		BackgroundExecutor.execute(new Runnable() {
			Media image = photos.get(0);

			@Override
			public void run() {
				for (int i = 0; i <= 100; i++) {
					Notifications.notify(i, image, 1, 1);
					try {
						Thread.sleep(200);
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
			}
		});
	}

	@Override
	protected void onDestroy() {
		LOG.debug("onDestroy");
		super.onDestroy();
		if (instance == this)
			instance = null;
		destroyed = true;
		UploadService.unregister(drawerHandleView);
		UploadService.unregister(drawerContentView);
	}

	boolean destroyed = false;

	PhotoAdapter photoAdapter;

	@UiThread
	void init() {
		if (mainTabView == null) {
			// for (int i = 0; i < TAB.values().length; i++) {
			{
				mainTabView = (com.tonicartos.widget.stickygridheaders.StickyGridHeadersGridView) View.inflate(activity, R.layout.photo_grid, null);
				photoAdapter = new PhotoAdapter();
				mainTabView.setAdapter(photoAdapter);
				mainTabView.setOnItemClickListener(onItemClickListener);
			}
			RelativeLayout relativeLayout = (RelativeLayout) findViewById(R.id.container);
			relativeLayout.addView(mainTabView, 0);
		} else {
			photoAdapter.notifyDataSetChanged();
		}
	}

	@ViewById(R.id.drawer_handle)
	DrawerHandleView drawerHandleView;

	@ViewById(R.id.drawer_content)
	DrawerContentView drawerContentView;

	@ViewById(R.id.slidingDrawer)
	SlidingDrawer slidingDrawer;

	@AfterViews
	void afterViews() {
		UploadService.register(drawerHandleView);
		UploadService.register(drawerContentView);
		renderPremium();
	}

	OnItemClickListener onItemClickListener = new OnItemClickListener() {
		@Override
		public void onItemClick(AdapterView<?> arg0, View convertView, int arg2, long arg3) {
			Media media = (Media) convertView.getTag();
			Header header = headerMap.get(media);
			if (selectedMedia.contains(media)) {
				convertView.findViewById(R.id.check_image).setVisibility(View.GONE);
				selectedMedia.remove(media);
				if (header.selected) {
					header.selected = false;
				}
			} else {
				convertView.findViewById(R.id.check_image).setVisibility(View.VISIBLE);
				selectedMedia.add(media);
				if (!header.selected) {
					List<Media> headerMedias = new ArrayList<Media>();
					Iterator<Entry<Media, Header>> it = headerMap.entrySet().iterator();
					while (it.hasNext()) {
						Map.Entry<Media, Header> entry = it.next();
						if (entry.getValue() == header) {
							headerMedias.add(entry.getKey());
						}
					}
					if (selectedMedia.containsAll(headerMedias)) {
						header.selected = true;
					}
				}
			}
			renderSelection();
		}
	};

	void updateCount() {
		int size = 0;
		size = selectedMedia.size();
		if (size > 0) {
			if (mMode == null)
				mMode = startActionMode(mCallback);
			if (mMode != null)
				mMode.setTitle(size + "");
		} else if (size == 0 && mMode != null) {
			mMode.finish();
		}
	}

	private MenuItem shareItem;
	private ShareActionProvider shareActionProvider;

	@UiThread(delay = 200)
	void setShareIntent() {
		if (!selectedMedia.isEmpty() && shareItem != null) {
			Map<String, String> shortUrls = new LinkedHashMap<String, String>();
			for (Media image : selectedMedia) {
				String photoId = FlickrApi.getPhotoId(image);
				if (photoId != null) {
					shortUrls.put(photoId, FlickrApi.getShortUrl(photoId));
				}
				if (shortUrls.size() >= MAX_LINK_SHARE)
					break;
			}
			boolean uploadedPhotosSelected = !shortUrls.isEmpty();
			shareItem.setVisible(uploadedPhotosSelected);
			privacyItem.setVisible(uploadedPhotosSelected);
			if (uploadedPhotosSelected) {
				Intent shareIntent = ShareCompat.IntentBuilder.from(activity).setType("text/*").setText(Joiner.on(" ").join(shortUrls.values())).getIntent();
				shareIntent.putExtra("photoIds", Joiner.on(",").join(shortUrls.keySet()));
				shareActionProvider.setShareIntent(shareIntent);
			}
		}
	}

	private MenuItem privacyItem;
	final ActionMode.Callback mCallback = new ActionMode.Callback() {

		/**
		 * Invoked whenever the action mode is shown. This is invoked
		 * immediately after onCreateActionMode
		 */
		@Override
		public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
			return false;
		}

		/** Called when user exits action mode */
		@Override
		public void onDestroyActionMode(ActionMode mode) {
			mMode = null;
			selectedMedia.clear();
			for (Header header : headers) {
				header.selected = false;
			}
			renderSelection();
		}

		/**
		 * This is called when the action mode is created. This is called by
		 * startActionMode()
		 */
		@Override
		public boolean onCreateActionMode(ActionMode mode, Menu menu) {
			getMenuInflater().inflate(R.menu.context_menu, menu);
			shareItem = menu.findItem(R.id.menu_item_share);
			// shareItem.setVisible(!isFolderTab());
			privacyItem = menu.findItem(R.id.menu_item_privacy);

			shareActionProvider = (ShareActionProvider) shareItem.getActionProvider();
			shareActionProvider.setOnShareTargetSelectedListener(new ShareActionProvider.OnShareTargetSelectedListener() {
				@Override
				public boolean onShareTargetSelected(ShareActionProvider shareActionProvider, Intent intent) {
					LOG.debug("intent : " + intent);
					if (intent.hasExtra("photoIds")) {
						List<String> privatePhotoIds = new ArrayList<String>();
						String[] photoIds = intent.getStringExtra("photoIds").split(",");
						for (String photoId : photoIds) {
							if (FlickrApi.getPrivacy(photoId) != PRIVACY.PUBLIC) {
								privatePhotoIds.add(photoId);
							}
						}
						if (privatePhotoIds.size() > 0) {
							FlickrApi.setPrivacy(PRIVACY.PUBLIC, privatePhotoIds);
						}
						Mixpanel.increment("photos_shared", photoIds.length);
					}
					return false;
				}
			});
			return true;
		}

		/** This is called when an item in the context menu is selected */
		@Override
		public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
			Mixpanel.track("UI actionMode " + getItemName(item));
			switch (item.getItemId()) {
			case R.id.menu_item_select_all: {
				EasyTracker.getTracker().sendEvent("ui", "click", "menu_item_select_all", 0L);
				selectedMedia.clear();
				selectedMedia.addAll(photos);

				renderSelection();
			}
				break;
			case R.id.menu_item_privacy: {
				EasyTracker.getTracker().sendEvent("ui", "click", "menu_item_privacy", 0L);
				Collection<Media> selectedImages;
				selectedImages = selectedMedia;
				PRIVACY privacy = null;
				for (Media image : selectedImages) {
					if (privacy == null) {
						privacy = FlickrApi.getPrivacy(image);
					} else {
						if (privacy != FlickrApi.getPrivacy(image)) {
							privacy = null;
							break;
						}
					}
				}
				Utils.dialogPrivacy(activity, privacy, new Utils.Callback<FlickrApi.PRIVACY>() {
					@Override
					public void onResult(PRIVACY result) {
						if (result != null) {
							List<String> photoIds = new ArrayList<String>();
							for (Media image : selectedMedia) {
								String photoId = FlickrApi.getPhotoId(image);
								if (photoId != null && FlickrApi.getPrivacy(photoId) != result) {
									photoIds.add(photoId);
								}
							}
							if (!photoIds.isEmpty()) {
								FlickrApi.setPrivacy(result, photoIds);
							}
						}
					}
				});
			}
				break;
			case R.id.menu_item_upload: {
				EasyTracker.getTracker().sendEvent("ui", "click", "menu_item_upload", 0L);
				final List<Media> selection = new ArrayList<Media>(selectedMedia);
				if (FlickrApi.isAuthentified()) {
					// foldersMap.clear();
					BackgroundExecutor.execute(new Runnable() {
						@Override
						public void run() {
							confirmUpload(selection, false);
						}
					});
				} else {
					// Notifications.notify(40, selection.get(0), 1, 1);
					Utils.confirmSignIn(activity);
				}
			}
				break;
			case R.id.menu_item_dequeue: {
				EasyTracker.getTracker().sendEvent("ui", "click", "menu_item_dequeue", 0L);
				final List<Media> selection = new ArrayList<Media>(selectedMedia);
				BackgroundExecutor.execute(new Runnable() {
					@Override
					public void run() {
						// if (isFolderTab()) {
						// for (Media image : selection) {
						// Folder folder = foldersMap.get(image);
						// UploadService.dequeue(folder.images);
						// }
						// } else {
						UploadService.dequeue(selection);
						// }
						refresh(false);
					}
				});
				mMode.finish();
			}
				break;

			}
			return false;
		}

	};

	@UiThread
	void confirmUpload(final List<Media> selection, boolean isFolderTab) {
		if (isFolderTab) {
			new AlertDialog.Builder(activity).setTitle("Upload to").setPositiveButton(null, null).setNegativeButton(null, null).setCancelable(true)
					.setItems(new String[] { "Default set (" + STR.instantUpload + ")", "One set per folder", "New set...", "Existing set..." }, new DialogInterface.OnClickListener() {
						@Override
						public void onClick(DialogInterface dialog, int which) {
							switch (which) {
							case 0:
								for (Media image : selection) {
									Folder folder = foldersMap.get(image);
									enqueue(folder.images, STR.instantUpload);
								}
								clearSelection();
								break;
							case 1: {
								List<Folder> folders = new ArrayList<Folder>();
								for (Media image : selection) {
									Folder folder = foldersMap.get(image);
									folders.add(folder);
								}
								createOneSetPerFolder(folders);
							}
								break;
							case 2: {
								List<Folder> folders = new ArrayList<Folder>();
								for (Media image : selection) {
									Folder folder = foldersMap.get(image);
									folders.add(folder);
								}
								if (folders.size() == 1) {
									showNewSetDialog(folders.get(0), selection);
								} else {
									showNewSetDialog(null, selection);
								}
							}
								break;
							case 3:
								showExistingSetDialog(selection);
								break;
							default:
								break;
							}
							LOG.debug("which : " + which);
						}

					}).show();
		} else {
			new AlertDialog.Builder(activity).setTitle("Upload to").setPositiveButton(null, null).setNegativeButton(null, null).setCancelable(true)
					.setItems(new String[] { "Default set (" + STR.instantUpload + ")", "New set...", "Existing set..." }, new DialogInterface.OnClickListener() {
						@Override
						public void onClick(DialogInterface dialog, int which) {
							switch (which) {
							case 0:
								enqueue(selection, STR.instantUpload);
								clearSelection();
								break;
							case 1:
								showNewSetDialog(null, selection);
								break;
							case 2:
								showExistingSetDialog(selection);
								break;

							default:
								break;
							}
							LOG.debug("which : " + which);
						}
					}).show();
		}
	}

	@UiThread
	void clearSelection() {
		if (mMode != null)
			mMode.finish();
	}

	@UiThread
	void showExistingSetDialog(final List<Media> selection) {
		showExistingSetDialog(activity, new Callback<String[]>() {
			@Override
			public void onResult(String[] result) {
				String photoSetTitle = result[1];
				// if (isFolderTab()) {
				// for (Media image : selection) {
				// Folder folder = foldersMap.get(image);
				// enqueue(folder.images, photoSetTitle);
				// }
				// } else {
				enqueue(selection, photoSetTitle);
				// }
				clearSelection();
			}
		}, null);
	}

	static void showExistingSetDialog(final Activity activity, final Callback<String[]> callback, final Map<String, String> cachedPhotosets) {
		final ProgressDialog dialog = ProgressDialog.show(activity, "", "Loading photosets", true);
		BackgroundExecutor.execute(new Runnable() {
			@Override
			public void run() {
				final Map<String, String> photosets = cachedPhotosets == null ? FlickrApi.getPhotoSets(true) : cachedPhotosets;
				activity.runOnUiThread(new Runnable() {
					@Override
					public void run() {
						dialog.cancel();
						if (photosets.isEmpty()) {
							Toast.makeText(activity, "No photoset found", Toast.LENGTH_LONG).show();
						} else {
							AlertDialog.Builder builder = new AlertDialog.Builder(activity);
							final List<String> photosetTitles = new ArrayList<String>();
							final List<String> photosetIds = new ArrayList<String>(photosets.keySet());
							Map<String, String> lowerCasePhotosets = new HashMap<String, String>();
							Iterator<String> it = photosetIds.iterator();
							while (it.hasNext()) {
								String photosetId = it.next();
								String photoSetTitle = photosets.get(photosetId);
								if (ToolString.isNotBlank(photoSetTitle)) {
									lowerCasePhotosets.put(photosetId, photoSetTitle.toLowerCase(Locale.US));
								} else {
									it.remove();
								}
							}
							Ordering<String> valueComparator = Ordering.natural().onResultOf(Functions.forMap(lowerCasePhotosets));
							Collections.sort(photosetIds, valueComparator);
							for (String photosetId : photosetIds) {
								photosetTitles.add(photosets.get(photosetId));
							}
							String[] photosetTitlesArray = photosetTitles.toArray(new String[photosetTitles.size()]);
							builder.setItems(photosetTitlesArray, new OnClickListener() {
								@Override
								public void onClick(DialogInterface dialog, int which) {
									LOG.debug("selected : " + photosetIds.get(which) + " - " + photosetTitles.get(which));
									String photoSetId = photosetIds.get(which);
									String photoSetTitle = photosetTitles.get(which);
									callback.onResult(new String[] { photoSetId, photoSetTitle });
								}
							});
							builder.show();
						}
					}
				});
			}
		});
	}

	@UiThread
	void showNewSetDialog(final Folder folder, final List<Media> selection) {
		showNewSetDialog(activity, folder == null ? null : folder.name, new Callback<String>() {
			@Override
			public void onResult(final String value) {
				if (ToolString.isBlank(value)) {
					toast("Title cannot be empty");
					showNewSetDialog(folder, selection);
				} else {
					BackgroundExecutor.execute(new Runnable() {
						@Override
						public void run() {
							enqueue(selection, value);
							clearSelection();
						}
					});
				}
			}
		});
	}

	ProgressDialog progressDialog;

	@UiThread
	void showLoading(String title, String message) {
		if (progressDialog == null) {
			progressDialog = new ProgressDialog(activity);
		}
		progressDialog.setTitle(title);
		progressDialog.setMessage(message);
		progressDialog.setIndeterminate(true);
		progressDialog.show();
	}

	@UiThread
	void hideLoading() {
		if (progressDialog != null) {
			progressDialog.dismiss();
		}
	}

	@Background
	void createOneSetPerFolder(List<Folder> folders) {
		for (Folder folder : folders) {
			try {
				enqueue(folder.images, folder.name);
				clearSelection();
			} catch (Throwable e) {
				LOG.error(ToolString.stack2string(e));
			}
		}
	}

	static void showNewSetDialog(final Activity activity, final String folderTitle, final Callback<String> callback) {
		AlertDialog.Builder alert = new AlertDialog.Builder(activity);

		alert.setTitle("Photo Set Title");

		// Set an EditText view to get user input
		final EditText input = new EditText(activity);
		input.setText(folderTitle);
		alert.setView(input);

		alert.setPositiveButton("OK", new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int whichButton) {
				String value = input.getText().toString();
				LOG.debug("value : " + value);
				callback.onResult(value);
			}

		});

		alert.setNegativeButton("Cancel", null);

		alert.show();
	}

	@UiThread
	void enqueue(Collection<Media> images, String photoSetTitle) {
		int enqueued = UploadService.enqueue(false, images, photoSetTitle);
		if (slidingDrawer != null && enqueued > 0) {
			slidingDrawer.animateOpen();
			drawerContentView.setCurrentTab(DrawerContentView.TAB_QUEUED_INDEX);
		}
	}

	@UiThread
	void renderSelection() {
		int childCount = mainTabView.getChildCount();
		for (int i = 0; i < childCount; i++) {
			View view = mainTabView.getChildAt(i);
			View check_image = view.findViewById(R.id.check_image);
			if (check_image == null) {
				// if (view.getTag() instanceof View) {
				// View headerView = (View) view.getTag();
				// renderHeaderSelection(headerView);
				// }
			} else {
				check_image.setVisibility(selectedMedia.contains(view.getTag()) ? View.VISIBLE : View.GONE);
			}
		}
		for (View headerView : attachedHeaderViews) {
			renderHeaderSelection(headerView);
		}
		// for (int i = 0; i < headers.size(); i++) {
		// renderHeaderSelection(mainTabView.getHeaderAt(i));
		// }
		// renderHeaderSelection(mainTabView.getStickiedHeader());
		// PhotoAdapter adapter = (PhotoAdapter) mainTabView.getAdapter();
		// adapter.notifyDataSetChanged();
		mainTabView.requestLayout();
		updateCount();
	}

	void renderHeaderSelection(View headerView) {
		if (headerView != null && headerView.getTag() instanceof Header) {
			Header header = (Header) headerView.getTag();
			// LOG.debug("rendering : " + header + " on " + headerView);
			TextView count = (TextView) headerView.getTag(R.id.count);
			count.setCompoundDrawablesWithIntrinsicBounds(0, 0, header.selected ? R.drawable.checkbox_on : R.drawable.checkbox_off, 0);
			count.setTextColor(getResources().getColor(header.selected ? R.color.litegray : R.color.gray));
			// checkbox.setText("" + header.selected);
		}
	}

	Set<Media> selectedMedia = new HashSet<Media>();

	List<Media> medias;
	List<Media> photos;
	List<Media> videos;
	List<Folder> folders;
	Map<Media, Folder> foldersMap = new HashMap<Media, Folder>();

	class PhotoAdapter extends BaseAdapter implements StickyGridHeadersBaseAdapter {

		public PhotoAdapter() {
		}

		@Override
		public int getCount() {
			return photos.size();
		}

		@Override
		public Object getItem(int position) {
			return photos.get(position);
		}

		@Override
		public long getItemId(int arg0) {
			return arg0;
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			Object item = getItem(position);
			if (convertView == null) {
				convertView = getLayoutInflater().inflate(R.layout.photo_grid_thumb, parent, false);
				convertView.setLayoutParams(new GridView.LayoutParams(GridView.LayoutParams.MATCH_PARENT, mainTabView.getColumnWidth() * 3 / 4));
				convertView.setTag(R.id.check_image, convertView.findViewById(R.id.check_image));
				convertView.setTag(R.id.uploading, convertView.findViewById(R.id.uploading));
				convertView.setTag(R.id.image_view, convertView.findViewById(R.id.image_view));
				convertView.setTag(R.id.uploaded, convertView.findViewById(R.id.uploaded));
			}
			final Media image = (Media) item;
			if (convertView.getTag() != image) {
				convertView.setTag(image);
				renderImageView(convertView);
			}
			return convertView;
		}

		@Override
		public View getHeaderView(int position, View convertView, ViewGroup arg2) {
			final TextView title;
			final TextView count;
			if (convertView == null) {
				convertView = View.inflate(activity, R.layout.photo_grid_header, null);
				convertView.findViewById(R.id.count).setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						Header header = (Header) v.getTag();
						header.selected = !header.selected;
						Iterator<Entry<Media, Header>> it = headerMap.entrySet().iterator();
						while (it.hasNext()) {
							Map.Entry<Media, Header> entry = it.next();
							if (entry.getValue() == header) {
								if (header.selected) {
									selectedMedia.add(entry.getKey());
								} else {
									selectedMedia.remove(entry.getKey());
								}
							}
						}
						renderSelection();
					}
				});
				title = (TextView) convertView.findViewById(R.id.title);
				convertView.setTag(R.id.title, title);
				count = (TextView) convertView.findViewById(R.id.count);
				convertView.setTag(R.id.count, count);
				convertView.findViewById(R.id.expand).setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						final Header header = (Header) title.getTag();
						header.collapsed = !header.collapsed;
						photos = new ArrayList<Media>(medias);
						Iterator<Media> it = photos.iterator();
						while (it.hasNext()) {
							Media media = it.next();
							if (headerMap.get(media).collapsed) {
								it.remove();
							}
						}
						photoAdapter.notifyDataSetChanged();
						
						if (header.collapsed) {
							//hack to make sure the collapsed do not disappear
							mainTabView.postDelayed(new Runnable() {
								@Override
								public void run() {
									int realIndex = getRealIndex(header);
									if (mainTabView.getFirstVisiblePosition() > realIndex) {
										mainTabView.setSelection(realIndex);
									}
								}
							}, 100);
						}
					}
				});
				title.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						Header header = (Header) v.getTag();
						mainTabView.setSelection(getRealIndex(header));
					}
				});
				convertView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
					@Override
					public void onViewDetachedFromWindow(View v) {
						attachedHeaderViews.remove(v);
					}

					@Override
					public void onViewAttachedToWindow(View v) {
						attachedHeaderViews.add(v);
						renderHeaderSelection(v);
					}
				});
			} else {
				title = (TextView) convertView.getTag(R.id.title);
				count = (TextView) convertView.getTag(R.id.count);
			}
			Header header = headers.get(position);
			convertView.setTag(header);
			count.setTag(header);
			title.setTag(header);

			title.setText(header.title);
			count.setText("" + header.count);
			title.setCompoundDrawablesWithIntrinsicBounds(header.collapsed ? R.drawable.expand_off : R.drawable.expand_on, 0, 0, 0);
			renderHeaderSelection(convertView);

			// LOG.debug(header + " : convertView = " + convertView);

			return convertView;
		}

		@Override
		public int getCountForHeader(int headerPosition) {
			Header header = headers.get(headerPosition);
			if (header.collapsed)
				return 0;
			return header.count;
		}

		@Override
		public int getNumHeaders() {
			return headers.size();
		}
	}

	int getRealIndex(Header header) {
		int nbColumn = mainTabView.getNumColumns();
		int realIndex = 0;
		for (Header currentHeader : headers) {
			if (currentHeader == header) {
				break;
			} else {
				realIndex += (int) ((Math.ceil(Double.valueOf(currentHeader.collapsed ? 0 : currentHeader.count) / nbColumn) + 1) * nbColumn);
			}
		}
		return realIndex;
	}

	Set<View> attachedHeaderViews = new HashSet<View>();

	ExecutorService executorService = Executors.newSingleThreadExecutor();

	private void renderImageView(final View convertView) {
		if (convertView.getTag() instanceof Media) {
			final Media image = (Media) convertView.getTag();
			final CacheableImageView imageView = (CacheableImageView) convertView.getTag(R.id.image_view);

			imageView.setTag(image);
			final View check_image = (View) convertView.getTag(R.id.check_image);
			check_image.setVisibility(View.GONE);
			final ImageView uploadedImageView = (ImageView) convertView.getTag(R.id.uploaded);
			uploadedImageView.setVisibility(View.GONE);

			final CacheableBitmapDrawable wrapper = Utils.getCache().getFromMemoryCache(image.path + "_size");
			final int reqWidth = mainTabView.getColumnWidth();
			int reqHeight = reqWidth * 3 / 4;
			if (wrapper != null && !wrapper.getBitmap().isRecycled()) {
				// The cache has it, so just display it
				imageView.setImageDrawable(wrapper);
				int width = wrapper.getBitmap().getWidth();
				int height = wrapper.getBitmap().getHeight();
				// int reqHeight = height * reqWidth / width;
				reqHeight = width > height ? reqWidth * 3 / 4 : reqWidth * 3 / 2;
			} else {
				imageView.setImageDrawable(null);
			}
			// convertView.setLayoutParams(new
			// ViewGroup.MarginLayoutParams(reqWidth, reqHeight));

			executorService.submit(new Runnable() {
				@Override
				public void run() {
					if (imageView.getTag() == image) {
						final boolean isUploaded;
						final boolean isUploading;
						isUploaded = FlickrApi.isUploaded(image);
						isUploading = UploadService.isUploading(image);
						final int privacyResource;
						if (isUploaded) {
							privacyResource = getPrivacyResource(FlickrApi.getPrivacy(image));
						} else {
							privacyResource = 0;
						}
						// LOG.debug(tab + ", isUploaded=" + isUploaded);
						final CacheableBitmapDrawable bitmapDrawable;
						if (wrapper != null && !wrapper.getBitmap().isRecycled()) {
							bitmapDrawable = wrapper;
						} else {
							Bitmap bitmap = Utils.getBitmap(image, 2);
							if (bitmap != null) {
								bitmapDrawable = Utils.getCache().put(image.path + "_size", bitmap);
							} else {
								bitmapDrawable = null;
							}
						}
						final boolean isChecked = selectedMedia.contains(image);

						runOnUiThread(new Runnable() {
							@Override
							public void run() {
								if (imageView.getTag() == image) {
									check_image.setVisibility(isChecked ? View.VISIBLE : View.GONE);
									uploadedImageView.setVisibility(isUploaded ? View.VISIBLE : View.GONE);
									((View) convertView.getTag(R.id.uploading)).setVisibility(isUploading ? View.VISIBLE : View.GONE);
									if (wrapper != bitmapDrawable) {
										imageView.setImageDrawable(bitmapDrawable);
										int width = bitmapDrawable.getBitmap().getWidth();
										int height = bitmapDrawable.getBitmap().getHeight();
										int reqWidth = mainTabView.getColumnWidth();
										// int reqHeight = height * reqWidth /
										// width;
										int reqHeight = width > height ? reqWidth * 3 / 4 : reqWidth * 3 / 2;
										// convertView.setLayoutParams(new
										// ViewGroup.MarginLayoutParams(reqWidth,
										// reqHeight));
									}
									if (privacyResource != 0) {
										uploadedImageView.setImageResource(privacyResource);
									}
								}
							}
						});
					}
				}
			});
		}
	}

	static SparseArray<String> uploadedPhotos = new SparseArray<String>();

	private Menu menu;

	private StickyGridHeadersGridView mainTabView;

	private boolean paused = false;

	@Override
	public void onBackPressed() {
		if (slidingDrawer != null && slidingDrawer.isOpened()) {
			slidingDrawer.animateClose();
		} else {
			moveTaskToBack(true);
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		this.menu = menu;
		super.onCreateOptionsMenu(menu);
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.main_menu, menu);
		return true;
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		renderMenu();
		return super.onPrepareOptionsMenu(menu);
	}

	private void renderMenu() {
		if (menu != null) {
			menu.findItem(R.id.trial_info).setVisible(!Utils.isPremium());
		}
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		Mixpanel.track("UI actionBar " + getItemName(item));
		switch (item.getItemId()) {
		case R.id.trial_info:
			Utils.showPremiumDialog(activity, new Utils.Callback<Boolean>() {
				@Override
				public void onResult(Boolean result) {
					renderPremium();
				}
			});
			break;
		case R.id.preferences:
			startActivity(new Intent(activity, Preferences.class));
			break;
		case R.id.faq:
			String url = "https://github.com/rafali/flickr-uploader/wiki/FAQ";
			Intent i = new Intent(Intent.ACTION_VIEW);
			i.setData(Uri.parse(url));
			startActivity(i);
			break;
		case R.id.sort_time_0:
			Utils.setLongProperty("sort_type", 0L);
			Mixpanel.track("Sort", "type", getSort());
			load();
			break;
		case R.id.sort_time_1:
			Utils.setLongProperty("sort_type", 1L);
			Mixpanel.track("Sort", "type", getSort());
			load();
			break;
		case R.id.view_size_0:
			mainTabView.setNumColumns(4);
			item.setChecked(true);
			break;
		case R.id.view_size_1:
			mainTabView.setNumColumns(2);
			item.setChecked(true);
			break;
		case R.id.view_size_2:
			mainTabView.setNumColumns(1);
			item.setChecked(true);
			break;
		}

		return (super.onOptionsItemSelected(item));
	}

	private String getItemName(MenuItem item) {
		try {
			return getResources().getResourceEntryName(item.getItemId());
		} catch (Throwable e) {
		}
		return "unknown";
	}

	@UiThread
	void showSortDialog() {
		final CharSequence[] modes = { "Recent to old", "Old to recent" };
		AlertDialog.Builder alt_bld = new AlertDialog.Builder(activity);
		alt_bld.setTitle("Sort");
		final int sort_type = (int) Utils.getLongProperty("sort_type");
		alt_bld.setSingleChoiceItems(modes, sort_type, new OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				dialog.dismiss();
				if (sort_type != which) {
					LOG.debug("clicked : " + modes[which]);
					Utils.setLongProperty("sort_type", (long) which);
					Mixpanel.track("Sort", "type", getSort());
					load();
				}
			}
		});
		AlertDialog alert = alt_bld.create();
		alert.show();
	}

	@Override
	protected void onResume() {
		paused = false;
		super.onResume();
		refresh(false);
		UploadService.wake();
		renderPremium();
		drawerHandleView.onResume();
	}

	@UiThread
	void confirmSync() {
		final CharSequence[] modes = { "Auto-upload new photos", "Auto-upload new videos" };
		AlertDialog.Builder builder = new AlertDialog.Builder(activity);
		builder.setTitle("Auto-upload (7-days Trial)");
		builder.setMultiChoiceItems(modes, new boolean[] { true, true }, null);
		builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int id) {
				ListView lw = ((AlertDialog) dialog).getListView();
				Utils.setBooleanProperty(Preferences.AUTOUPLOAD, lw.isItemChecked(0));
				Utils.setBooleanProperty(Preferences.AUTOUPLOAD_VIDEOS, lw.isItemChecked(1));
			}

		});
		builder.setNegativeButton("More options", new OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				ListView lw = ((AlertDialog) dialog).getListView();
				Utils.setBooleanProperty(Preferences.AUTOUPLOAD, lw.isItemChecked(0));
				Utils.setBooleanProperty(Preferences.AUTOUPLOAD_VIDEOS, lw.isItemChecked(1));
				startActivity(new Intent(activity, Preferences.class));
			}
		});
		builder.setCancelable(false);
		builder.create().show();
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		if (IabHelper.get(false) != null && IabHelper.get(false).handleActivityResult(requestCode, resultCode, data)) {
			return;
		}
		if (resultCode == WebAuth.RESULT_CODE_AUTH) {
			if (FlickrApi.isAuthentified()) {
				confirmSync();
			}
		} else {
			super.onActivityResult(requestCode, resultCode, data);
		}
	}

	@UiThread(delay = 100)
	public void refresh(boolean reload) {
		if (reload) {
			load();
		} else {
			if (mainTabView != null) {
				renderMenu();
				ViewGroup grid = (ViewGroup) mainTabView;
				int childCount = grid.getChildCount();
				for (int i = 0; i < childCount; i++) {
					View convertView = grid.getChildAt(i);
					Media image = (Media) convertView.getTag();
					if (image != null) {
						renderImageView(convertView);
					}
				}
			}
		}
	}

	int getPrivacyResource(PRIVACY privacy) {
		if (privacy != null) {
			switch (privacy) {
			case PUBLIC:
				return R.drawable.uploaded_public;
			case FAMILY:
			case FRIENDS:
			case FRIENDS_FAMILY:
				return R.drawable.uploaded_friends;
			default:
				break;
			}
		}
		return R.drawable.uploaded;
	}

	public static void staticRefresh(boolean reload) {
		if (instance != null) {
			instance.refresh(reload);
		}
	}

	@Override
	protected void onPause() {
		paused = true;
		super.onPause();
	}

	public boolean isPaused() {
		return paused;
	}

	@UiThread
	void renderPremium() {
		if (!destroyed) {
			getActionBar().setSubtitle(null);
			getActionBar().setTitle(null);
			// if (Utils.isPremium()) {
			// getActionBar().setSubtitle(null);
			// } else {
			// if (Utils.getBooleanProperty(Preferences.AUTOUPLOAD, false) ||
			// Utils.getBooleanProperty(Preferences.AUTOUPLOAD_VIDEOS, false)) {
			// if (Utils.isTrial()) {
			// getActionBar().setSubtitle("Auto-Upload Trial");
			// } else {
			// getActionBar().setSubtitle("Trial Expired");
			// }
			// } else {
			// getActionBar().setSubtitle(null);
			// if (!Utils.isTrial()) {
			// // FIXME
			// }
			// }
			// }
		}
	}

}
