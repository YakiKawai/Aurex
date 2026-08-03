package org.aurex.features.profilebg;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;

/**
 * Хранилище фона шапки собственного профиля.
 *
 * <p>Фон хранится только локально: в Telegram нет серверного поля под произвольный
 * баннер профиля, поэтому картинка не уходит на сервер и видна только владельцу
 * устройства.
 *
 * <p>Файл лежит во внутренней памяти приложения, а не в кэше: кэш Telegram чистит сам,
 * и фон бесследно пропадал бы после очистки кэша.
 */
public final class ProfileBackground {

    private static final String DIRECTORY = "aurex";
    private static final String FILE_NAME = "profile_background.jpg";

    private static Bitmap cachedBitmap;
    private static boolean cacheLoaded;

    private ProfileBackground() {
    }

    public static File file(Context context) {
        final File directory = new File(context.getApplicationContext().getFilesDir(), DIRECTORY);
        if (!directory.exists()) {
            directory.mkdirs();
        }
        return new File(directory, FILE_NAME);
    }

    public static boolean has(Context context) {
        if (context == null) {
            return false;
        }
        return file(context).exists();
    }

    /**
     * Возвращает декодированный фон или {@code null}.
     *
     * <p>Результат кэшируется: метод вызывается из onDraw шапки профиля, там нельзя
     * трогать диск на каждом кадре.
     */
    public static Bitmap bitmap(Context context) {
        if (cacheLoaded) {
            return cachedBitmap;
        }
        cacheLoaded = true;
        cachedBitmap = null;
        if (context == null) {
            return null;
        }
        final File file = file(context);
        if (!file.exists()) {
            return null;
        }
        try {
            final BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            cachedBitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (Throwable ignore) {
            cachedBitmap = null;
        }
        return cachedBitmap;
    }

    /**
     * Сбрасывает кэш, но не уничтожает bitmap: старый кадр может в этот момент
     * рисоваться на экране, а recycle() привёл бы к падению в отрисовке.
     */
    public static void invalidateCache() {
        cacheLoaded = false;
        cachedBitmap = null;
    }

    public static void remove(Context context) {
        if (context == null) {
            return;
        }
        try {
            file(context).delete();
        } catch (Throwable ignore) {
        }
        invalidateCache();
    }
}
