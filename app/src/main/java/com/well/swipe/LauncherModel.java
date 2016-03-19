package com.well.swipe;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.HandlerThread;

import com.well.swipe.utils.Utilities;

import java.lang.ref.WeakReference;
import java.net.URISyntaxException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;


/**
 * Created by mingwei on 3/14/16.
 */
public class LauncherModel extends BroadcastReceiver {
    /**
     * 加载default_workspace
     */
    public LoadTask mLoadTask;
    /**
     * 应用Application注册LauncherModel
     */
    private SwipeApplication mApplication;
    /**
     * 所有的app数据
     */
    private AllAppsList mAllAppsList;
    /**
     * 图片缓存
     */
    private IconCache mIconCache;

    private final HandlerThread mWorkerThread = new HandlerThread("swipe-load");

    private final Handler mWorker = new Handler();

    private Callback mCallBack;

    private WeakReference<Callback> mCallback;

    private Bitmap mDefaultIcon;

    public interface Callback {
        /**
         * 加载刚开始的时候掉用
         */
        void bindStart();

        /**
         * 绑定所有的app数据
         *
         * @param appslist
         */
        void bindAllApps(ArrayList<ItemApplication> appslist);

        /**
         * 绑定要在swipe上显示的app
         *
         * @param appslist applist
         */
        void bindFavorites(ArrayList<ItemApplication> appslist);

        /**
         * 绑定Switch
         *
         * @param switchlist
         */
        void bindSwitch(ArrayList<ItemSwipeSwitch> switchlist);

        /**
         * 加载完成式掉用
         */
        void bindFinish();
    }

    public LauncherModel(SwipeApplication app, IconCache iconCache) {
        mApplication = app;
        mAllAppsList = new AllAppsList(iconCache);
        mIconCache = iconCache;
        mDefaultIcon = Utilities.createIconBitmap(
                mIconCache.getFullResDefaultActivityIcon(), app);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

    }

    public void initCallBack(Callback callback) {
        mCallback = new WeakReference<>(callback);
    }

    public void startLoadTask() {
        mLoadTask = new LoadTask(mApplication);
        mLoadTask.run();
    }

    static ComponentName getComponentNameFromResolveInfo(ResolveInfo info) {
        if (info.activityInfo != null) {
            return new ComponentName(info.activityInfo.packageName, info.activityInfo.name);
        } else {
            return new ComponentName(info.serviceInfo.packageName, info.serviceInfo.name);
        }
    }

    private class LoadTask implements Runnable {

        private Context mContext;

        private HashMap<Object, CharSequence> mLabelCache;

        public LoadTask(Context context) {
            mContext = context;
            mLabelCache = new HashMap<>();
        }

        @Override
        public void run() {
            bindStart();
            loadDefaultWorkspace();
            bindFavorites();
            bindSwitch();
            bindFinish();
            loadAndBindAllApps();
        }

        private void loadDefaultWorkspace() {
            mApplication.getProvider().loadDefaultFavoritesIfNecessary(R.xml.default_workspace);
        }

        /**
         * 加载设备上的app数据
         */
        private void loadAndBindAllApps() {
            Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            PackageManager manager = mContext.getPackageManager();
            List<ResolveInfo> mInfoLists = manager.queryIntentActivities(mainIntent, 0);
            Collections.sort(mInfoLists,
                    new LauncherModel.ShortcutNameComparator(manager, mLabelCache));

            for (int i = 0; i < mInfoLists.size(); i++) {
                mAllAppsList.data.add(new ItemApplication(manager, mInfoLists.get(i), mIconCache, mLabelCache));
            }
            ArrayList<ItemApplication> applications = new ArrayList<>();
            if (applications.size() != 0) {
                applications.clear();
            }
            applications.addAll(mAllAppsList.data);

            mCallback.get().bindAllApps(applications);

        }

