package org.aurex.features.profilebg;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.os.Build;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.BasePermissionsActivity;
import org.telegram.ui.PhotoAlbumPickerActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;

/**
 * Выбор изображения для фона профиля.
 *
 * <p>Используется штатная галерея Telegram в режиме выбора обоев
 * ({@code SELECT_TYPE_WALLPAPER}): тот же экран, та же анимация открытия и тот же экран
 * кадрирования, что и при установке обоев чата. Порядок вызовов повторяет
 * {@code org.telegram.ui.Components.WallpaperUpdater} апстрима.
 */
public final class ProfileBackgroundPicker {

    private static final int JPEG_QUALITY = 92;

    private ProfileBackgroundPicker() {
    }

    public interface Callback {
        void onBackgroundChanged();
    }

    public static void openGallery(BaseFragment fragment, Callback callback) {
        if (fragment == null) {
            return;
        }
        final Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            if (activity.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(
                    new String[]{Manifest.permission.READ_MEDIA_IMAGES},
                    BasePermissionsActivity.REQUEST_CODE_EXTERNAL_STORAGE
                );
                return;
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            if (activity.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    BasePermissionsActivity.REQUEST_CODE_EXTERNAL_STORAGE
                );
                return;
            }
        }

        final PhotoAlbumPickerActivity picker =
            new PhotoAlbumPickerActivity(PhotoAlbumPickerActivity.SELECT_TYPE_WALLPAPER, false, false, null);
        picker.setAllowSearchImages(false);
        picker.setMaxSelectedPhotos(1, false);
        picker.setDelegate(new PhotoAlbumPickerActivity.PhotoAlbumPickerActivityDelegate() {
            @Override
            public void didSelectPhotos(ArrayList<SendMessagesHelper.SendingMediaInfo> photos, boolean notify, int scheduleDate) {
                if (save(activity, photos) && callback != null) {
                    callback.onBackgroundChanged();
                }
            }

            @Override
            public void startPhotoSelectActivity() {
            }
        });
        fragment.presentFragment(picker);
    }

    private static boolean save(Activity activity, ArrayList<SendMessagesHelper.SendingMediaInfo> photos) {
        if (photos == null || photos.isEmpty()) {
            return false;
        }
        final SendMessagesHelper.SendingMediaInfo info = photos.get(0);
        if (info == null || info.path == null) {
            return false;
        }
        FileOutputStream stream = null;
        try {
            final Point screenSize = AndroidUtilities.getRealScreenSize();
            final Bitmap bitmap = ImageLoader.loadBitmap(info.path, null, screenSize.x, screenSize.y, true);
            if (bitmap == null) {
                return false;
            }
            final File target = ProfileBackground.file(activity);
            stream = new FileOutputStream(target);
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream);
            stream.flush();
            ProfileBackground.invalidateCache();
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (Throwable ignore) {
                }
            }
        }
    }
}
