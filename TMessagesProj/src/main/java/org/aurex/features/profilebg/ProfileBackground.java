package org.aurex.features.profilebg;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * Хранилище фона шапки профиля.
 *
 * <p>Фон живёт только на устройстве: в Telegram нет серверного поля под произвольный
 * баннер профиля, поэтому картинка никуда не отправляется и видна только владельцу.
 *
 * <p>Файл лежит во внутренней памяти приложения, а не в кэше: кэш Telegram чистит сам,
 * и фон бесследно пропадал бы после очистки.
 *
 * <p>Файл именуется по id пользователя, поэтому у каждого аккаунта свой фон, а
 * переключение аккаунтов ничего не перетирает. Привязка именно к id, а не к номеру
 * слота: слоты переиспользуются после выхода из аккаунта.
 */
public final class ProfileBackground {

    private static final String DIRECTORY = "aurex";
    private static final String FILE_PREFIX = "profile_background_";
    private static final String FILE_SUFFIX = ".jpg";

    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    /**
     * Кэш ровно на один аккаунт: шапка профиля в один момент времени рисуется для
     * одного пользователя, а держать в памяти несколько полноэкранных bitmap незачем.
     * Все поля читаются и пишутся только в UI-потоке.
     */
    private static long cachedUserId;
    private static boolean cacheLoaded;
    private static Bitmap cachedBitmap;
    private static long loadingUserId;

    private ProfileBackground() {
    }

    public static long userId(int account) {
        return UserConfig.getInstance(account).getClientUserId();
    }

    public static File file(long userId) {
        final File directory = new File(ApplicationLoader.getFilesDirFixed(), DIRECTORY);
        if (!directory.exists()) {
            directory.mkdirs();
        }
        return new File(directory, FILE_PREFIX + userId + FILE_SUFFIX);
    }

    public static boolean has(int account) {
        final long userId = userId(account);
        if (userId == 0) {
            return false;
        }
        if (cacheLoaded && cachedUserId == userId) {
            return cachedBitmap != null;
        }
        return file(userId).exists();
    }

    /**
     * Возвращает фон текущего аккаунта, если он уже в памяти.
     *
     * <p>Метод вызывается из onDraw, поэтому диск здесь не трогается: при промахе кэша
     * запускается фоновая загрузка, а по её окончании дёргается {@code onLoaded} —
     * шапка перерисуется уже с картинкой.
     *
     * @return bitmap или {@code null}, если фона нет либо он ещё не загружен.
     */
    public static Bitmap bitmap(int account, Runnable onLoaded) {
        final long userId = userId(account);
        if (userId == 0) {
            return null;
        }
        if (cacheLoaded && cachedUserId == userId) {
            return cachedBitmap;
        }
        load(userId, onLoaded);
        return null;
    }

    private static void load(long userId, Runnable onLoaded) {
        if (loadingUserId == userId) {
            return;
        }
        loadingUserId = userId;
        Utilities.globalQueue.postRunnable(() -> {
            Bitmap decoded = null;
            final File file = file(userId);
            if (file.exists()) {
                try {
                    final BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    decoded = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
            final Bitmap result = decoded;
            AndroidUtilities.runOnUIThread(() -> {
                if (loadingUserId == userId) {
                    loadingUserId = 0;
                }
                cachedUserId = userId;
                cachedBitmap = result;
                cacheLoaded = true;
                if (onLoaded != null) {
                    onLoaded.run();
                }
            });
        });
    }

    /**
     * Сохраняет выбранное изображение как фон аккаунта.
     *
     * <p>Файл именно копируется, а не перекодируется: картинка уже прошла штатный
     * редактор Telegram и сжата им, второе сжатие только испортило бы качество.
     *
     * @param onDone вызывается в UI-потоке после завершения, успешного или нет.
     */
    public static void set(int account, File source, Runnable onDone) {
        final long userId = userId(account);
        if (userId == 0 || source == null) {
            if (onDone != null) {
                onDone.run();
            }
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            final boolean saved = copy(source, file(userId));
            AndroidUtilities.runOnUIThread(() -> {
                if (saved) {
                    invalidateCache(userId);
                }
                if (onDone != null) {
                    onDone.run();
                }
            });
        });
    }

    public static void remove(int account) {
        final long userId = userId(account);
        if (userId == 0) {
            return;
        }
        invalidateCache(userId);
        Utilities.globalQueue.postRunnable(() -> {
            try {
                file(userId).delete();
            } catch (Throwable ignore) {
            }
        });
    }

    /**
     * Сбрасывает кэш, но не вызывает recycle(): старый кадр может рисоваться прямо
     * сейчас, и уничтожение bitmap уронило бы отрисовку. Память освободит сборщик.
     */
    private static void invalidateCache(long userId) {
        if (cachedUserId == userId) {
            cacheLoaded = false;
            cachedBitmap = null;
            loadingUserId = 0;
        }
    }

    private static boolean copy(File source, File target) {
        FileInputStream input = null;
        FileOutputStream output = null;
        try {
            input = new FileInputStream(source);
            output = new FileOutputStream(target);
            final byte[] buffer = new byte[COPY_BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) > 0) {
                output.write(buffer, 0, read);
            }
            output.flush();
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        } finally {
            close(input);
            close(output);
        }
    }

    private static void close(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Throwable ignore) {
        }
    }
}