        /**
         * 从表中读出数据传到Service
         */
        private void bindFavorites() {
            ContentResolver resolver = mContext.getContentResolver();
            Cursor cursor = resolver.query(SwipeSettings.Favorites.CONTENT_URI, null, SwipeSettings.
                    BaseColumns.ITEM_TYPE + "=?", new String[]{String.valueOf(SwipeSettings.
                    BaseColumns.ITEM_TYPE_APPLICATION)}, null);
            ArrayList<ItemApplication> favorites = new ArrayList<>();
            for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
                int type = cursor.getInt(cursor.getColumnIndexOrThrow(SwipeSettings.BaseColumns.ITEM_TYPE));
                String title = cursor.getString(cursor.getColumnIndexOrThrow(SwipeSettings.BaseColumns.ITEM_TITLE));
                String intentStr = cursor.getString(cursor.getColumnIndexOrThrow(SwipeSettings.BaseColumns.ITEM_INTENT));
                int iconType = cursor.getInt(cursor.getColumnIndexOrThrow(SwipeSettings.BaseColumns.ICON_TYPE));
                int packagenameIndex = cursor.getColumnIndexOrThrow(SwipeSettings.BaseColumns.ICON_PACKAGENAME);
                int resourcenameIndex = cursor.getColumnIndexOrThrow(SwipeSettings.BaseColumns.ICON_RESOURCE);
                int iconIndex = cursor.getColumnIndexOrThrow(SwipeSettings.BaseColumns.ICON_BITMAP);
                Intent intent = null;
                Bitmap icon = null;
                try {
                    intent = Intent.parseUri(intentStr, 0);
                } catch (URISyntaxException e) {

                }
                ItemApplication application = new ItemApplication();
                application.mType = type;
                application.mTitle = title;
                application.mIntent = intent;
                switch (iconType) {
                    case SwipeSettings.BaseColumns.ICON_TYPE_RESOURCE:
                        String packagename = cursor.getString(packagenameIndex);
                        String resourcename = cursor.getString(resourcenameIndex);
                        PackageManager packageManager = mContext.getPackageManager();
                        try {
                            Resources resources = packageManager.getResourcesForApplication(packagename);
                            if (resources != null) {
                                final int id = resources.getIdentifier(resourcename, null, null);
                                icon = Utilities.createIconBitmap(
                                        mIconCache.getFullResIcon(resources, id), mContext);
                            }
                        } catch (PackageManager.NameNotFoundException e) {
                            e.printStackTrace();
                        }
                        if (icon == null) {
                            icon = getIconFromCursor(cursor, iconIndex, mContext);
                        }
                        if (icon == null) {
                            icon = getFallbackIcon();
                            application.isFallbackIcon = true;
                        }
                        break;
                    case SwipeSettings.BaseColumns.ICON_TYPE_BITMAP:
                        icon = getIconFromCursor(cursor, iconIndex, mContext);
                        if (icon == null) {
                            icon = getFallbackIcon();
                            application.isCustomIcon = false;
                            application.isFallbackIcon = true;
                        } else {
                            application.isCustomIcon = true;
                        }
                        break;
                    default:
                        icon = getFallbackIcon();
                        application.isFallbackIcon = true;
                        application.isCustomIcon = false;
                        break;
                }

                application.mIconBitmap = icon;

                favorites.add(application);
            }
            mCallback.get().bindFavorites(favorites);
        }

        private void bindSwitch() {
            ContentResolver resolver = mContext.getContentResolver();
            Cursor cursor = resolver.query(SwipeSettings.Favorites.CONTENT_URI, null, SwipeSettings.
                    BaseColumns.ITEM_TYPE + "=?", new String[]{String.valueOf(SwipeSettings.
                    BaseColumns.ITEM_TYPE_SWITCH)}, null);
            ArrayList<ItemSwipeSwitch> switches = new ArrayList<>();
            for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
                ItemSwipeSwitch application = new ItemSwipeSwitch();
                application.mType = cursor.getInt(cursor.getColumnIndexOrThrow(SwipeSettings.BaseColumns.ITEM_TYPE));
                application.mTitle = cursor.getString(cursor.getColumnIndexOrThrow(SwipeSettings.BaseColumns.ITEM_TITLE));
                application.mAction = cursor.getString(cursor.getColumnIndexOrThrow(SwipeSettings.BaseColumns.ITEM_ACTION));
                application.mTitle = cursor.getString(cursor.getColumnIndexOrThrow(SwipeSettings.BaseColumns.ITEM_TITLE));
                //#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;launchFlags=0x10200000;component=com.android.camera/.Camera;end
                //index.add(cursor.getInt(cursor.getColumnIndexOrThrow(SwipeSettings.Favorites.ITEM_INDEX)));
                switches.add(application);
            }
            mCallback.get().bindSwitch(switches);
        }

        private void bindStart() {
            mCallback.get().bindStart();
        }

        private void bindFinish() {
            mCallback.get().bindFinish();
        }

        private void matchItomIcon() {

        }

        Bitmap getIconFromCursor(Cursor c, int iconIndex, Context context) {
            @SuppressWarnings("all") // suppress dead code warning
            final boolean debug = false;
            byte[] data = c.getBlob(iconIndex);
            try {
                return Utilities.createIconBitmap(BitmapFactory.decodeByteArray(data, 0, data.length), context);
            } catch (Exception e) {
                return null;
            }
        }

        public Bitmap getFallbackIcon() {
            return Bitmap.createBitmap(mDefaultIcon);
        }

    }

    private class PackageUpdataTask implements Runnable {

        @Override
        public void run() {

        }
    }

    public static class ShortcutNameComparator implements Comparator<ResolveInfo> {
        private Collator mCollator;
        private PackageManager mPackageManager;
        private HashMap<Object, CharSequence> mLabelCache;

        ShortcutNameComparator(PackageManager pm) {
            mPackageManager = pm;
            mLabelCache = new HashMap<Object, CharSequence>();
            mCollator = Collator.getInstance();
        }

        ShortcutNameComparator(PackageManager pm, HashMap<Object, CharSequence> labelCache) {
            mPackageManager = pm;
            mLabelCache = labelCache;
            mCollator = Collator.getInstance();
        }

        public final int compare(ResolveInfo a, ResolveInfo b) {
            CharSequence labelA, labelB;
            ComponentName keyA = LauncherModel.getComponentNameFromResolveInfo(a);
            ComponentName keyB = LauncherModel.getComponentNameFromResolveInfo(b);
            if (mLabelCache.containsKey(keyA)) {
                labelA = mLabelCache.get(keyA);
            } else {
                labelA = a.loadLabel(mPackageManager).toString();

                mLabelCache.put(keyA, labelA);
            }
            if (mLabelCache.containsKey(keyB)) {
                labelB = mLabelCache.get(keyB);
            } else {
                labelB = b.loadLabel(mPackageManager).toString();

                mLabelCache.put(keyB, labelB);
            }
            return mCollator.compare(labelA, labelB);
        }
    }

    ;
}